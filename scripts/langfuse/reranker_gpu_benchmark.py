"""Local CPU/GPU paired reranking benchmark; output never contains query or document text."""
import argparse
import datetime
import hashlib
import json
import math
from pathlib import Path
import statistics
import time
from urllib import request
from urllib.parse import urlsplit


def validate_scores(rows,count):
    if not isinstance(rows,list) or len(rows)!=count: raise ValueError('missing_candidate_scores')
    indices=[]
    for row in rows:
        index=row.get('index'); score=row.get('score')
        if isinstance(index,bool) or not isinstance(index,int) or not 0<=index<count: raise ValueError('invalid_candidate_index')
        if isinstance(score,bool) or not isinstance(score,(float,int)) or not math.isfinite(score): raise ValueError('invalid_score')
        indices.append(index)
    if set(indices)!=set(range(count)): raise ValueError('duplicate_candidate_index')
    return sorted(rows,key=lambda r:(-r['score'],r['index']))


def compare_rankings(sample,cpu,gpu):
    keys=sample['keys']; labels=set(sample['relevant']); result={}
    cpu=validate_scores(cpu,len(keys)); gpu=validate_scores(gpu,len(keys))
    for name,rows in [('cpu',cpu),('gpu',gpu)]:
        ordered=[keys[r['index']] for r in rows]
        result[name]=dict(ordered_keys=ordered,hit_at_5=int(any(k in labels for k in ordered[:5])),
            mrr_at_5=next((1/(i+1) for i,k in enumerate(ordered[:5]) if k in labels),0))
    result['exact_order_equal']=result['cpu']['ordered_keys']==result['gpu']['ordered_keys']
    result['top5_order_equal']=result['cpu']['ordered_keys'][:5]==result['gpu']['ordered_keys'][:5]
    cpu_by_index={r['index']:r['score'] for r in cpu}
    result['max_absolute_score_difference']=max(abs(cpu_by_index[r['index']]-r['score']) for r in gpu)
    return result


def endpoint(value):
    url=urlsplit(value)
    if url.scheme!='http' or url.hostname not in ('localhost','127.0.0.1') or url.username or url.password or url.path not in ('','/') or url.query or url.fragment:
        raise ValueError('benchmark_requires_local_endpoint')
    return value.rstrip('/')


def post(base,sample):
    payload=dict(query=sample['query'],texts=sample['texts'],top_n=len(sample['texts']))
    req=request.Request(base+'/rerank',data=json.dumps(payload,ensure_ascii=False).encode(),headers={'Content-Type':'application/json'})
    started=time.perf_counter()
    # No system proxy or redirects: private benchmark inputs stay on local loopback.
    class NoRedirect(request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs): raise ValueError('redirect_rejected')
    with request.build_opener(request.ProxyHandler({}),NoRedirect()).open(req,timeout=90) as response:
        rows=json.load(response)
    elapsed=time.perf_counter()-started
    return validate_scores(rows,len(sample['texts'])),elapsed


def run(args):
    cpu=endpoint(args.cpu_url); gpu=endpoint(args.gpu_url)
    if args.repeats<2: raise ValueError('at_least_two_repeats_required')
    samples=json.loads(args.inputs.read_text(encoding='utf-8'))
    if not isinstance(samples,list) or not samples: raise ValueError('empty_samples')
    for sample in samples:
        if not sample['query'] or not sample['keys'] or len(sample['keys'])!=len(sample['texts']) or len(set(sample['keys']))!=len(sample['keys']): raise ValueError('invalid_sample')
    args.output_dir.mkdir(parents=True,exist_ok=False)
    report=dict(created_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),input_sha256=hashlib.sha256(args.inputs.read_bytes()).hexdigest(),
        cpu_url=cpu,gpu_url=gpu,repeats=args.repeats,sample_count=len(samples),candidate_counts=[len(s['keys']) for s in samples],measurements=[],
        scope='fixed candidate reranking only, warmed serial paired calls; CPU float32 vs GPU float16 serving configurations')
    try:
        # Warm each service once; exclude startup and warmup from timings.
        post(cpu,samples[0]); post(gpu,samples[0])
        for sample_index,sample in enumerate(samples):
            for repeat in range(args.repeats):
                pair={}; times={}
                order=[('cpu',cpu),('gpu',gpu)] if (sample_index+repeat)%2==0 else [('gpu',gpu),('cpu',cpu)]
                for name,base in order:
                    pair[name],times[name]=post(base,sample)
                row=dict(sample_id=sample['sample_id'],repeat=repeat+1,execution_order=[name for name,_ in order],seconds=times,
                    input_sha256=hashlib.sha256(json.dumps(sample,ensure_ascii=False,sort_keys=True).encode()).hexdigest(),
                    comparison=compare_rankings(sample,pair['cpu'],pair['gpu']))
                report['measurements'].append(row)
                print(json.dumps(dict(sample_id=row['sample_id'],repeat=row['repeat'],seconds=times,exact_order_equal=row['comparison']['exact_order_equal'])),flush=True)
                (args.output_dir/'measurements.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        rows=report['measurements']
        cpu_median=statistics.median(r['seconds']['cpu'] for r in rows); gpu_median=statistics.median(r['seconds']['gpu'] for r in rows)
        summary=dict(sample_count=len(samples),paired_runs=len(rows),cpu_median_seconds=cpu_median,gpu_median_seconds=gpu_median,
            median_speedup=cpu_median/gpu_median,exact_order_equal_count=sum(r['comparison']['exact_order_equal'] for r in rows),
            top5_order_equal_count=sum(r['comparison']['top5_order_equal'] for r in rows),
            hit_mrr_equal_count=sum(all(r['comparison']['cpu'][metric]==r['comparison']['gpu'][metric] for metric in ['hit_at_5','mrr_at_5']) for r in rows),
            cpu_over_java_10s_budget=sum(r['seconds']['cpu']>10 for r in rows),gpu_over_java_10s_budget=sum(r['seconds']['gpu']>10 for r in rows))
        (args.output_dir/'summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
        print(json.dumps(summary),flush=True)
    except Exception:
        (args.output_dir/'failure.json').write_text(json.dumps(dict(status='benchmark_failed',completed_pairs=len(report['measurements']))),encoding='utf-8')
        raise


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs',type=Path,required=True); parser.add_argument('--output-dir',type=Path,required=True)
    parser.add_argument('--cpu-url',default='http://127.0.0.1:8082'); parser.add_argument('--gpu-url',default='http://127.0.0.1:8083'); parser.add_argument('--repeats',type=int,default=3)
    try: run(parser.parse_args())
    except Exception as error: raise SystemExit('Benchmark failed: '+type(error).__name__+' (no private content logged)')
