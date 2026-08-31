#!/usr/bin/env python3
"""Measure end-to-end TTFT for Agentic routing versus forced retrieval."""

from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import time
from typing import Any, Callable, Iterable
import urllib.error
import urllib.parse
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

FORCED_SYSTEM_SUFFIX = (
    "\n\n本轮已按强制检索基线提供参考资料。请直接回答用户问题；"
    "参考资料不相关时，不要声称资料包含相关答案。"
)


def percentile(values: list[float | int], percent: float) -> float:
    if not values:
        raise ValueError("percentile requires at least one value")
    if not 0 <= percent <= 100:
        raise ValueError("percent must be between 0 and 100")
    ordered = sorted(float(value) for value in values)
    position = (len(ordered) - 1) * percent / 100
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def _rounded(value: float) -> float | int:
    rounded = round(value, 2)
    return int(rounded) if rounded.is_integer() else rounded


def load_direct_cases(path: str | Path, limit: int | None = None) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(
        Path(path).read_text(encoding="utf-8").splitlines(), start=1
    ):
        if not raw_line.strip():
            continue
        try:
            case = json.loads(raw_line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid JSON at {path}:{line_number}: {exc.msg}") from exc
        if case.get("expectedRoute") == "DIRECT":
            cases.append(case)
    return cases[:limit] if limit is not None else cases


def _decode_sse_line(raw_line: bytes | str) -> dict[str, Any] | None:
    line = raw_line.decode("utf-8") if isinstance(raw_line, bytes) else raw_line
    line = line.strip()
    if not line or line.startswith(":") or line.startswith("event:"):
        return None
    payload_text = line[5:].strip() if line.startswith("data:") else line
    if payload_text == "[DONE]":
        return {"done": True}
    try:
        return json.loads(payload_text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"invalid SSE JSON: {exc.msg}") from exc


def parse_stream_timing(
    lines: Iterable[bytes | str],
    *,
    started_seconds: float,
    clock: Callable[[], float] = time.perf_counter,
    stop_after_first_content: bool = False,
) -> dict[str, Any]:
    tool_detected = False
    content_chars = 0
    first_content_ms: int | None = None
    for raw_line in lines:
        payload = _decode_sse_line(raw_line)
        if payload is None:
            continue
        if payload.get("done"):
            break
        choices = payload.get("choices", [])
        if not choices:
            continue
        delta = choices[0].get("delta") or {}
        content = delta.get("content") or ""
        if content:
            if first_content_ms is None:
                first_content_ms = round((clock() - started_seconds) * 1000)
            content_chars += len(content)
            if stop_after_first_content:
                break
        if delta.get("tool_calls"):
            tool_detected = True
    return {
        "predictedRoute": "SEARCH" if tool_detected else "DIRECT",
        "firstContentMs": first_content_ms,
        "contentChars": content_chars,
    }


def _base_messages(case: dict[str, Any], system_prompt: str) -> list[dict[str, str]]:
    messages: list[dict[str, str]] = [{"role": "system", "content": system_prompt}]
    for message in case.get("history", []):
        if message.get("role") in {"user", "assistant"}:
            messages.append({
                "role": message["role"],
                "content": str(message.get("content", "")),
            })
    return messages


def build_reference_context(
    search_results: list[dict[str, Any]], *, max_chars: int = 12000
) -> str:
    parts: list[str] = []
    for index, result in enumerate(search_results, start=1):
        file_name = str(result.get("fileName") or result.get("fileMd5") or "unknown")
        text = str(result.get("textContent") or result.get("content") or "").strip()
        if not text:
            continue
        part = f"[{index}] {file_name}\n{text}"
        candidate = "\n\n".join(parts + [part])
        if len(candidate) > max_chars:
            remaining = max_chars - len("\n\n".join(parts)) - (2 if parts else 0)
            if remaining > 0:
                parts.append(part[:remaining])
            break
        parts.append(part)
    return "\n\n".join(parts)[:max_chars]


def _open_stream(
    *,
    api_url: str,
    api_key: str,
    body: dict[str, Any],
    timeout_seconds: float,
):
    request = urllib.request.Request(
        f"{api_url.rstrip('/')}/chat/completions",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "User-Agent": "ShadowRAG-ttft-eval/1.0",
        },
        method="POST",
    )
    return urllib.request.urlopen(request, timeout=timeout_seconds)


