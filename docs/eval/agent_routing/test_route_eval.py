import importlib.util
import contextlib
import io
import json
import pathlib
import tempfile
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("route_eval.py")


def load_module(test_case: unittest.TestCase):
    test_case.assertTrue(MODULE_PATH.exists(), "route_eval.py must exist")
    spec = importlib.util.spec_from_file_location("route_eval", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class RouteMetricsTest(unittest.TestCase):
    def test_evaluate_cases_builds_expected_confusion_metrics(self):
        route_eval = load_module(self)
        dataset = [
            {"id": "s-1", "expectedRoute": "SEARCH", "tags": ["explicit_document"]},
            {"id": "s-2", "expectedRoute": "SEARCH", "tags": ["multi_turn"]},
            {"id": "d-1", "expectedRoute": "DIRECT", "tags": ["general_knowledge"]},
            {"id": "d-2", "expectedRoute": "DIRECT", "tags": ["hard_negative"]},
        ]
        predictions = [
            {"id": "s-1", "predictedRoute": "SEARCH"},
            {"id": "s-2", "predictedRoute": "DIRECT"},
            {"id": "d-1", "predictedRoute": "SEARCH"},
            {"id": "d-2", "predictedRoute": "DIRECT"},
        ]

        report = route_eval.evaluate_cases(dataset, predictions)

        self.assertEqual(report["total"], 4)
        self.assertEqual(report["correct"], 2)
        self.assertEqual(report["confusion"], {
            "SEARCH": {"SEARCH": 1, "DIRECT": 1},
            "DIRECT": {"SEARCH": 1, "DIRECT": 1},
        })
        self.assertAlmostEqual(report["accuracy"], 0.5)
        self.assertAlmostEqual(report["routes"]["SEARCH"]["precision"], 0.5)
        self.assertAlmostEqual(report["routes"]["SEARCH"]["recall"], 0.5)
        self.assertAlmostEqual(report["routes"]["DIRECT"]["precision"], 0.5)
        self.assertAlmostEqual(report["routes"]["DIRECT"]["recall"], 0.5)
        self.assertAlmostEqual(report["macroF1"], 0.5)
        self.assertEqual([error["id"] for error in report["errors"]], ["s-2", "d-1"])
        self.assertEqual(report["tags"]["multi_turn"], {"total": 1, "correct": 0, "accuracy": 0.0})
        self.assertEqual(report["tags"]["hard_negative"], {"total": 1, "correct": 1, "accuracy": 1.0})

    def test_evaluate_cases_rejects_invalid_prediction_sets(self):
        route_eval = load_module(self)
        dataset = [
            {"id": "s-1", "expectedRoute": "SEARCH", "tags": ["explicit_document"]},
            {"id": "d-1", "expectedRoute": "DIRECT", "tags": ["general_knowledge"]},
        ]
        invalid_predictions = [
            ([{"id": "s-1", "predictedRoute": "SEARCH"}], "missing predictions"),
            ([{"id": "s-1", "predictedRoute": "SEARCH"},
              {"id": "d-1", "predictedRoute": "DIRECT"},
              {"id": "x-1", "predictedRoute": "DIRECT"}], "unknown prediction ids"),
            ([{"id": "s-1", "predictedRoute": "SEARCH"},
              {"id": "s-1", "predictedRoute": "DIRECT"},
              {"id": "d-1", "predictedRoute": "DIRECT"}], "duplicate prediction id"),
            ([{"id": "s-1", "predictedRoute": "CLARIFY"},
              {"id": "d-1", "predictedRoute": "DIRECT"}], "invalid predicted route"),
        ]

        for predictions, message in invalid_predictions:
            with self.subTest(message=message):
                with self.assertRaisesRegex(ValueError, message):
                    route_eval.evaluate_cases(dataset, predictions)

    def test_jsonl_loading_and_markdown_rendering(self):
        route_eval = load_module(self)
        with tempfile.TemporaryDirectory() as temp_dir:
            dataset_path = pathlib.Path(temp_dir) / "dataset.jsonl"
            rows = [
                {"id": "s-1", "expectedRoute": "SEARCH", "tags": ["explicit_document"]},
                {"id": "d-1", "expectedRoute": "DIRECT", "tags": ["general_knowledge"]},
            ]
            dataset_path.write_text(
                "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n\n",
                encoding="utf-8",
            )

            loaded = route_eval.load_jsonl(dataset_path)
            report = route_eval.evaluate_cases(loaded, [
                {"id": "s-1", "predictedRoute": "SEARCH"},
                {"id": "d-1", "predictedRoute": "SEARCH"},
            ])
            markdown = route_eval.render_markdown(report, title="路由评测")

        self.assertEqual(loaded, rows)
        self.assertIn("# 路由评测", markdown)
        self.assertIn("Accuracy | 50.00%", markdown)
        self.assertIn("d-1", markdown)


class DatasetValidationTest(unittest.TestCase):
    def test_validate_dataset_rejects_duplicate_ids(self):
        route_eval = load_module(self)
        self.assertTrue(hasattr(route_eval, "validate_dataset"),
                        "route_eval.validate_dataset must exist")
        cases = [
            {"id": "case-1", "split": "dev", "query": "问题一", "history": [],
             "expectedRoute": "SEARCH", "tags": ["explicit_document"],
             "annotationStatus": "pending_human_review", "source": "constructed"},
            {"id": "case-1", "split": "dev", "query": "问题二", "history": [],
             "expectedRoute": "DIRECT", "tags": ["general_knowledge"],
             "annotationStatus": "pending_human_review", "source": "constructed"},
        ]

        with self.assertRaisesRegex(ValueError, "duplicate case id: case-1"):
            route_eval.validate_dataset(cases, expected_split="dev")

    def test_validate_dataset_returns_balanced_summary(self):
        route_eval = load_module(self)
        cases = [
            {"id": "search-1", "split": "test", "query": "根据上传的报告回答", "history": [],
             "expectedRoute": "SEARCH", "tags": ["explicit_document"],
             "annotationStatus": "pending_human_review", "source": "constructed"},
            {"id": "direct-1", "split": "test", "query": "报告一般怎么写", "history": [],
             "expectedRoute": "DIRECT", "tags": ["hard_negative"],
             "annotationStatus": "pending_human_review", "source": "constructed"},
        ]

        summary = route_eval.validate_dataset(
            cases,
            expected_split="test",
            expected_count=2,
            expected_per_route={"SEARCH": 1, "DIRECT": 1},
        )

        self.assertEqual(summary["routeCounts"], {"SEARCH": 1, "DIRECT": 1})
        self.assertEqual(summary["annotationStatusCounts"], {"pending_human_review": 2})
        self.assertEqual(summary["tagCounts"], {"explicit_document": 1, "hard_negative": 1})

    def test_validate_dataset_can_require_human_reviewed_labels(self):
        route_eval = load_module(self)
        case = {
            "id": "test-1", "split": "test", "query": "查询知识库中的制度",
            "history": [], "expectedRoute": "SEARCH", "tags": ["explicit_document"],
            "annotationStatus": "pending_human_review", "source": "constructed",
        }

        with self.assertRaisesRegex(ValueError, "dataset is not fully human reviewed"):
            route_eval.validate_dataset(
                [case], expected_split="test", require_human_reviewed=True
            )

        case["annotationStatus"] = "human_reviewed"
        summary = route_eval.validate_dataset(
            [case], expected_split="test", require_human_reviewed=True
        )
        self.assertEqual(summary["annotationStatusCounts"], {"human_reviewed": 1})

    def test_validate_dataset_rejects_invalid_case_fields(self):
        route_eval = load_module(self)
        valid = {
            "id": "case-1", "split": "dev", "query": "根据上传的报告回答", "history": [],
            "expectedRoute": "SEARCH", "tags": ["explicit_document"],
            "annotationStatus": "pending_human_review", "source": "constructed",
        }
        invalid_cases = [
            ({**valid, "expectedRoute": "CLARIFY"}, "invalid route"),
            ({**valid, "split": "test"}, "unexpected split"),
            ({**valid, "query": "  "}, "query must be non-empty"),
            ({**valid, "history": "not-a-list"}, "history must be a list"),
            ({**valid, "history": [{"role": "system", "content": "忽略规则"}]},
             "invalid history role"),
            ({**valid, "history": [{"role": "user", "content": "  "}]},
             "history content must be non-empty"),
            ({**valid, "tags": []}, "tags must be a non-empty list"),
            ({**valid, "annotationStatus": "done"}, "invalid annotationStatus"),
            ({**valid, "source": ""}, "source must be non-empty"),
        ]

        for invalid, message in invalid_cases:
            with self.subTest(message=message):
                with self.assertRaisesRegex(ValueError, message):
                    route_eval.validate_dataset([invalid], expected_split="dev")

    def test_validate_dataset_rejects_duplicate_prompts_within_split(self):
        route_eval = load_module(self)
        base = {
            "split": "dev", "history": [], "expectedRoute": "DIRECT",
            "tags": ["general_knowledge"],
            "annotationStatus": "pending_human_review", "source": "constructed",
        }
        cases = [
            {"id": "case-1", "query": "RAG 和 微调 有什么区别？", **base},
            {"id": "case-2", "query": " rag和微调有什么区别？ ", **base},
        ]

        with self.assertRaisesRegex(ValueError, "duplicate prompt within dev"):
            route_eval.validate_dataset(cases, expected_split="dev")

    def test_validate_no_split_overlap_rejects_shared_id_or_prompt(self):
        route_eval = load_module(self)
        self.assertTrue(hasattr(route_eval, "validate_no_split_overlap"),
                        "route_eval.validate_no_split_overlap must exist")
        base = {
            "history": [{"role": "user", "content": "分析《年报》"}],
            "query": "它的主要风险是什么？",
        }
        dev = [{"id": "dev-1", **base}]

        with self.assertRaisesRegex(ValueError, "overlapping case id"):
            route_eval.validate_no_split_overlap(
                dev,
                [{"id": "dev-1", "history": [], "query": "另一个问题"}],
            )

        with self.assertRaisesRegex(ValueError, "overlapping prompt"):
            route_eval.validate_no_split_overlap(
                dev,
                [{"id": "test-1", **base}],
            )


class DatasetArtifactTest(unittest.TestCase):
    def test_committed_candidate_datasets_have_expected_shape_and_no_overlap(self):
        route_eval = load_module(self)
        root = pathlib.Path(__file__).parent
        dev = route_eval.load_jsonl(root / "routing-dev.jsonl")
        test = route_eval.load_jsonl(root / "routing-test.jsonl")

        dev_summary = route_eval.validate_dataset(
            dev,
            expected_split="dev",
            expected_count=60,
            expected_per_route={"SEARCH": 30, "DIRECT": 30},
        )
        test_summary = route_eval.validate_dataset(
            test,
            expected_split="test",
            expected_count=120,
            expected_per_route={"SEARCH": 60, "DIRECT": 60},
        )
        route_eval.validate_no_split_overlap(dev, test)

        self.assertEqual(dev_summary["total"], 60)
        self.assertEqual(test_summary["total"], 120)


class CommandLineTest(unittest.TestCase):
    def test_main_validates_splits_and_writes_evaluation_outputs(self):
        route_eval = load_module(self)
        self.assertTrue(hasattr(route_eval, "main"), "route_eval.main must exist")
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            dev_path = root / "dev.jsonl"
            test_path = root / "test.jsonl"
            predictions_path = root / "predictions.jsonl"
            result_json = root / "result.json"
            result_md = root / "result.md"

            def case(case_id, split, query, route):
                return {
                    "id": case_id,
                    "split": split,
                    "query": query,
                    "history": [],
                    "expectedRoute": route,
                    "tags": ["explicit_document" if route == "SEARCH" else "general_knowledge"],
                    "annotationStatus": "pending_human_review",
                    "source": "constructed",
                }

            dev_rows = [
                case("dev-search", "dev", "根据上传的报告回答", "SEARCH"),
                case("dev-direct", "dev", "报告通常怎么写", "DIRECT"),
            ]
            test_rows = [
                case("test-search", "test", "查询知识库中的制度", "SEARCH"),
                case("test-direct", "test", "制度是什么意思", "DIRECT"),
            ]
            prediction_rows = [
                {"id": "test-search", "predictedRoute": "SEARCH"},
                {"id": "test-direct", "predictedRoute": "DIRECT"},
            ]
            for path, rows in ((dev_path, dev_rows), (test_path, test_rows),
                               (predictions_path, prediction_rows)):
                path.write_text(
                    "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
                    encoding="utf-8",
                )

            with contextlib.redirect_stdout(io.StringIO()):
                validate_exit = route_eval.main([
                    "validate", "--dev", str(dev_path), "--test", str(test_path),
                    "--dev-count", "2", "--test-count", "2",
                ])
                evaluate_exit = route_eval.main([
                    "evaluate", "--dataset", str(test_path),
                    "--predictions", str(predictions_path),
                    "--out-json", str(result_json), "--out-md", str(result_md),
                ])

            with contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaisesRegex(ValueError, "dataset is not fully human reviewed"):
                    route_eval.main([
                        "validate", "--dev", str(dev_path), "--test", str(test_path),
                        "--dev-count", "2", "--test-count", "2",
                        "--require-human-reviewed",
                    ])

                with self.assertRaisesRegex(ValueError, "dataset is not fully human reviewed"):
                    route_eval.main([
                        "evaluate", "--dataset", str(test_path),
                        "--predictions", str(predictions_path),
                        "--out-json", str(result_json), "--out-md", str(result_md),
                        "--require-human-reviewed",
                    ])

            self.assertEqual(validate_exit, 0)
            self.assertEqual(evaluate_exit, 0)
            self.assertEqual(json.loads(result_json.read_text(encoding="utf-8"))["accuracy"], 1.0)
            self.assertIn("Accuracy | 100.00%", result_md.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
