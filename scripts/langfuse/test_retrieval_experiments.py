import unittest
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from unittest.mock import patch
import retrieval_experiments as experiments
import verify_retrieval_experiment as verification


class DatasetTests(unittest.TestCase):
    def test_verifier_accepts_skipped_rerank_and_batches_full_dataset_scores(self):
        row=dict(strategy='hybrid_rerank',retrieval_status='bm25_fallback',rerank_status='skipped')
        verification.verify_phases(row,[dict(name='embedding'),dict(name='retrieval')])
        scores=[dict(id=str(i)) for i in range(200)]
        batches=[]
        def get(path,params):
            batch=params['id'].split(',')
            batches.append(batch)
            return dict(data=[dict(id=i) for i in batch])
        with patch.object(experiments,'get',side_effect=get):
            actual=verification.fetch_scores(scores)
        self.assertEqual(200,len(actual))
        self.assertTrue(all(len(batch)<=100 for batch in batches))

    @unittest.skipUnless(shutil.which('pwsh'), 'PowerShell 7 is required')
    def test_loader_reloads_empty_environment_and_respects_nonempty_override(self):
        loader = Path(__file__).parent / 'run-with-langfuse.ps1'
        with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as tmp:
            config = Path(tmp) / 'env.local'
            config.write_text('LANGFUSE_CAPTURE_CONTENT=false\n', encoding='utf-8')
            check = Path(tmp) / 'check.py'
            check.write_text('import os\nassert os.environ["LANGFUSE_CAPTURE_CONTENT"] == os.environ["EXPECTED_CAPTURE"]\n')
            for current, expected in [('', 'false'), ('true', 'true')]:
                env = {**os.environ, 'LANGFUSE_CAPTURE_CONTENT': current, 'EXPECTED_CAPTURE': expected}
                completed = subprocess.run(['pwsh', '-NoProfile', '-File', str(loader), '-ConfigFile', str(config),
                                            '-Command', shutil.which('python'), '-CommandArguments', str(check)],
                                           capture_output=True, env=env)
                self.assertEqual(0, completed.returncode, completed.stderr.decode(errors='replace'))

    def test_cloud_identity_and_numeric_order_are_preserved(self):
        items = [dict(id='item-' + str(n), status='ACTIVE', input={'query': '问题'},
                      expectedOutput={'relevant_chunk_keys': ['a' * 32 + ':1']},
                      metadata={'sample_id': str(n), 'difficulty': 'easy'}) for n in (10, 2, 1)]
        result = experiments.freeze_dataset({'id': 'dataset-real', 'items': items}, 2, '2026-10-08T00:00:00Z')
        self.assertEqual(['1', '2'], [r['query_id'] for r in result])
        self.assertEqual('item-1', result[0]['langfuse_dataset_item_id'])
        self.assertEqual('dataset-real', result[0]['langfuse_dataset_id'])
        items[1]['metadata']['sample_id'] = '1'
        with self.assertRaises(ValueError):
            experiments.freeze_dataset({'id': 'dataset-real', 'items': items}, 2, 'version')

    def test_experiment_scores_attach_to_root_observation(self):
        row = dict(score_status='scored', run_id='r', sample_id='1', strategy='bm25',
                   trace_id='a' * 32, observation_id='b' * 16, hit_at_5=1, mrr_at_5=0.5,
                   retrieval_status='success', rerank_status='not_applicable')
        self.assertEqual('b' * 16, experiments.evaluation.score_payloads([row])[0]['observationId'])