def _model_body(
    messages: list[dict[str, str]],
    *,
    model: str,
    temperature: float,
    top_p: float,
    max_tokens: int,
    with_tool: bool,
) -> dict[str, Any]:
    body: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "stream": True,
        "temperature": temperature,
        "top_p": top_p,
        "max_tokens": max_tokens,
    }
    if with_tool:
        body["tools"] = [SEARCH_TOOL]
    return body


def run_agentic(
    case: dict[str, Any],
    *,
    system_prompt: str,
    request_options: dict[str, Any],
) -> dict[str, Any]:
    messages = _base_messages(case, system_prompt)
    messages.append({"role": "user", "content": str(case["query"])})
    body = _model_body(messages, with_tool=True, **request_options["model_options"])
    started = time.perf_counter()
    try:
        with _open_stream(
            api_url=request_options["api_url"],
            api_key=request_options["api_key"],
            body=body,
            timeout_seconds=request_options["timeout_seconds"],
        ) as response:
            result = parse_stream_timing(
                response,
                started_seconds=started,
                stop_after_first_content=True,
            )
        if result["firstContentMs"] is None:
            raise RuntimeError(
                f"no answer token received; route={result['predictedRoute']}"
            )
        return {
            "ttftMs": result["firstContentMs"],
            "predictedRoute": result["predictedRoute"],
            "contentChars": result["contentChars"],
            "error": None,
        }
    except Exception as exc:
        return {
            "ttftMs": None,
            "predictedRoute": None,
            "contentChars": 0,
            "error": str(exc),
        }


def search_knowledge_base(
    query: str,
    *,
    search_url: str,
    search_token: str,
    top_k: int,
    timeout_seconds: float,
) -> list[dict[str, Any]]:
    separator = "&" if "?" in search_url else "?"
    url = (
        search_url
        + separator
        + urllib.parse.urlencode({"query": query, "topK": top_k})
    )
    request = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {search_token}", "Accept": "application/json"},
        method="GET",
    )
    with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if payload.get("code") != 200 or not isinstance(payload.get("data"), list):
        raise RuntimeError(f"unexpected search response: code={payload.get('code')}")
    return payload["data"]


def run_forced(
    case: dict[str, Any],
    *,
    system_prompt: str,
    request_options: dict[str, Any],
    search_url: str,
    search_token: str,
    top_k: int,
    context_max_chars: int,
) -> dict[str, Any]:
    overall_started = time.perf_counter()
    try:
        results = search_knowledge_base(
            str(case["query"]),
            search_url=search_url,
            search_token=search_token,
            top_k=top_k,
            timeout_seconds=request_options["timeout_seconds"],
        )
        retrieval_ms = round((time.perf_counter() - overall_started) * 1000)
        reference = build_reference_context(results, max_chars=context_max_chars)
        messages = _base_messages(case, system_prompt + FORCED_SYSTEM_SUFFIX)
        messages.append({
            "role": "user",
            "content": (
                f"<<REF>>\n{reference}\n<<END>>\n\n"
                f"用户问题：{case['query']}"
            ),
        })
        body = _model_body(messages, with_tool=False, **request_options["model_options"])
        model_started = time.perf_counter()
        with _open_stream(
            api_url=request_options["api_url"],
            api_key=request_options["api_key"],
            body=body,
            timeout_seconds=request_options["timeout_seconds"],
        ) as response:
            overall = parse_stream_timing(
                response,
                started_seconds=overall_started,
                stop_after_first_content=True,
            )
        if overall["firstContentMs"] is None:
            raise RuntimeError("no answer token received")
        return {
            "ttftMs": overall["firstContentMs"],
            "retrievalMs": retrieval_ms,
            "modelFirstTokenMs": round(
                overall["firstContentMs"] - (model_started - overall_started) * 1000
            ),
            "resultCount": len(results),
            "contentChars": overall["contentChars"],
            "error": None,
        }
    except Exception as exc:
        return {
            "ttftMs": None,
            "retrievalMs": None,
            "modelFirstTokenMs": None,
            "resultCount": None,
            "contentChars": 0,
            "error": str(exc),
        }


