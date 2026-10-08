import copy
import hashlib
import io
from email.message import Message
from urllib.response import addinfourl
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import eval_retrieval as evaluation

KEY = 'a' * 32 + ':1'
OTHER = 'b' * 32 + ':2'

class JavaScoringTests(unittest.TestCase):
    def test_experiment_result_rejects_wrong_cloud_item_before_scoring(self):
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as tmp:
            dataset, results, rows = self.fixture(tmp)
            samples = json.loads(dataset.read_text())
            for s in samples:
                s.update(langfuse_dataset_id='dataset', langfuse_dataset_item_id='item-' + str(s['query_id']), langfuse_dataset_version='2026-10-08T00:00:00Z')
            dataset.write_text(json.dumps(samples))
            meta = json.loads((pathlib.Path(tmp) / 'metadata.json').read_text())
            meta['dataset_sha256'] = hashlib.sha256(dataset.read_bytes()).hexdigest()
            (pathlib.Path(tmp) / 'metadata.json').write_text(json.dumps(meta))
            for r in rows:
                r.update(observation_id=f"{int(r['trace_id'],16):016x}", experiment_id='exp-' + r['strategy'],
                         experiment_name='run-' + r['strategy'], dataset_id='dataset',
                         dataset_item_id='item-' + r['sample_id'], dataset_version='2026-10-08T00:00:00Z')
            self.write_rows(results,rows)
            scored, _ = evaluation.evaluate_java_results(results,dataset)
            self.assertEqual(rows[0]['observation_id'], evaluation.score_payloads(scored)[0]['observationId'])
            rows[0]['dataset_item_id']='wrong-item'
            self.write_rows(results,rows)
            with self.assertRaises(ValueError):
                evaluation.evaluate_java_results(results,dataset)

    def fixture(self, directory):
        root = pathlib.Path(directory)
        dataset = [{'query_id': i, 'query': 'q', 'relevant': [KEY], 'difficulty': 'easy'} for i in range(1, 5)]
        dataset_path = root / 'dataset.json'
        dataset_path.write_text(json.dumps(dataset), encoding='utf-8')
        rows = []
        for strategy in ('bm25', 'hybrid_rerank'):
            for i, keys in enumerate(([KEY], [OTHER, OTHER + '0', KEY], [OTHER + str(x) for x in range(5)] + [KEY], [OTHER]), 1):
                rows.append(dict(run_id='run', sample_id=str(i), strategy=strategy, trace_id=f'{len(rows)+1:032x}', retrieved=[dict(key=k, score=1.0) for k in keys], top_k=10, error=None, retrieval_status='success', rerank_status='not_applicable' if strategy=='bm25' else 'success'))
        result_path = root / 'results.jsonl'
        self.write_rows(result_path, rows)
        (root / 'metadata.json').write_text(json.dumps(dict(dataset_sha256=hashlib.sha256(dataset_path.read_bytes()).hexdigest(), config={'top_k': 10})), encoding='utf-8')
        return dataset_path, result_path, rows

    def write_rows(self, path, rows):
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows), encoding='utf-8')

    def test_rank_fixtures_preserve_original_order_and_labels(self):
        self.assertTrue(callable(getattr(evaluation, 'evaluate_java_results', None)), 'Java scoring mode is missing')
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as tmp:
            dataset, results, rows = self.fixture(tmp)
            scored, report = evaluation.evaluate_java_results(results, dataset)
            self.assertEqual([1, 1, 0, 0], [r['hit_at_5'] for r in scored[:4]])
            self.assertEqual([1, 1/3, 0, 0], [r['mrr_at_5'] for r in scored[:4]])
            self.assertEqual(rows[2]['retrieved'], scored[2]['retrieved'])
            self.assertEqual([KEY], scored[2]['relevant'])
            self.assertEqual(4, report['paired']['valid_count'])
            self.assertAlmostEqual(1/3, report['strategies']['bm25']['mrr_at_5'])

    def test_errors_excluded_from_means_and_paired_denominator(self):
        self.assertTrue(callable(getattr(evaluation, 'evaluate_java_results', None)), 'Java scoring mode is missing')
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as tmp:
            dataset, results, rows = self.fixture(tmp)
            rows[4].update(retrieval_status='error', error='SEARCH_ERROR', retrieved=[], rerank_status='unknown')
            self.write_rows(results, rows)
            scored, report = evaluation.evaluate_java_results(results, dataset)
            self.assertIsNone(scored[4]['hit_at_5'])
            self.assertEqual('retrieval_error', scored[4]['score_status'])
            self.assertEqual(3, report['strategies']['hybrid_rerank']['valid_count'])
            self.assertEqual(1, report['strategies']['hybrid_rerank']['error_count'])
            self.assertEqual(3, report['paired']['valid_count'])

    def test_rejects_invalid_contract_and_unknown_labels(self):
        self.assertTrue(callable(getattr(evaluation, 'evaluate_java_results', None)), 'Java scoring mode is missing')
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as tmp:
            dataset, results, rows = self.fixture(tmp)
            mutations = [rows[:-1], rows + [rows[0]], [dict(rows[0], sample_id='unknown')] + rows[1:], [dict(rows[0], trace_id='bad')] + rows[1:], [dict(rows[0], retrieval_status='unknown')] + rows[1:], [dict(rows[0], top_k=5)] + rows[1:]]
            for invalid in mutations:
                with self.subTest(invalid=invalid[0]):
                    self.write_rows(results, invalid)
                    with self.assertRaises(ValueError):
                        evaluation.evaluate_java_results(results, dataset)
            self.write_rows(results, rows)
            labels = json.loads(dataset.read_text())
            for bad in (None, [], ['unknown'], [None]):
                labels[0]['relevant'] = bad
                dataset.write_text(json.dumps(labels))
                with self.assertRaises(ValueError):
                    evaluation.evaluate_java_results(results, dataset)

    def test_offline_cli_without_site_packages_and_stable_score_ids(self):
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as tmp:
            dataset, results, rows = self.fixture(tmp)
            output = pathlib.Path(tmp) / 'output'
            command = [sys.executable, '-S', str(pathlib.Path(evaluation.__file__)), '--java-results', str(results), '--dataset', str(dataset), '--output-dir', str(output)]
            completed = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, completed.returncode, completed.stderr)
            payloads = json.loads((output / 'scores.json').read_text())
            self.assertEqual(16, len(payloads))
            self.assertEqual({'hit_at_5', 'mrr_at_5'}, {p['name'] for p in payloads})
            self.assertEqual('NUMERIC', payloads[0]['dataType'])
            subprocess.run(command, check=True, capture_output=True)
            self.assertEqual(payloads, json.loads((output / 'scores.json').read_text()))

    def test_degraded_strategies_are_scored_but_not_comparable(self):
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as tmp:
            dataset, results, rows = self.fixture(tmp)
            rows[4].update(retrieval_status='bm25_fallback', rerank_status='unknown')
            rows[5].update(rerank_status='skipped')
            rows[6].update(rerank_status='fallback')
            self.write_rows(results, rows)
            scored, report = evaluation.evaluate_java_results(results, dataset)
            self.assertEqual('bm25', scored[4]['actual_strategy'])
            self.assertEqual('hybrid_without_successful_rerank', scored[5]['actual_strategy'])
            self.assertEqual(4, report['strategies']['hybrid_rerank']['valid_count'])
            self.assertEqual(1, report['paired']['valid_count'])
            self.assertEqual({'success': 3, 'bm25_fallback': 1}, report['strategies']['hybrid_rerank']['retrieval_status_counts'])

    def test_upload_http_contract_uses_only_existing_traces(self):
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as tmp:
            dataset, results, rows = self.fixture(tmp)
            scored, _ = evaluation.evaluate_java_results(results, dataset)
            payloads = evaluation.score_payloads(scored)
            fake_response = unittest.mock.MagicMock()
            fake_response.__enter__.return_value.status = 201
            with patch.dict(os.environ, {'LANGFUSE_BASE_URL': 'https://jp.cloud.langfuse.com', 'LANGFUSE_PUBLIC_KEY': 'test-public', 'LANGFUSE_SECRET_KEY': 'test-secret'}), patch.object(evaluation.request.OpenerDirector, 'open', return_value=fake_response) as send:
                self.assertEqual(len(payloads), evaluation.upload_scores(payloads))
            sent = send.call_args_list[0]
            self.assertEqual('https://jp.cloud.langfuse.com/api/public/scores', sent.args[0].full_url)
            self.assertEqual('POST', sent.args[0].method)
            self.assertEqual(10, sent.kwargs['timeout'])
            body = json.loads(sent.args[0].data)
            self.assertEqual(rows[0]['trace_id'], body['traceId'])
            self.assertEqual('NUMERIC', body['dataType'])
            self.assertTrue(sent.args[0].headers['Authorization'].startswith('Basic '))

    def test_upload_refuses_redirect_before_forwarding_authorization(self):
        for redirect in ('https://other.example/scores', 'http://other.example/scores'):
            calls = []
            def fake_transport(handler, req):
                calls.append(req)
                headers = Message()
                if len(calls) == 1:
                    headers['Location'] = redirect
                    response = addinfourl(io.BytesIO(b''), headers, req.full_url, 302)
                    response.msg = 'Found'
                else:
                    response = addinfourl(io.BytesIO(b'{}'), headers, req.full_url, 201)
                    response.msg = 'Created'
                return response
            with self.subTest(redirect=redirect), patch.dict(os.environ, {'LANGFUSE_BASE_URL': 'https://jp.cloud.langfuse.com', 'LANGFUSE_PUBLIC_KEY': 'test-public', 'LANGFUSE_SECRET_KEY': 'test-secret'}), patch.object(evaluation.request.HTTPSHandler, 'https_open', fake_transport), patch.object(evaluation.request.HTTPHandler, 'http_open', fake_transport):
                with self.assertRaisesRegex(RuntimeError, '^score_upload_failed$'):
                    evaluation.upload_scores([{'traceId': '1' * 32, 'value': 1.0}])
                self.assertEqual(1, len(calls), 'redirect forwarded the authenticated request')

    def test_failed_export_or_snapshot_metadata_rejected_before_outputs(self):
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as tmp:
            dataset, results, rows = self.fixture(tmp)
            metadata_path = pathlib.Path(tmp) / 'metadata.json'
            original = json.loads(metadata_path.read_text())
            for field, bad_value in (('export_status', 'error'), ('preflight_status', 'error'), ('permission_unchanged', False), ('index_snapshot_unchanged', False)):
                metadata_path.write_text(json.dumps(dict(original, **{field: bad_value})))
                output = pathlib.Path(tmp) / field
                with self.subTest(field=field), patch.object(evaluation, 'upload_scores', return_value=16) as upload, patch('sys.stderr'):
                    self.assertEqual(2, evaluation.main(['--java-results', str(results), '--dataset', str(dataset), '--output-dir', str(output), '--upload-scores']))
                    self.assertFalse(output.exists())
                    upload.assert_not_called()
            metadata_path.write_text(json.dumps(dict(original, export_status='complete', preflight_status='success', permission_unchanged=True, index_snapshot_unchanged=True, cloud_flush_success=False)))
            scored, report = evaluation.evaluate_java_results(results, dataset)
            self.assertEqual(8, len(scored))
            self.assertFalse(report['metadata']['cloud_flush_success'])

    def test_safe_public_fixture_file_id_preserves_rank_before_labeled_hit(self):
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as tmp:
            dataset, results, rows = self.fixture(tmp)
            rows[0]['retrieved'] = [{'key': 'route-eval-ttft-public:1', 'score': 2.0}, {'key': KEY, 'score': 1.0}]
            self.write_rows(results, rows)
            scored, _ = evaluation.evaluate_java_results(results, dataset)
            self.assertEqual(1, scored[0]['hit_at_5'])
            self.assertEqual(0.5, scored[0]['mrr_at_5'])
            self.assertEqual(rows[0]['retrieved'], scored[0]['retrieved'])
            for bad in ('', ':1', 'public:extra:1', 'public:-1', 'a' * 129 + ':1', None):
                rows[0]['retrieved'][0]['key'] = bad
                self.write_rows(results, rows)
                with self.subTest(key=bad), self.assertRaises(ValueError):
                    evaluation.evaluate_java_results(results, dataset)

    def test_score_upload_retries_429_same_payload_with_bounded_delay(self):
        payload = {'id': 'stable-id', 'traceId': '1' * 32, 'value': 1.0}
        response = unittest.mock.MagicMock()
        response.__enter__.return_value.status = 201
        for retry_after, delay in (('2', 2.0), ('1000', 60.0), ('invalid', 30.0), (None, 30.0)):
            headers = Message()
            if retry_after is not None:
                headers['Retry-After'] = retry_after
            fp = io.BytesIO(b'private error')
            error = evaluation.HTTPError('https://jp.cloud.langfuse.com/api/public/scores', 429, 'rate limit', headers, fp)
            with self.subTest(retry_after=retry_after), patch.dict(os.environ, {'LANGFUSE_BASE_URL': 'https://jp.cloud.langfuse.com', 'LANGFUSE_PUBLIC_KEY': 'test-public', 'LANGFUSE_SECRET_KEY': 'test-secret'}), patch.object(evaluation.request.OpenerDirector, 'open', side_effect=[error, response]) as send, patch('time.sleep') as sleep:
                self.assertEqual(1, evaluation.upload_scores([payload]))
                self.assertEqual(2, send.call_count)
                self.assertEqual(send.call_args_list[0].args[0].data, send.call_args_list[1].args[0].data)
                sleep.assert_called_once_with(delay)
                self.assertTrue(fp.closed)

    def test_score_upload_retry_budget_and_non429_errors(self):
        for code, attempts in ((429, 3), (401, 1), (302, 1)):
            errors = [evaluation.HTTPError('https://jp.cloud.langfuse.com/api/public/scores', code, 'private', Message(), io.BytesIO()) for _ in range(attempts)]
            with self.subTest(code=code), patch.dict(os.environ, {'LANGFUSE_BASE_URL': 'https://jp.cloud.langfuse.com', 'LANGFUSE_PUBLIC_KEY': 'test-public', 'LANGFUSE_SECRET_KEY': 'test-secret'}), patch.object(evaluation.request.OpenerDirector, 'open', side_effect=errors) as send, patch('time.sleep') as sleep:
                with self.assertRaisesRegex(RuntimeError, '^score_upload_failed$'):
                    evaluation.upload_scores([{'id': 'stable'}])
                self.assertEqual(attempts, send.call_count)
                self.assertEqual(attempts - 1, sleep.call_count)
                self.assertTrue(all(error.fp.closed for error in errors))

    def test_optional_metadata_run_and_top_k_must_match(self):
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as tmp:
            dataset, results, rows = self.fixture(tmp)
            metadata_path = pathlib.Path(tmp) / 'metadata.json'
            original = json.loads(metadata_path.read_text())
            invalid = [dict(original, run_id='another-run'), dict(original, config={'top_k': 5}), dict(original, config={'top_k': 10, 'metric_top_k': 10})]
            for metadata in invalid:
                metadata_path.write_text(json.dumps(metadata))
                with self.subTest(metadata=metadata), self.assertRaises(ValueError):
                    evaluation.evaluate_java_results(results, dataset)

    def test_metadata_checksum_and_nonfinite_scores_rejected(self):
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as tmp:
            dataset, results, rows = self.fixture(tmp)
            rows[0]['retrieved'][0]['score'] = float('nan')
            self.write_rows(results, rows)
            with self.assertRaises(ValueError):
                evaluation.evaluate_java_results(results, dataset)
            dataset.write_text(dataset.read_text() + ' ')
            with self.assertRaises(ValueError):
                evaluation.evaluate_java_results(results, dataset)

    def test_failed_upload_preserves_local_results_without_sensitive_exception(self):
        self.assertTrue(callable(getattr(evaluation, 'main', None)), 'Java scoring CLI is missing')
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as tmp:
            dataset, results, rows = self.fixture(tmp)
            output = pathlib.Path(tmp) / 'output'
            with patch.object(evaluation, 'upload_scores', side_effect=RuntimeError('secret credentials')), patch('sys.stderr') as stderr:
                code = evaluation.main(['--java-results', str(results), '--dataset', str(dataset), '--output-dir', str(output), '--upload-scores'])
            self.assertEqual(1, code)
            self.assertTrue((output / 'report.json').exists())
            self.assertTrue((output / 'scores.json').exists())
            self.assertNotIn('secret credentials', str(stderr.write.call_args_list))

if __name__ == '__main__':
    unittest.main()

