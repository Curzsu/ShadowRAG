# 检索评测小样本报告

运行：`retrieval-20261008-170718-d5497044`。固定用户权限，原始 topK=10，评分 topK=5。

标签来源：既有 eval_retrieval.py 前 10 条；用户在 2026-10-08 确认标签经人工核对。此次为链路验收，不代表 100 组正式简历评测。

| 实际策略 | 有效/总数 | Hit@5 | MRR@5 | 精排状态 |
|---|---|---|---|---|
| BM25 | 10/10 | 70.0% | 0.4950 | {'not_applicable': 10} |
| 混合链路（包含降级） | 10/10 | 60.0% | 0.5500 | {'fallback': 10} |

成功精排配对样本：0/10；排除 10 条。不能将降级结果标为成功精排。
没有成功精排的配对结果；不报告混合+精排改善。

配置与边界：bge-m3 查询向量、BM25/KNN 每路 recallK=300、RRF k=60、实际 BAAI/bge-reranker-v2-m3（TEI 1.9.3 CPU）、既有 Java 精排超时 10 秒。运行前后索引与权限摘要一致。未修改生产超时或语料。

Cloud 已核验 20 条实际 trace 与 40 个数值分数，值及 trace 关联与本地一致；另抽查两策略的子节点和无正文字段。

- [bm25 实测记录](https://jp.cloud.langfuse.com/project/cmuyeaxqk001oad0eov2lkv26/traces/ac66d6c3231c46574c9e322056392c69)
- [hybrid_rerank 实测记录](https://jp.cloud.langfuse.com/project/cmuyeaxqk001oad0eov2lkv26/traces/18960e97465b78c265083f2bb073749d)

本地重算（在项目根目录，将本报告文件夹绝对路径作为三个路径的前缀）：

```powershell
python docs/interview/eval_retrieval.py --java-results "docs/eval/retrieval/results/retrieval-20261008-170718-d5497044/java-results.jsonl" --dataset "docs/eval/retrieval/results/retrieval-20261008-170718-d5497044/dataset.json" --output-dir "target/langfuse-retrieval/recomputed-retrieval-20261008-170718-d5497044"
```
