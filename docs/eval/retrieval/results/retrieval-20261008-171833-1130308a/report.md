# 检索评测小样本报告

运行：`retrieval-20261008-171833-1130308a`。固定用户权限，原始 topK=10，评分 topK=5。

标签来源：既有 eval_retrieval.py 前 10 条；用户在 2026-10-08 确认标签经人工核对。此次为链路验收，不代表 100 组正式简历评测。

| 实际策略 | 有效/总数 | Hit@5 | MRR@5 | 精排状态 |
|---|---|---|---|---|
| BM25 | 10/10 | 70.0% | 0.4950 | {'not_applicable': 10} |
| 混合链路（包含降级） | 10/10 | 70.0% | 0.6250 | {'success': 6, 'fallback': 4} |

成功精排配对样本：6/10；排除 4 条。不能将降级结果标为成功精排。

- BM25：Hit@5=50.00%，MRR@5=0.3667（配对分母 6）。
- 混合+成功精排：Hit@5=66.67%，MRR@5=0.5417（配对分母 6）。
- 配对改善：Hit@5 +16.67 个百分点，MRR@5 +0.1750；该子集由精排成功情况形成，不代表全体问题。

配置与边界：bge-m3 查询向量、BM25/KNN 每路 recallK=300、RRF k=60、实际 BAAI/bge-reranker-v2-m3（TEI 1.9.3 CPU）、既有 Java 精排超时 10 秒。运行前后索引与权限摘要一致。未修改生产超时或语料。

同一冻结样本的第二次运行；此前已用同批候选直接请求精排服务预热。首轮所有精排降级的证据另行保留，没有挑选或删除失败记录。

Cloud 已核验 20 条实际 trace 与 40 个数值分数，值及 trace 关联与本地一致；另抽查两策略的子节点和无正文字段。

- [bm25 实测记录](https://jp.cloud.langfuse.com/project/cmuyeaxqk001oad0eov2lkv26/traces/9c8456816bfd8181c67905e1420d45d1)
- [hybrid_rerank 实测记录](https://jp.cloud.langfuse.com/project/cmuyeaxqk001oad0eov2lkv26/traces/0202f3320b86e323bac30271219b0cbc)

本地重算（在项目根目录，将本报告文件夹绝对路径作为三个路径的前缀）：

```powershell
python docs/interview/eval_retrieval.py --java-results "docs/eval/retrieval/results/retrieval-20261008-171833-1130308a/java-results.jsonl" --dataset "docs/eval/retrieval/results/retrieval-20261008-171833-1130308a/dataset.json" --output-dir "target/langfuse-retrieval/recomputed-retrieval-20261008-171833-1130308a"
```
