#!/usr/bin/env python3
"""Offline validation and metrics for ShadowRAG route evaluation data."""

from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
from typing import Any


ROUTES = ("SEARCH", "DIRECT")
ANNOTATION_STATUSES = ("pending_human_review", "human_reviewed")


def _safe_divide(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def _ensure_human_reviewed(cases: list[dict[str, Any]]) -> None:
    reviewed_count = sum(
        case.get("annotationStatus") == "human_reviewed" for case in cases
    )
    if reviewed_count != len(cases):
        raise ValueError(
            "dataset is not fully human reviewed: "
            f"{len(cases) - reviewed_count} case(s) remain"
        )


def load_jsonl(path: str | Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line_number, raw_line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid JSON at {path}:{line_number}: {exc.msg}") from exc
        if not isinstance(row, dict):
            raise ValueError(f"JSONL row must be an object at {path}:{line_number}")
        rows.append(row)
    return rows


def render_markdown(report: dict[str, Any], *, title: str) -> str:
    lines = [
        f"# {title}",
        "",
        "## 总览",
        "",
        "| 指标 | 值 |",
        "|---|---:|",
        f"| 用例数 | {report['total']} |",
        f"| 正确数 | {report['correct']} |",
        f"| Accuracy | {report['accuracy']:.2%} |",
        f"| Macro-F1 | {report['macroF1']:.2%} |",
        "",
        "## 分类指标",
        "",
        "| 路由 | Support | Precision | Recall | F1 |",
        "|---|---:|---:|---:|---:|",
    ]
    for route in ROUTES:
        metrics = report["routes"][route]
        lines.append(
            f"| {route} | {metrics['support']} | {metrics['precision']:.2%} | "
            f"{metrics['recall']:.2%} | {metrics['f1']:.2%} |"
        )

    lines.extend([
        "",
        "## 混淆矩阵",
        "",
        "| Expected \\ Predicted | SEARCH | DIRECT |",
        "|---|---:|---:|",
        f"| SEARCH | {report['confusion']['SEARCH']['SEARCH']} | {report['confusion']['SEARCH']['DIRECT']} |",
        f"| DIRECT | {report['confusion']['DIRECT']['SEARCH']} | {report['confusion']['DIRECT']['DIRECT']} |",
        "",
        "## 标签切片",
        "",
        "| 标签 | 用例数 | 正确数 | Accuracy |",
        "|---|---:|---:|---:|",
    ])
    for tag, metrics in report["tags"].items():
        lines.append(
            f"| {tag} | {metrics['total']} | {metrics['correct']} | {metrics['accuracy']:.2%} |"
        )

    lines.extend(["", "## 错误用例", ""])
    if not report["errors"]:
        lines.append("无。")
    else:
        lines.extend([
            "| ID | Expected | Predicted | Tags | Query |",
            "|---|---|---|---|---|",
        ])
        for error in report["errors"]:
            query = str(error.get("query", "")).replace("|", "\\|").replace("\n", " ")
            tags = ", ".join(error.get("tags", []))
            lines.append(
                f"| {error['id']} | {error['expectedRoute']} | {error['predictedRoute']} | "
                f"{tags} | {query} |"
            )
    lines.append("")
    return "\n".join(lines)


def validate_dataset(
    cases: list[dict[str, Any]],
    *,
    expected_split: str,
    expected_count: int | None = None,
    expected_per_route: dict[str, int] | None = None,
    require_human_reviewed: bool = False,
) -> dict[str, Any]:
    seen_ids: set[str] = set()
    seen_prompts: set[str] = set()
    route_counts: Counter[str] = Counter()
    annotation_counts: Counter[str] = Counter()
    tag_counts: Counter[str] = Counter()

    for case in cases:
        case_id = case["id"]
        if case_id in seen_ids:
            raise ValueError(f"duplicate case id: {case_id}")
        seen_ids.add(case_id)

        route = case.get("expectedRoute")
        if route not in ROUTES:
            raise ValueError(f"invalid route for {case_id}: {route}")
        if case.get("split") != expected_split:
            raise ValueError(
                f"unexpected split for {case_id}: {case.get('split')} != {expected_split}"
            )
        query = case.get("query")
        if not isinstance(query, str) or not query.strip():
            raise ValueError(f"query must be non-empty for {case_id}")
        history = case.get("history")
        if not isinstance(history, list):
            raise ValueError(f"history must be a list for {case_id}")
        for message in history:
            if not isinstance(message, dict) or message.get("role") not in {
                "user", "assistant"
            }:
                raise ValueError(f"invalid history role for {case_id}")
            content = message.get("content")
            if not isinstance(content, str) or not content.strip():
                raise ValueError(f"history content must be non-empty for {case_id}")
        tags = case.get("tags")
        if not isinstance(tags, list) or not tags or not all(
            isinstance(tag, str) and tag.strip() for tag in tags
        ):
            raise ValueError(f"tags must be a non-empty list for {case_id}")
        annotation_status = case.get("annotationStatus")
        if annotation_status not in ANNOTATION_STATUSES:
            raise ValueError(
                f"invalid annotationStatus for {case_id}: {annotation_status}"
            )
        source = case.get("source")
        if not isinstance(source, str) or not source.strip():
            raise ValueError(f"source must be non-empty for {case_id}")

        prompt_fingerprint = _prompt_fingerprint(case)
        if prompt_fingerprint in seen_prompts:
            raise ValueError(f"duplicate prompt within {expected_split}: {case_id}")
        seen_prompts.add(prompt_fingerprint)

        route_counts[route] += 1
        annotation_counts[annotation_status] += 1
        tag_counts.update(tags)

    if expected_count is not None and len(cases) != expected_count:
        raise ValueError(f"unexpected case count: {len(cases)} != {expected_count}")
    if expected_per_route is not None and dict(route_counts) != expected_per_route:
        raise ValueError(
            f"unexpected route counts: {dict(route_counts)} != {expected_per_route}"
        )
    if require_human_reviewed:
        _ensure_human_reviewed(cases)

    return {
        "total": len(cases),
        "split": expected_split,
        "routeCounts": dict(route_counts),
        "annotationStatusCounts": dict(annotation_counts),
        "tagCounts": dict(tag_counts),
    }


def _prompt_fingerprint(case: dict[str, Any]) -> str:
    history = json.dumps(
        case.get("history", []),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    query = re.sub(r"\s+", "", str(case.get("query", ""))).casefold()
    return f"{history}|{query}"


def validate_no_split_overlap(
    dev_cases: list[dict[str, Any]],
    test_cases: list[dict[str, Any]],
) -> None:
    dev_ids = {case["id"] for case in dev_cases}
    test_ids = {case["id"] for case in test_cases}
    overlapping_ids = sorted(dev_ids & test_ids)
    if overlapping_ids:
        raise ValueError(f"overlapping case id: {overlapping_ids[0]}")

    dev_prompts = {_prompt_fingerprint(case) for case in dev_cases}
    for case in test_cases:
        if _prompt_fingerprint(case) in dev_prompts:
            raise ValueError(f"overlapping prompt: {case['id']}")


def evaluate_cases(
    dataset: list[dict[str, Any]],
    predictions: list[dict[str, Any]],
) -> dict[str, Any]:
    dataset_ids = {case["id"] for case in dataset}
    prediction_by_id: dict[str, str] = {}
    for item in predictions:
        case_id = item["id"]
        if case_id in prediction_by_id:
            raise ValueError(f"duplicate prediction id: {case_id}")
        predicted_route = item.get("predictedRoute")
        if predicted_route not in ROUTES:
            raise ValueError(
                f"invalid predicted route for {case_id}: {predicted_route}"
            )
        prediction_by_id[case_id] = predicted_route

    prediction_ids = set(prediction_by_id)
    unknown_ids = sorted(prediction_ids - dataset_ids)
    if unknown_ids:
        raise ValueError(f"unknown prediction ids: {', '.join(unknown_ids)}")
    missing_ids = sorted(dataset_ids - prediction_ids)
    if missing_ids:
        raise ValueError(f"missing predictions: {', '.join(missing_ids)}")

    confusion = {
        expected: {predicted: 0 for predicted in ROUTES}
        for expected in ROUTES
    }

    correct = 0
    errors: list[dict[str, Any]] = []
    tag_totals: Counter[str] = Counter()
    tag_correct: Counter[str] = Counter()
    for case in dataset:
        expected = case["expectedRoute"]
        predicted = prediction_by_id[case["id"]]
        confusion[expected][predicted] += 1
        is_correct = expected == predicted
        correct += int(is_correct)
        for tag in case.get("tags", []):
            tag_totals[tag] += 1
            tag_correct[tag] += int(is_correct)
        if not is_correct:
            errors.append({
                "id": case["id"],
                "query": case.get("query", ""),
                "expectedRoute": expected,
                "predictedRoute": predicted,
                "tags": case.get("tags", []),
            })

    route_metrics: dict[str, dict[str, float | int]] = {}
    for route in ROUTES:
        true_positive = confusion[route][route]
        predicted_total = sum(confusion[expected][route] for expected in ROUTES)
        expected_total = sum(confusion[route].values())
        precision = _safe_divide(true_positive, predicted_total)
        recall = _safe_divide(true_positive, expected_total)
        f1 = _safe_divide(2 * precision * recall, precision + recall)
        route_metrics[route] = {
            "support": expected_total,
            "precision": precision,
            "recall": recall,
            "f1": f1,
        }

    tag_metrics = {
        tag: {
            "total": tag_totals[tag],
            "correct": tag_correct[tag],
            "accuracy": _safe_divide(tag_correct[tag], tag_totals[tag]),
        }
        for tag in sorted(tag_totals)
    }

    return {
        "total": len(dataset),
        "correct": correct,
        "accuracy": _safe_divide(correct, len(dataset)),
        "confusion": confusion,
        "routes": route_metrics,
        "macroF1": sum(float(route_metrics[route]["f1"]) for route in ROUTES) / len(ROUTES),
        "tags": tag_metrics,
        "errors": errors,
    }


def _balanced_route_counts(total: int) -> dict[str, int]:
    if total % len(ROUTES) != 0:
        raise ValueError(f"expected count must be divisible by {len(ROUTES)}: {total}")
    per_route = total // len(ROUTES)
    return {route: per_route for route in ROUTES}


def _write_json(path: str | Path, payload: dict[str, Any]) -> None:
    output_path = Path(path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def _write_text(path: str | Path, content: str) -> None:
    output_path = Path(path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(content, encoding="utf-8")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_parser = subparsers.add_parser("validate", help="validate dev/test JSONL files")
    validate_parser.add_argument("--dev", required=True)
    validate_parser.add_argument("--test", required=True)
    validate_parser.add_argument("--dev-count", type=int, default=60)
    validate_parser.add_argument("--test-count", type=int, default=120)
    validate_parser.add_argument(
        "--require-human-reviewed",
        action="store_true",
        help="fail unless every dev/test label has annotationStatus=human_reviewed",
    )

    evaluate_parser = subparsers.add_parser("evaluate", help="calculate route metrics")
    evaluate_parser.add_argument("--dataset", required=True)
    evaluate_parser.add_argument("--predictions", required=True)
    evaluate_parser.add_argument("--out-json", required=True)
    evaluate_parser.add_argument("--out-md", required=True)
    evaluate_parser.add_argument("--title", default="ShadowRAG Agent 路由评测报告")
    evaluate_parser.add_argument(
        "--require-human-reviewed",
        action="store_true",
        help="fail unless every dataset label has annotationStatus=human_reviewed",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "validate":
        dev_cases = load_jsonl(args.dev)
        test_cases = load_jsonl(args.test)
        dev_summary = validate_dataset(
            dev_cases,
            expected_split="dev",
            expected_count=args.dev_count,
            expected_per_route=_balanced_route_counts(args.dev_count),
            require_human_reviewed=args.require_human_reviewed,
        )
        test_summary = validate_dataset(
            test_cases,
            expected_split="test",
            expected_count=args.test_count,
            expected_per_route=_balanced_route_counts(args.test_count),
            require_human_reviewed=args.require_human_reviewed,
        )
        validate_no_split_overlap(dev_cases, test_cases)
        print(json.dumps(
            {"status": "valid", "dev": dev_summary, "test": test_summary},
            ensure_ascii=False,
            indent=2,
        ))
        return 0

    dataset = load_jsonl(args.dataset)
    predictions = load_jsonl(args.predictions)
    if args.require_human_reviewed:
        _ensure_human_reviewed(dataset)
    report = evaluate_cases(dataset, predictions)
    _write_json(args.out_json, report)
    _write_text(args.out_md, render_markdown(report, title=args.title))
    print(json.dumps(
        {"status": "evaluated", "total": report["total"], "accuracy": report["accuracy"]},
        ensure_ascii=False,
    ))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
