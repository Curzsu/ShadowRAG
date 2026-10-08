# Langfuse 检索实验联调

运行：`retrieval-exp-20261008-180248-9d8c0c85`。数据源：Cloud `retrieval-eval-50` 按编号前10条人工标签，用户 Lukesu，两种策略共享权限并通过索引前后校验。

| 实际策略 | 样本数 | Hit@5 | MRR@5 |
| --- | --- | --- | --- |
| BM25 | 10 | 70% | 0.495 |
| 混合检索链路（包含8条超时降级） | 10 | 60% | 0.600 |

精排2条成功、8条降级。成功子集只有2条，不可外推全部样本；本轮仅验证实验接入，不替代正式100条简历指标。

云端已回读核验两个实验各10条、20条真实trace、40个根节点分数、Cloud DatasetItem身份、输入/标签/有序输出。

在 Langfuse：Datasets → retrieval-eval-50 → Experiments，查看本运行前缀的 bm25、hybrid_rerank 两组。

重算本地报告：

```powershell
python docs/interview/eval_retrieval.py --java-results docs/eval/retrieval/results/retrieval-exp-20261008-180248-9d8c0c85/java-results.jsonl --dataset docs/eval/retrieval/results/retrieval-exp-20261008-180248-9d8c0c85/dataset.json --output-dir docs/eval/retrieval/results/retrieval-exp-20261008-180248-9d8c0c85
```
