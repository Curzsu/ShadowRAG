#!/usr/bin/env python3
"""Run the production-shaped first Function Calling turn for route evaluation."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import time
from typing import Any, Iterable
import urllib.error
import urllib.request


SEARCH_TOOL = {
    "type": "function",
    "function": {
        "name": "search_knowledge_base",
        "description": (
            "仅用于检索用户明确指向的已上传文件、当前知识库、内部制度或项目文档中的事实。"
            "只有可靠回答必须依赖这些私有或指定资料时才调用。对于通用知识、技术原理、行业惯例、"
            "计算、写作和一般建议不要调用；问题仅出现“文档、报告、制度、流程、基金”等名词，"
            "但未要求读取具体资料时，也不要调用。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "搜索查询语句，用于在知识库中检索相关文档内容",
                }
            },
            "required": ["query"],
        },
    },
}


def load_jsonl(path: str | Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(
        Path(path).read_text(encoding="utf-8").splitlines(), start=1
    ):
        if not raw_line.strip():
            continue
        try:
            row = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid JSON at {path}:{line_number}: {exc.msg}") from exc
        if not isinstance(row, dict):
            raise ValueError(f"JSONL row must be an object at {path}:{line_number}")
        rows.append(row)
    return rows


def build_request(
    case: dict[str, Any],
    *,
    system_prompt: str,
    model: str,
    temperature: float,
    top_p: float,
    max_tokens: int,
) -> dict[str, Any]:
    messages: list[dict[str, str]] = [
        {"role": "system", "content": system_prompt}
    ]
    for message in case.get("history", []):
        if message.get("role") in {"user", "assistant"}:
            messages.append({
                "role": message["role"],
                "content": str(message.get("content", "")),
            })
    messages.append({"role": "user", "content": str(case["query"])})
    return {
        "model": model,
        "messages": messages,
        "stream": True,
        "tools": [SEARCH_TOOL],
        "temperature": temperature,
        "top_p": top_p,
        "max_tokens": max_tokens,
    }


def parse_sse_lines(lines: Iterable[bytes | str]) -> dict[str, Any]:
    tool_detected = False
    tool_name = ""
    tool_call_id = ""
    tool_arguments: list[str] = []
    content_chars = 0

    for raw_line in lines:
        line = raw_line.decode("utf-8") if isinstance(raw_line, bytes) else raw_line
        line = line.strip()
        if not line or line.startswith(":") or line.startswith("event:"):
            continue
        payload_text = line[5:].strip() if line.startswith("data:") else line
        if payload_text == "[DONE]":
            break
        try:
            payload = json.loads(payload_text)
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid SSE JSON: {exc.msg}") from exc
        choices = payload.get("choices", [])
        if not choices:
            continue
        delta = choices[0].get("delta") or {}
        content = delta.get("content") or ""
        content_chars += len(content)
        tool_calls = delta.get("tool_calls") or []
        if not tool_calls:
            continue
        tool_detected = True
        tool_call = tool_calls[0]
        if tool_call.get("id"):
            tool_call_id = tool_call["id"]
        function = tool_call.get("function") or {}
        if function.get("name"):
            tool_name += function["name"]
        if function.get("arguments"):
            tool_arguments.append(function["arguments"])

    return {
        "predictedRoute": "SEARCH" if tool_detected else "DIRECT",
        "toolName": tool_name or None,
        "toolCallId": tool_call_id or None,
        "toolArguments": "".join(tool_arguments) or None,
        "contentChars": content_chars,
    }


def predict_case(
    case: dict[str, Any],
    *,
    api_url: str,
    api_key: str,
    system_prompt: str,
    model: str,
    temperature: float,
    top_p: float,
    max_tokens: int,
    timeout_seconds: float,
) -> dict[str, Any]:
    request_body = build_request(
        case,
        system_prompt=system_prompt,
        model=model,
        temperature=temperature,
        top_p=top_p,
        max_tokens=max_tokens,
    )
    request = urllib.request.Request(
        f"{api_url.rstrip('/')}/chat/completions",
        data=json.dumps(request_body, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "User-Agent": "ShadowRAG-route-eval/1.0",
        },
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            result = parse_sse_lines(response)
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", errors="replace")[:1000]
        raise RuntimeError(f"HTTP {exc.code}: {error_body}") from exc
    result.update({
        "id": case["id"],
        "latencyMs": round((time.perf_counter() - started) * 1000),
        "error": None,
    })
    return result


def _predict_with_retries(
    case: dict[str, Any],
    *,
    retries: int,
    request_options: dict[str, Any],
) -> dict[str, Any]:
    last_error: Exception | None = None
    for attempt in range(retries + 1):
        try:
            return predict_case(case, **request_options)
        except Exception as exc:  # Keep the batch and its evidence even if one request fails.
            last_error = exc
            if attempt < retries:
                time.sleep(min(2 ** attempt, 5))
    return {
        "id": case["id"],
        "predictedRoute": None,
        "toolName": None,
        "toolCallId": None,
        "toolArguments": None,
        "contentChars": 0,
        "latencyMs": None,
        "error": str(last_error),
    }


def _write_jsonl(path: str | Path, rows: list[dict[str, Any]]) -> None:
    output_path = Path(path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--system-prompt", required=True)
    parser.add_argument(
        "--api-url", default="https://open.bigmodel.cn/api/coding/paas/v4"
    )
    parser.add_argument("--api-key-env", default="ROUTE_EVAL_API_KEY")
    parser.add_argument("--model", default="glm-5")
    parser.add_argument("--temperature", type=float, default=0.3)
    parser.add_argument("--top-p", type=float, default=0.9)
    parser.add_argument("--max-tokens", type=int, default=4096)
    parser.add_argument("--timeout-seconds", type=float, default=180)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--limit", type=int)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    api_key = os.environ.get(args.api_key_env, "").strip()
    if not api_key:
        raise ValueError(f"API key environment variable is empty: {args.api_key_env}")
    cases = load_jsonl(args.dataset)
    if args.limit is not None:
        cases = cases[:args.limit]
    system_prompt = Path(args.system_prompt).read_text(encoding="utf-8").strip()
    request_options = {
        "api_url": args.api_url,
        "api_key": api_key,
        "system_prompt": system_prompt,
        "model": args.model,
        "temperature": args.temperature,
        "top_p": args.top_p,
        "max_tokens": args.max_tokens,
        "timeout_seconds": args.timeout_seconds,
    }

    by_id: dict[str, dict[str, Any]] = {}
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as executor:
        futures = {
            executor.submit(
                _predict_with_retries,
                case,
                retries=max(0, args.retries),
                request_options=request_options,
            ): case
            for case in cases
        }
        for completed, future in enumerate(as_completed(futures), start=1):
            case = futures[future]
            result = future.result()
            by_id[case["id"]] = result
            status = result["predictedRoute"] or "ERROR"
            print(f"[{completed}/{len(cases)}] {case['id']} -> {status}", flush=True)

    ordered_results = [by_id[case["id"]] for case in cases]
    _write_jsonl(args.out, ordered_results)
    error_count = sum(bool(row["error"]) for row in ordered_results)
    print(json.dumps({
        "status": "complete" if error_count == 0 else "partial",
        "total": len(ordered_results),
        "errors": error_count,
        "output": str(args.out),
    }, ensure_ascii=False))
    return 0 if error_count == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
