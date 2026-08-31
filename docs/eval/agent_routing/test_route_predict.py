import importlib.util
import json
import pathlib
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("route_predict.py")
V2_PROMPT_PATH = pathlib.Path(__file__).with_name("routing-system-prompt-v2.txt")
PROJECT_ROOT = pathlib.Path(__file__).parents[3]
APPLICATION_YML = PROJECT_ROOT / "src" / "main" / "resources" / "application.yml"
CHAT_HANDLER = (
    PROJECT_ROOT / "src" / "main" / "java" / "com" / "yizhaoqi" /
    "smartpai" / "service" / "ChatHandler.java"
)


def load_module(test_case: unittest.TestCase):
    test_case.assertTrue(MODULE_PATH.exists(), "route_predict.py must exist")
    spec = importlib.util.spec_from_file_location("route_predict", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class RequestConstructionTest(unittest.TestCase):
    def test_build_request_matches_production_router_shape(self):
        route_predict = load_module(self)
        case = {
            "history": [
                {"role": "user", "content": "阅读《故障复盘》"},
                {"role": "assistant", "content": "好的。"},
            ],
            "query": "它的根因是什么？",
        }

        request = route_predict.build_request(
            case,
            system_prompt="系统规则",
            model="glm-5",
            temperature=0.3,
            top_p=0.9,
            max_tokens=4096,
        )

        self.assertEqual(request["model"], "glm-5")
        self.assertTrue(request["stream"])
        self.assertEqual(request["messages"], [
            {"role": "system", "content": "系统规则"},
            {"role": "user", "content": "阅读《故障复盘》"},
            {"role": "assistant", "content": "好的。"},
            {"role": "user", "content": "它的根因是什么？"},
        ])
        tool = request["tools"][0]["function"]
        self.assertEqual(tool["name"], "search_knowledge_base")
        self.assertIn("通用知识", tool["description"])
        self.assertIn("明确指向", tool["description"])
        self.assertEqual(tool["parameters"]["required"], ["query"])

    def test_v2_prompt_defines_private_source_boundary_and_negative_examples(self):
        self.assertTrue(V2_PROMPT_PATH.exists(), "routing-system-prompt-v2.txt must exist")
        prompt = V2_PROMPT_PATH.read_text(encoding="utf-8")
        self.assertIn("必须依赖当前知识库", prompt)
        self.assertIn("通常、一般、为什么、如何设计", prompt)
        self.assertIn("不要调用工具", prompt)

    def test_production_prompt_and_tool_description_match_v2_boundary(self):
        lines = APPLICATION_YML.read_text(encoding="utf-8").splitlines()
        start = lines.index("    rules: |") + 1
        prompt_lines = []
        for line in lines[start:]:
            if line.startswith("  generation:"):
                break
            prompt_lines.append(line[6:] if line.startswith("      ") else "")
        production_prompt = "\n".join(prompt_lines).strip()
        candidate_prompt = V2_PROMPT_PATH.read_text(encoding="utf-8").strip()
        self.assertEqual(production_prompt, candidate_prompt)

        chat_handler = CHAT_HANDLER.read_text(encoding="utf-8")
        self.assertIn("仅用于检索用户明确指向的已上传文件", chat_handler)
        self.assertIn("只有可靠回答必须依赖这些私有或指定资料时才调用", chat_handler)

    def test_production_uses_volc_coding_plan_endpoint_without_storing_key(self):
        config = APPLICATION_YML.read_text(encoding="utf-8")
        self.assertIn(
            "url: https://ark.cn-beijing.volces.com/api/coding/v3", config
        )
        self.assertIn("model: glm-5-3-flash", config)
        self.assertIn('key: "${DEEPSEEK_API_KEY:}"', config)
        self.assertNotIn("ark.cn-beijing.volces.com/api/v3", config)


class StreamParsingTest(unittest.TestCase):
    def test_parse_stream_detects_fragmented_search_tool_call(self):
        route_predict = load_module(self)
        chunks = [
            {"choices": [{"delta": {"tool_calls": [{
                "id": "call-1",
                "function": {"name": "search_knowledge_base", "arguments": "{\"que"},
            }]}}]},
            {"choices": [{"delta": {"tool_calls": [{
                "function": {"arguments": "ry\":\"合同\"}"},
            }]}, "finish_reason": "tool_calls"}]},
        ]
        lines = [
            f"data: {json.dumps(chunk, ensure_ascii=False)}\n".encode("utf-8")
            for chunk in chunks
        ] + [b"data: [DONE]\n"]

        result = route_predict.parse_sse_lines(lines)

        self.assertEqual(result["predictedRoute"], "SEARCH")
        self.assertEqual(result["toolName"], "search_knowledge_base")
        self.assertEqual(result["toolCallId"], "call-1")
        self.assertEqual(json.loads(result["toolArguments"]), {"query": "合同"})

    def test_parse_stream_returns_direct_when_no_tool_call_exists(self):
        route_predict = load_module(self)
        lines = [
            b'data: {"choices":[{"delta":{"content":"42"}}]}\n',
            b'data: [DONE]\n',
        ]

        result = route_predict.parse_sse_lines(lines)

        self.assertEqual(result["predictedRoute"], "DIRECT")
        self.assertIsNone(result["toolName"])
        self.assertEqual(result["contentChars"], 2)

    def test_parse_stream_rejects_invalid_json_event(self):
        route_predict = load_module(self)
        with self.assertRaisesRegex(ValueError, "invalid SSE JSON"):
            route_predict.parse_sse_lines([b"data: {bad-json}\n"])


if __name__ == "__main__":
    unittest.main()
