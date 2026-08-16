#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Strip [ocr] tool-log lines from the code review file and translate the
remaining English prose to Chinese, while preserving code blocks, diff
lines, section headers and file paths.
"""
import sys
import time
from deep_translator import GoogleTranslator

SRC = r"C:/Users/Lukesu/Desktop/shadowrag的code review.txt"
DST = SRC

MAX_CHARS = 3000      # max chars per translation batch
MAX_LINES = 30        # max lines per translation batch
SLEEP = 0.4           # polite delay between batches (seconds)
RETRIES = 6

translator = GoogleTranslator(source="en", target="zh-CN")
batch_cache = {}     # joined-batch -> translated
line_cache = {}      # single line -> translated


def classify(line):
    s = line.strip()
    if not s:
        return "KEEP"          # blank line
    if s.startswith("[ocr]"):
        return "DROP"          # redundant tool log
    if line[0] in (" ", "\t"):
        return "KEEP"          # indented code
    if line.startswith("─"):
        return "KEEP"          # section header (─── path ───)
    if line[0] in "-+":
        return "KEEP"          # diff line
    return "TRANS"             # translatable prose / tag line


def translate_with_retry(text):
    if text in batch_cache:
        return batch_cache[text]
    last = None
    for attempt in range(1, RETRIES + 1):
        try:
            out = translator.translate(text)
            batch_cache[text] = out
            return out
        except Exception as e:  # noqa
            last = e
            time.sleep(1.5 * attempt)
    # give up on batching, fall back to per-line
    lines = text.split("\n")
    res = []
    for ln in lines:
        if ln in line_cache:
            res.append(line_cache[ln])
        else:
            try:
                tr = translator.translate(ln)
            except Exception:
                tr = ln
            line_cache[ln] = tr
            res.append(tr)
            time.sleep(SLEEP)
    return "\n".join(res)


def main():
    with open(SRC, "r", encoding="utf-8", errors="replace") as f:
        lines = f.read().split("\n")

    out = []
    i = 0
    n = len(lines)
    dropped = 0
    translated_batches = 0

    while i < n:
        line = lines[i]
        cls = classify(line)
        if cls == "DROP":
            dropped += 1
            i += 1
            continue
        if cls == "KEEP":
            out.append(line)
            i += 1
            continue

        # gather a batch of consecutive translatable lines
        batch = []
        chars = 0
        j = i
        while j < n and len(batch) < MAX_LINES and chars < MAX_CHARS:
            c = classify(lines[j])
            if c != "TRANS":
                break
            batch.append(lines[j])
            chars += len(lines[j]) + 1
            j += 1

        if not batch:
            # safety: should not happen
            out.append(line)
            i += 1
            continue

        joined = "\n".join(batch)
        translated = translate_with_retry(joined)
        parts = translated.split("\n")
        if len(parts) == len(batch):
            out.extend(parts)
        else:
            # line count changed; translate individually
            for ln in batch:
                if ln in line_cache:
                    out.append(line_cache[ln])
                else:
                    try:
                        tr = translator.translate(ln)
                    except Exception:
                        tr = ln
                    line_cache[ln] = tr
                    out.append(tr)
                    time.sleep(SLEEP)
        translated_batches += 1
        if translated_batches % 25 == 0:
            print(f"...processed {translated_batches} batches, line {j}/{n}",
                  flush=True)
        time.sleep(SLEEP)
        i = j

    with open(DST, "w", encoding="utf-8") as f:
        f.write("\n".join(out))

    print(f"DONE. dropped={dropped} translated_batches={translated_batches} "
          f"total_out_lines={len(out)}", flush=True)


if __name__ == "__main__":
    main()
