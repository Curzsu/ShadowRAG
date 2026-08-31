# 通用问答 TTFT 对照报告

## 结论

在锁定测试集的 60 条 `DIRECT` 问题上，Agentic 按需路由的 P50 TTFT 为 **8,659 ms**，强制检索基线为 **13,187 ms**，P50 降低 **34.34%**。P95 从 **23,041.25 ms** 降至 **20,641.3 ms**，降低 **10.42%**。

| 指标 | 强制检索 | Agentic 路由 | 变化 |
|---|---:|---:|---:|
| P50 TTFT | 13,187 ms | 8,659 ms | -34.34% |
| P95 TTFT | 23,041.25 ms | 20,641.3 ms | -10.42% |
| 检索阶段 P50 | 1,534 ms | 跳过 | -100% |
| 成功样本 | 60/60 | 60/60 | 0 失败 |

## 口径

- TTFT 从一轮请求开始计时，到流式响应中首个非空回答字符到达为止；状态事件和空增量不计入。
- 两组使用同一 `glm-5-3-flash` 模型、System Prompt、temperature、top_p 和输出上限。
- 强制检索组真实执行本地 `bge-m3` Embedding、Elasticsearch KNN + BM25、RRF 与 `bge-reranker-v2-m3` Cross-Encoder Rerank，应用 TEI 精排顺序后向模型注入 top 10 结果。
- Agentic 组携带与生产一致的 `search_knowledge_base` Tool；60 条通用问题均由首轮模型直接回答。
- 单并发运行，先做 2 对预热；正式样本交替执行，30 条 Agentic 先跑、30 条强制检索先跑。
- 每个样本只运行 1 次，因此报告保留全部长尾，不通过删除或选择性重跑处理异常值。

## 解释

P50 总降幅高于检索阶段本身的 1,534 ms，因为强制检索不仅多执行一次检索链路，还向模型注入了 10 条上下文，增加了首字前的输入预填充时间。火山模型服务本身存在秒级波动，因此使用 60 条样本的 P50/P95，而不使用单条耗时或平均值作为简历指标。

## 证据

- 原始逐条结果：`ttft-direct-60-rerank-v2-volc.jsonl`
- 机器可读摘要：`ttft-direct-60-rerank-summary-v2-volc.json`
- 完整运行元数据与 SHA-256：`evidence-bundle-v2-volc.json`
