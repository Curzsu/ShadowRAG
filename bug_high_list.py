#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import re
from collections import OrderedDict

P = r"C:/Users/Lukesu/Desktop/shadowrag的code review.txt"
lines = open(P, encoding="utf-8").read().split("\n")

pat = re.compile(r"\[bug[ ·]*(high|高)\]")
hdr = re.compile(r"───\s*(.+?)\s*───")

cur = None
items = []
for i, l in enumerate(lines):
    mh = hdr.search(l)
    if mh:
        cur = mh.group(1).strip()
        continue
    if pat.search(l):
        mt = pat.search(l)
        title = l[mt.end():].strip()
        if len(title) > 90:
            title = title[:90] + "…"
        items.append((i + 1, cur or "(unknown)", title))

groups = OrderedDict()
order = ["src", "frontend", "homepage", "docs",
         ".gitignore:30-30", ".gitattributes:1-1"]
for ln, h, t in items:
    base = h.split("/")[0] if h else "(unknown)"
    groups.setdefault(base, []).append((ln, h, t))

label = {
    "src": "后端 (src/main/java)",
    "frontend": "前端 (frontend/)",
    "homepage": "官网 (homepage/)",
    "docs": "文档 (docs/)",
    ".gitignore:30-30": ".gitignore",
    ".gitattributes:1-1": ".gitattributes",
}

out = []
out.append("# [bug · high] 级别问题清单（共 %d 条）" % len(items))
out.append("")
out.append("> 来源：`shadowrag的code review.txt`（已翻译版）")
out.append("")
for base in order:
    if base not in groups:
        continue
    lst = groups[base]
    out.append("## %s — %d 条" % (label.get(base, base), len(lst)))
    out.append("")
    for idx, (ln, h, t) in enumerate(lst, 1):
        out.append("%d. **L%d** `%s` — %s" % (idx, ln, h, t))
    out.append("")

out.append("---")
out.append("统计：前端 %d · 后端 %d · 官网 %d · 文档 %d · 其他 2" % (
    len(groups.get("frontend", [])),
    len(groups.get("src", [])),
    len(groups.get("homepage", [])),
    len(groups.get("docs", []))))

with open(r"E:/Curzsu/ShadowRAG/bug_high_list.md", "w", encoding="utf-8") as f:
    f.write("\n".join(out) + "\n")

print("written", len(items), "items")
