"""Download a frozen Cloud dataset; Java runs the actual retrieval experiments."""
import argparse
import base64
import datetime
import json
import os
from pathlib import Path
import sys
from urllib import request, parse
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'docs' / 'interview'))
import eval_retrieval as evaluation


def get(path, params=None):
    base = os.environ.get('LANGFUSE_BASE_URL', '').rstrip('/')
    url = parse.urlsplit(base)
    public, secret = os.environ.get('LANGFUSE_PUBLIC_KEY'), os.environ.get('LANGFUSE_SECRET_KEY')
    if url.scheme != 'https' or not url.hostname or url.username or url.password or url.query or url.fragment or not public or not secret:
        raise ValueError('invalid_langfuse_configuration')
    auth = base64.b64encode(f'{public}:{secret}'.encode()).decode()
    req = request.Request(base + path + ('?' + parse.urlencode(params) if params else ''),
                          headers={'Authorization': 'Basic ' + auth})
    try:
        with request.build_opener(evaluation._NoScoreRedirect()).open(req, timeout=20) as response:
            return json.load(response)
    except Exception:
        raise RuntimeError('langfuse_read_failed') from None


def freeze_dataset(dataset, limit, version):
    if not isinstance(dataset.get('id'), str) or not dataset['id'] or limit < 1:
        raise ValueError('invalid_dataset')
    rows, ids, samples = [], set(), set()
    for item in dataset['items']:
        if item.get('status') != 'ACTIVE':
            continue
        sample = str(item['metadata']['sample_id'])
        key = item['id']
        query = item['input']['query']
        labels = item['expectedOutput']['relevant_chunk_keys']
        difficulty = item['metadata']['difficulty']
        if (not sample.isdigit() or str(int(sample)) != sample or sample in samples or key in ids
                or not isinstance(key, str) or not key or not isinstance(query, str) or not query.strip()
                or not isinstance(labels, list) or not labels or len(set(labels)) != len(labels)
                or not all(isinstance(k, str) and evaluation.KEY_PATTERN.fullmatch(k) for k in labels)
                or difficulty not in ('easy', 'medium', 'hard')):
            raise ValueError('invalid_dataset_item')
        ids.add(key)
        samples.add(sample)
        rows.append(dict(query_id=sample, query=query, relevant=labels, difficulty=difficulty,
                         langfuse_dataset_id=dataset['id'], langfuse_dataset_item_id=key,
                         langfuse_dataset_version=version))
    if len(rows) < limit:
        raise ValueError('insufficient_active_samples')
    return sorted(rows, key=lambda r: int(r['query_id']))[:limit]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset-name', default='retrieval-eval-50')
    parser.add_argument('--limit', type=int, default=10)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    if (args.output_dir / 'dataset.json').exists():
        raise ValueError('dataset_snapshot_already_exists')
    version = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')
    dataset = get('/api/public/v2/datasets/' + parse.quote(args.dataset_name, safe=''), {'version': version})
    dataset['items'] = []
    page = 1
    while True:
        batch = get('/api/public/dataset-items', {'datasetName': args.dataset_name, 'version': version, 'limit': 100, 'page': page})
        dataset['items'].extend(batch['data'])
        if page >= batch['meta']['totalPages']:
            break
        page += 1
    rows = freeze_dataset(dataset, args.limit, version)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / 'dataset.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding='utf-8')
    manifest = dict(dataset_id=dataset['id'], dataset_name=args.dataset_name, version=version,
                    sample_count=len(rows), selection='ascending numeric sample_id',
                    fetched_active_count=sum(i.get('status') == 'ACTIVE' for i in dataset['items']))
    (args.output_dir / 'dataset-manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(json.dumps(manifest))


if __name__ == '__main__':
    try:
        main()
    except Exception:
        print('Dataset download failed; configuration and response bodies are not logged.', file=sys.stderr)
        sys.exit(1)
