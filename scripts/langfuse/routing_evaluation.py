"""Current-policy routing smoke evaluation. Java owns model inference; Python scores and uploads."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import sys
import time
import uuid

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'docs/eval/agent_routing'))
sys.path.insert(0,str(ROOT/'docs/interview'))
from route_eval import load_jsonl, validate_dataset, evaluate_cases
from eval_retrieval import upload_scores
import retrieval_experiments as cloud

IDS=['test-search-001','test-search-002','test-search-016','test-search-030',
     'test-direct-001','test-direct-013','test-direct-056','test-direct-058','test-direct-059','test-direct-060']
DIRECT_IDS={'test-direct-058','test-direct-059','test-direct-060'}

def write(path,data):
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def prepare(directory,source=None):
    source=Path(source) if source else ROOT/'docs/eval/agent_routing/routing-test.jsonl'
    cases=load_jsonl(source)
    validate_dataset(cases,expected_split='test')
    if not source.is_file(): raise ValueError('dataset_missing')
    by_id={c['id']:c for c in cases}
    if not set(IDS).issubset(by_id): raise ValueError('smoke_case_ids_missing')
    selected=[]
    for identifier in IDS:
        sample=dict(by_id[identifier])
        if source.resolve()==(ROOT/'docs/eval/agent_routing/routing-test.jsonl').resolve():
            sample['historicalExpectedRoute']=sample['expectedRoute']
            sample['expectedRoute']='DIRECT' if identifier in DIRECT_IDS else 'SEARCH'
            sample['annotationStatus']='pending_human_review'
            sample['labelPolicy']='knowledge-first candidate; factual questions search, pure calculation/greeting/provided-text rewrite direct'
        selected.append(sample)
    directory.mkdir(parents=True,exist_ok=False)
    (directory/'dataset.jsonl').write_text(''.join(json.dumps(c,ensure_ascii=False)+'\n' for c in selected),encoding='utf-8')
    write(directory/'dataset-manifest.json',dict(source=str(source.relative_to(ROOT)) if source.is_relative_to(ROOT) else source.name,
        source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),dataset_sha256=hashlib.sha256((directory/'dataset.jsonl').read_bytes()).hexdigest(),
        created_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),sample_ids=IDS,label_status='human_reviewed' if all(c['annotationStatus']=='human_reviewed' for c in selected) else 'pending_human_review'))

def evaluate(cases,rows,run,policy_hash):
    validate_dataset(cases,expected_split='test')
    by_id={r['id']:r for r in rows}
    if len(by_id)!=len(rows) or set(by_id)!={c['id'] for c in cases}: raise ValueError('prediction_coverage_invalid')
    valid=[]; failures=[]; scores=[]
    for sample in cases:
        row=by_id[sample['id']]
        if row['policy_sha256']!=policy_hash: raise ValueError('policy_mismatch')
        for key,size in [('trace_id',32),('observation_id',16)]:
            if not re.fullmatch('[0-9a-f]{'+str(size)+'}',row[key]) or set(row[key])=={'0'}: raise ValueError('trace_identity_invalid')
        if row.get('expectedRoute',sample['expectedRoute'])!=sample['expectedRoute'] or row.get('annotationStatus',sample['annotationStatus'])!=sample['annotationStatus']: raise ValueError('label_mismatch')
        if row.get('error') or row.get('predictedRoute') not in ('SEARCH','DIRECT'):
            if row.get('predictedRoute') is not None or row.get('selectedAttempt') is not None: raise ValueError('failed_prediction_has_selection')
            failures.append(dict(id=sample['id'],error=row.get('error') or 'INVALID_ROUTE',trace_id=row['trace_id']))
            continue
        selected=[a for a in row['attempts'] if a['attempt']==row['selectedAttempt']]
        if len(selected)!=1 or selected[0]['predictedRoute']!=row['predictedRoute'] or selected[0].get('error'): raise ValueError('attempt_selection_invalid')
        if [a['attempt'] for a in row['attempts']]!=list(range(1,len(row['attempts'])+1)) or row['selectedAttempt']!=len(row['attempts']): raise ValueError('attempt_sequence_invalid')
        if any(not a.get('error') for a in row['attempts'][:-1]): raise ValueError('earlier_success_not_selected')
        valid.append(row)
        scores.append(dict(id=str(uuid.uuid5(uuid.NAMESPACE_URL,run+'/'+sample['id']+'/route_correct')),
            traceId=row['trace_id'],observationId=row['observation_id'],name='route_correct',dataType='NUMERIC',
            value=int(row['predictedRoute']==sample['expectedRoute']),comment='Routing evaluation; '+sample['annotationStatus']+'; policy '+policy_hash))
    valid_ids={r['id'] for r in valid}
    report=evaluate_cases([c for c in cases if c['id'] in valid_ids],valid)
    direct=[c for c in cases if c['expectedRoute']=='DIRECT']
    report.update(run_id=run,policy_sha256=policy_hash,valid_total=len(valid),total=len(cases),valid_accuracy=report['accuracy'],
        accuracy=report['correct']/len(cases),failure_count=len(failures),failures=failures,
        direct_accuracy=sum(by_id[c['id']].get('predictedRoute')=='DIRECT' and not by_id[c['id']].get('error') for c in direct)/len(direct) if direct else None,
        evaluation_status='human_reviewed' if all(c['annotationStatus']=='human_reviewed' for c in cases) else 'candidate_unreviewed')
    report['wrong_cases']=[dict(id=c['id'],query=c['query'],expectedRoute=c['expectedRoute'],predictedRoute=by_id[c['id']]['predictedRoute'],trace_id=by_id[c['id']]['trace_id']) for c in cases if c['id'] in valid_ids and c['expectedRoute']!=by_id[c['id']]['predictedRoute']]
    return report,scores

def score(directory,upload=False):
    manifest=json.loads((directory/'dataset-manifest.json').read_text(encoding='utf-8'))
    if hashlib.sha256((directory/'dataset.jsonl').read_bytes()).hexdigest()!=manifest['dataset_sha256']: raise ValueError('dataset_changed')
    policy=json.loads((directory/'policy.json').read_text(encoding='utf-8'))
    report,scores=evaluate(load_jsonl(directory/'dataset.jsonl'),load_jsonl(directory/'predictions.jsonl'),policy['run_id'],policy['policy_sha256'])
    write(directory/'report.json',report); write(directory/'scores.json',scores)
    lines=['# 当前策略路由评测', '',f"运行：{report['run_id']}",f"标签状态：{report['evaluation_status']}",
        f"样本 {report['total']}；成功 {report['valid_total']}；失败 {report['failure_count']}；正确 {report['correct']}。",
        f"整体准确率（失败计入分母）：{report['accuracy']:.1%}；成功样本准确率：{report['valid_accuracy']:.1%}。",
        f"DIRECT 子集准确率：{report['direct_accuracy']:.1%}。" if report['direct_accuracy'] is not None else '本次无 DIRECT 样本。',
        '混淆矩阵（行=标签，列=预测；失败样本另列）：\n\n|标签 / 预测|SEARCH|DIRECT|\n|---|---:|---:|\n'
        + '\n'.join(f"|{route}|{report['confusion'][route]['SEARCH']}|{report['confusion'][route]['DIRECT']}|" for route in ['SEARCH','DIRECT']),
        '仅首轮路由；未执行检索和后续回答。未复核标签结果仅用于联调，不能作为简历指标。','', '## 错分样本',json.dumps(report['wrong_cases'],ensure_ascii=False,indent=2), '', '## 失败样本',json.dumps(report['failures'],ensure_ascii=False,indent=2)]
    (directory/'report.md').write_text('\n\n'.join(lines)+'\n',encoding='utf-8')
    if upload:
        write(directory/'upload-status.json',dict(status='pending',expected_count=len(scores)))
        count=upload_scores(scores)
        write(directory/'upload-status.json',dict(status='success',uploaded_count=count))
    return report

def verify(directory):
    manifest=json.loads((directory/'dataset-manifest.json').read_text(encoding='utf-8'))
    policy=json.loads((directory/'policy.json').read_text(encoding='utf-8'))
    rows=load_jsonl(directory/'predictions.jsonl'); scores=json.loads((directory/'scores.json').read_text(encoding='utf-8'))
    params=dict(fromStartTime=manifest['created_at'],toStartTime=datetime.datetime.now(datetime.timezone.utc).isoformat(),sessionId=policy['run_id'],fields='core,basic,metadata,io',limit=100)
    response=cloud.get('/api/public/v2/observations',params)
    if response.get('meta',{}).get('nextCursor'): raise ValueError('unexpected_observation_pagination')
    observations={o['id']:o for o in response['data']}
    for row in rows:
        root=observations[row['observation_id']]
        assert root['traceId']==row['trace_id'] and root['name']=='routing.evaluation'
        assert root.get('input') is None and root.get('output') is None
        metadata=root.get('metadata') or {}
        if isinstance(metadata,str): metadata=json.loads(metadata)
        assert metadata['policy_sha256']==row['policy_sha256'] and metadata['id']==row['id']
        assert metadata['messages_sha256']==row['messages_sha256']
        assert metadata['expectedRoute']==row['expectedRoute']
        assert metadata.get('predictedRoute')==row['predictedRoute']
        assert metadata['annotationStatus']==row['annotationStatus']
        assert str(metadata['selectedAttempt'])==str(row['selectedAttempt'] if row['selectedAttempt'] is not None else 'none')
        for attempt in row['attempts']:
            child=observations[attempt['observation_id']]
            assert child['parentObservationId']==row['observation_id'] and child['traceId']==row['trace_id']
            assert child['name']=='llm.round' and child.get('input') is None and child.get('output') is None
            child_metadata=child.get('metadata') or {}
            if isinstance(child_metadata,str): child_metadata=json.loads(child_metadata)
            assert str(child_metadata['attempt'])==str(attempt['attempt'])
            assert child_metadata['policy_sha256']==row['policy_sha256']
            if attempt['error']:
                assert child_metadata['error_code']==attempt['error'] and child['level']=='ERROR'
    actual=[]
    for start in range(0,len(scores),100):
        actual.extend(cloud.get('/api/public/v3/scores',dict(id=','.join(s['id'] for s in scores[start:start+100]),fields='subject',limit=100))['data'])
    by_id={s['id']:s for s in actual}
    for score in scores:
        fetched=by_id[score['id']]
        assert fetched['name']=='route_correct' and fetched['value']==score['value']
        assert fetched['dataType']=='NUMERIC'
        assert fetched['subject']==dict(kind='observation',id=score['observationId'],traceId=score['traceId'])
    evidence=dict(verified=True,run_id=policy['run_id'],trace_count=len(rows),attempt_count=sum(len(r['attempts']) for r in rows),score_count=len(scores))
    write(directory/'cloud-verification.json',evidence)
    return evidence

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['prepare','score','verify']); parser.add_argument('output_dir',type=Path)
    parser.add_argument('--dataset',type=Path); parser.add_argument('--upload',action='store_true')
    args=parser.parse_args()
    try:
        if args.command=='prepare': prepare(args.output_dir,args.dataset)
        elif args.command=='score':
            report=score(args.output_dir,args.upload)
            print(json.dumps({k:report[k] for k in ['run_id','total','correct','failure_count','accuracy','evaluation_status']}))
        else:
            # OTLP and score ingestion are asynchronous. Wait only for eventual visibility.
            for attempt in range(6):
                try:
                    evidence=verify(args.output_dir)
                    print(json.dumps(evidence)); break
                except (KeyError,AssertionError):
                    if attempt==5: raise
                    time.sleep(5)
    except Exception as error:
        print('Routing evaluation failed ('+type(error).__name__+'); inspect local artifacts, no credentials logged.',file=sys.stderr); sys.exit(1)
