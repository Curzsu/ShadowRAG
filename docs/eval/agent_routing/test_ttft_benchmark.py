import importlib.util
import json
from pathlib import Path
import unittest


MODULE_PATH = Path(__file__).with_name("ttft_benchmark.py")
SPEC = importlib.util.spec_from_file_location("ttft_benchmark", MODULE_PATH)
ttft = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(ttft)


class TtftBenchmarkTests(unittest.TestCase):
    def test_percentile_interpolates_and_rejects_empty_input(self):
        self.assertEqual(ttft.percentile([100, 200, 300, 400], 50), 250)
        self.assertEqual(ttft.percentile([100, 200, 300, 400], 95), 385)
        with self.assertRaises(ValueError):
            ttft.percentile([], 50)

    def test_parse_stream_records_first_nonempty_content_and_route(self):
        lines = [
            b'data: {"choices":[{"delta":{"role":"assistant","content":""}}]}\n',
            b'data: {"choices":[{"delta":{"content":"hello"}}]}\n',
            b'data: {"choices":[{"delta":{"content":" world"}}]}\n',
            b'data: [DONE]\n',
        ]
        clock_values = iter([10.250])

        result = ttft.parse_stream_timing(
            lines, started_seconds=10.0, clock=lambda: next(clock_values)
        )

        self.assertEqual(result["predictedRoute"], "DIRECT")
        self.assertEqual(result["firstContentMs"], 250)
        self.assertEqual(result["contentChars"], 11)

    def test_parse_stream_can_stop_immediately_after_first_content(self):
        lines = [
            b'data: {"choices":[{"delta":{"content":"hello"}}]}\n',
            b'data: {"choices":[{"delta":{"content":" world"}}]}\n',
        ]
        result = ttft.parse_stream_timing(
            lines,
            started_seconds=10.0,
            clock=lambda: 10.125,
            stop_after_first_content=True,
        )
        self.assertEqual(result["firstContentMs"], 125)
        self.assertEqual(result["contentChars"], 5)

    def test_parse_stream_detects_search_tool(self):
        lines = [
            'data: {"choices":[{"delta":{"tool_calls":[{"id":"call-1","function":{"name":"search_knowledge_base","arguments":"{\\"query\\":\\"制度\\"}"}}]}}]}',
            "data: [DONE]",
        ]
        result = ttft.parse_stream_timing(
            lines, started_seconds=2.0, clock=lambda: 2.1
        )
        self.assertEqual(result["predictedRoute"], "SEARCH")
        self.assertIsNone(result["firstContentMs"])

    def test_build_reference_context_limits_size_and_uses_retrieval_fields(self):
        rows = [
            {"fileName": "a.md", "textContent": "第一段", "score": 0.9},
            {"fileName": "b.md", "textContent": "第二段", "score": 0.8},
        ]
        context = ttft.build_reference_context(rows, max_chars=40)
        self.assertIn("a.md", context)
        self.assertIn("第一段", context)
        self.assertLessEqual(len(context), 40)

    def test_summary_calculates_p50_reduction_and_error_counts(self):
        rows = [
            {
                "id": "a",
                "agentic": {"ttftMs": 100, "error": None},
                "forced": {"ttftMs": 300, "retrievalMs": 150, "error": None},
            },
            {
                "id": "b",
                "agentic": {"ttftMs": 200, "error": None},
                "forced": {"ttftMs": 500, "retrievalMs": 200, "error": None},
            },
            {
                "id": "c",
                "agentic": {"ttftMs": None, "error": "timeout"},
                "forced": {"ttftMs": None, "retrievalMs": None, "error": "timeout"},
            },
        ]
        summary = ttft.summarize(rows)
        self.assertEqual(summary["completedPairs"], 2)
        self.assertEqual(summary["agentic"]["p50TtftMs"], 150)
        self.assertEqual(summary["forced"]["p50TtftMs"], 400)
        self.assertEqual(summary["forced"]["p50RetrievalMs"], 175)
        self.assertEqual(summary["p50TtftReductionPercent"], 62.5)
        self.assertEqual(summary["failedPairs"], 1)

    def test_load_direct_cases_filters_before_limit(self):
        path = Path(self.id().replace(".", "_") + ".jsonl")
        try:
            path.write_text(
                "\n".join(
                    json.dumps(row, ensure_ascii=False)
                    for row in [
                        {"id": "s", "expectedRoute": "SEARCH", "query": "私有资料"},
                        {"id": "d1", "expectedRoute": "DIRECT", "query": "通用问题1"},
                        {"id": "d2", "expectedRoute": "DIRECT", "query": "通用问题2"},
                    ]
                ),
                encoding="utf-8",
            )
            cases = ttft.load_direct_cases(path, limit=1)
            self.assertEqual([case["id"] for case in cases], ["d1"])
        finally:
            path.unlink(missing_ok=True)


if __name__ == "__main__":
    unittest.main()