def summarize(rows: list[dict[str, Any]]) -> dict[str, Any]:
    completed = [
        row
        for row in rows
        if row["agentic"].get("ttftMs") is not None
        and row["forced"].get("ttftMs") is not None
    ]
    if not completed:
        raise ValueError("no completed benchmark pairs")
    agentic_ttft = [row["agentic"]["ttftMs"] for row in completed]
    forced_ttft = [row["forced"]["ttftMs"] for row in completed]
    retrieval = [row["forced"]["retrievalMs"] for row in completed]
    agentic_p50 = percentile(agentic_ttft, 50)
    forced_p50 = percentile(forced_ttft, 50)
    return {
        "totalPairs": len(rows),
        "completedPairs": len(completed),
        "failedPairs": len(rows) - len(completed),
        "agentic": {
            "p50TtftMs": _rounded(agentic_p50),
            "p95TtftMs": _rounded(percentile(agentic_ttft, 95)),
        },
        "forced": {
            "p50TtftMs": _rounded(forced_p50),
            "p95TtftMs": _rounded(percentile(forced_ttft, 95)),
            "p50RetrievalMs": _rounded(percentile(retrieval, 50)),
            "p95RetrievalMs": _rounded(percentile(retrieval, 95)),
        },
        "p50TtftReductionPercent": _rounded(
            (forced_p50 - agentic_p50) / forced_p50 * 100
        ),
    }


def _write_jsonl(path: str | Path, rows: list[dict[str, Any]]) -> None:
    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True)
    parser.add_argument("--system-prompt", required=True)
    parser.add_argument("--out-jsonl", required=True)
    parser.add_argument("--out-summary", required=True)
    parser.add_argument("--api-url", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--api-key-env", default="ROUTE_EVAL_API_KEY")
    parser.add_argument("--search-url", default="http://localhost:8081/api/v1/search/hybrid")
    parser.add_argument("--search-token-env", default="ROUTE_EVAL_SEARCH_TOKEN")
    parser.add_argument("--temperature", type=float, default=0.3)
    parser.add_argument("--top-p", type=float, default=0.9)
    parser.add_argument("--max-tokens", type=int, default=4096)
    parser.add_argument("--timeout-seconds", type=float, default=180)
    parser.add_argument("--top-k", type=int, default=10)
    parser.add_argument("--context-max-chars", type=int, default=12000)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--warmup", type=int, default=1)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    api_key = os.environ.get(args.api_key_env, "").strip()
    search_token = os.environ.get(args.search_token_env, "").strip()
    if not api_key:
        raise ValueError(f"API key environment variable is empty: {args.api_key_env}")
    if not search_token:
        raise ValueError(
            f"search token environment variable is empty: {args.search_token_env}"
        )
    cases = load_direct_cases(args.dataset, args.limit)
    if not cases:
        raise ValueError("dataset contains no DIRECT cases")
    system_prompt = Path(args.system_prompt).read_text(encoding="utf-8").strip()
    request_options = {
        "api_url": args.api_url,
        "api_key": api_key,
        "timeout_seconds": args.timeout_seconds,
        "model_options": {
            "model": args.model,
            "temperature": args.temperature,
            "top_p": args.top_p,
            "max_tokens": args.max_tokens,
        },
    }

    def agentic(case: dict[str, Any]) -> dict[str, Any]:
        return run_agentic(
            case, system_prompt=system_prompt, request_options=request_options
        )

    def forced(case: dict[str, Any]) -> dict[str, Any]:
        return run_forced(
            case,
            system_prompt=system_prompt,
            request_options=request_options,
            search_url=args.search_url,
            search_token=search_token,
            top_k=args.top_k,
            context_max_chars=args.context_max_chars,
        )

    for index in range(min(args.warmup, len(cases))):
        warmup_case = cases[index]
        agentic(warmup_case)
        forced(warmup_case)

    rows: list[dict[str, Any]] = []
    for index, case in enumerate(cases):
        if index % 2 == 0:
            agentic_result = agentic(case)
            forced_result = forced(case)
            order = "agentic-first"
        else:
            forced_result = forced(case)
            agentic_result = agentic(case)
            order = "forced-first"
        row = {
            "id": case["id"],
            "query": case["query"],
            "order": order,
            "agentic": agentic_result,
            "forced": forced_result,
        }
        rows.append(row)
        _write_jsonl(args.out_jsonl, rows)
        print(
            f"[{index + 1}/{len(cases)}] {case['id']} "
            f"agentic={agentic_result.get('ttftMs')}ms "
            f"forced={forced_result.get('ttftMs')}ms",
            flush=True,
        )

    summary = summarize(rows)
    summary.update({
        "dataset": str(Path(args.dataset)),
        "model": args.model,
        "apiUrl": args.api_url,
        "temperature": args.temperature,
        "topP": args.top_p,
        "topK": args.top_k,
        "warmupPairs": min(args.warmup, len(cases)),
        "timingDefinition": "request start to first non-empty answer content",
    })
    summary_path = Path(args.out_summary)
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
