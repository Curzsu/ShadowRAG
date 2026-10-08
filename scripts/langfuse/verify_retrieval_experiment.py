"""Read back actual experiments, dataset associations, ordered outputs and scores."""
import argparse
import datetime
import json
from pathlib import Path
import sys
import retrieval_experiments as cloud


def decoded(value):
    return json.loads(value) if isinstance(value, str) else value


def verify_phases(row, children):
    names = {o['name'] for o in children}
    assert names.issubset({'embedding', 'retrieval', 'rerank'})
    if row['retrieval_status'] == 'success':
        required = {'retrieval'} if row['strategy'] == 'bm25' else {'embedding', 'retrieval'}
        assert required.issubset(names)
    elif row['retrieval_status'] == 'bm25_fallback':
        assert 'retrieval' in names
    if row['strategy'] == 'hybrid_rerank' and row['rerank_status'] in ('success', 'fallback'):
        assert 'rerank' in names


def fetch_scores(scores):
    actual = []
    for offset in range(0, len(scores), 100):
        ids = ','.join(s['id'] for s in scores[offset:offset + 100])
        actual.extend(cloud.get('/api/public/v3/scores', {'id': ids, 'fields': 'subject', 'limit': 100})['data'])
    return actual


def verify(root):
    manifest = json.loads((root / 'dataset-manifest.json').read_text(encoding='utf-8'))
    rows = json.loads((root / 'scored_results.json').read_text(encoding='utf-8'))
    scores = json.loads((root / 'scores.json').read_text(encoding='utf-8'))
    from_time = manifest['version']
    to_time = datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')
    evidence = dict(run_id=rows[0]['run_id'], verified=False, experiments=[], score_count=0)
    for strategy in cloud.evaluation.STRATEGIES:
        expected = [r for r in rows if r['strategy'] == strategy]
        first = expected[0]
        time_range = dict(fromStartTime=from_time, toStartTime=to_time)
        experiments = cloud.get('/api/public/experiments', {**time_range, 'id': first['experiment_id'], 'datasetId': manifest['dataset_id'], 'limit': 100})
        assert len(experiments['data']) == 1
        experiment = experiments['data'][0]
        assert experiment['name'] == first['experiment_name'] and experiment['itemCount'] == len(expected)
        items = cloud.get('/api/public/experiment-items', {**time_range, 'experimentId': first['experiment_id'], 'fields': 'io,dataset,scores', 'limit': 100})
        assert not items.get('meta', {}).get('nextCursor'), 'unexpected pagination'
        actual = {i['experimentItemId']: i for i in items['data']}
        assert set(actual) == {r['dataset_item_id'] for r in expected}
        for row in expected:
            item = actual[row['dataset_item_id']]
            assert item['traceId'] == row['trace_id'] and item['id'] == row['observation_id']
            assert item['experimentDatasetId'] == row['dataset_id']
            actual_version = datetime.datetime.fromisoformat(item['experimentItemVersion'].replace('Z', '+00:00'))
            expected_version = datetime.datetime.fromisoformat(row['dataset_version'].replace('Z', '+00:00'))
            assert abs((actual_version - expected_version).total_seconds()) < 0.001
            assert decoded(item['input']) == {'query': row['query']}
            assert decoded(item['expectedOutput']) == {'relevant_chunk_keys': row['relevant']}
            output = decoded(item['output'])
            assert output['retrieved'] == row['retrieved']
            assert output['rerank_status'] == row['rerank_status'] and output['retrieval_status'] == row['retrieval_status']
            expected_scores = [s for s in scores if s['traceId'] == row['trace_id']]
            item_scores = {s['id']: s for s in item.get('scores', [])}
            assert {s['id'] for s in expected_scores}.issubset(item_scores)
            for score in expected_scores:
                actual_score = item_scores[score['id']]
                assert actual_score['name'] == score['name'] and abs(actual_score['value'] - score['value']) < 1e-9
        # Root and all search phases must share the experiment identity. No chunk text in child I/O.
        first_tree = cloud.get('/api/public/v2/observations', {**time_range, 'traceId': first['trace_id'], 'fields': 'core,basic,io', 'limit': 100})['data']
        children = [o for o in first_tree if o.get('parentObservationId') == first['observation_id']]
        verify_phases(first, children)
        assert all(o.get('input') is None and o.get('output') is None for o in children)
        evidence['experiments'].append(dict(id=experiment['id'], name=experiment['name'], item_count=len(actual), sample_trace_id=first['trace_id']))
    actual_scores = fetch_scores(scores)
    by_id = {p['id']: p for p in actual_scores}
    assert set(by_id) == {p['id'] for p in scores}
    for expected in scores:
        actual = by_id[expected['id']]
        assert actual['name'] == expected['name'] and abs(actual['value'] - expected['value']) < 1e-9
        assert actual['dataType'] == 'NUMERIC'
        assert actual['subject'] == dict(kind='observation', id=expected['observationId'], traceId=expected['traceId'])
    evidence.update(verified=True, score_count=len(scores))
    (root / 'cloud-verification.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
    return evidence


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output_dir', type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.output_dir)))
    except Exception as error:
        print('Cloud verification failed (' + type(error).__name__ + '); no response bodies or credentials logged.', file=sys.stderr)
        sys.exit(1)
