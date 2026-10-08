# Langfuse 检索评测阶段

范围：实际 Java 检索结果与相同权限下的 BM25 对照，计算 Hit@5、MRR@5，并将逐条分数关联到实际 trace。继续使用 `codex/langfuse-phase-1-2`；不自动提交、推送或合并。TTFT 对照与路由评测阶段不在本次范围内。

## 实现与口径

- Java 入口为 `RetrievalEvaluationExportTest` 的显式外部测试。普通 Maven 测试只运行离线契约测试，不连接 Cloud、数据库或检索服务。
- 混合策略调用实际 `HybridSearchService.searchWithPermission(query, username, 10)`，使用当前向量召回、BM25、Java RRF 和 Cross-Encoder 精排。
- BM25 通过测试工具调用服务已有私有入口，复用同一用户数据库 ID 和有效组织标签，不伪造向量失败来得到基线。
- 两策略返回 top 10，评分仅取前 5。Hit@5 为至少一个人工标注 chunk 被命中；逐条 RR@5 为第一个相关结果排名的倒数，未命中为 0；全体有效逐条 RR 的平均值为 MRR@5。
- 搜索错误保留错误记录，质量分数为空，报告错误数与有效分母。混合退回 BM25 或精排跳过/降级仍保留实际结果，但不进入“成功混合+精排”的配对改善统计。
- 运行前检查标签在当前用户权限下可见；前后记录权限摘要及索引 UUID、设置/映射摘要、文档数、主分片 max_seq_no。检测到变化则拒绝评分。配置与 Java 源码摘要一起保存，不能只用旧 HEAD 表示未提交实现。
- 每条样本/策略在 Java 调用前建立独立 trace。Python 只附加分数，不重建虚构调用树；Cloud 不上传问题和 chunk 正文。

## 使用方式

先保存冻结的 JSON 评测集，每条为 `query_id`、`query`、`relevant`（`fileMd5:chunkId` 列表）、`difficulty`。既有脚本中有 50 条标注，用户已确认人工核对；本阶段先验收前 10 条，不把 10 条结果作为 100 组正式简历指标。

在项目根目录设置外部测试参数，运行实际导出。每次用新的 run ID 和输出目录，避免覆盖原始记录：

```powershell
$env:LANGFUSE_RETRIEVAL_EVAL = 'true'
$env:RETRIEVAL_EVAL_USER = 'Lukesu'
$env:RETRIEVAL_EVAL_DATASET = '冻结数据集的绝对路径'
$env:RETRIEVAL_EVAL_RUN_ID = '本次唯一运行标识'
$env:RETRIEVAL_EVAL_OUTPUT_DIR = '新输出目录的绝对路径'
. ./scripts/langfuse/run-with-langfuse.ps1 -Command mvn -CommandArguments @('-Dtest=RetrievalEvaluationExportTest*', 'test')
Remove-Item Env:LANGFUSE_RETRIEVAL_EVAL
```

输出目录包含 `java-results.jsonl` 和 `metadata.json`。离线评分不需要 Langfuse SDK、requests 或 numpy：

```powershell
python docs/interview/eval_retrieval.py --java-results <输出目录>/java-results.jsonl --dataset <冻结数据集> --output-dir <评分目录>
```

上报分数时复用已配置的私有凭据：

```powershell
. ./scripts/langfuse/run-with-langfuse.ps1 -Command python -CommandArguments @('docs/interview/eval_retrieval.py', '--java-results', '<输出目录>/java-results.jsonl', '--dataset', '<冻结数据集>', '--output-dir', '<评分目录>', '--upload-scores')
```

评分目录保留有序结果、标签、配置、逐条得分、汇总和上传状态。上传失败返回非零退出码，保留本地结果；对同一导出文件重试使用相同 score ID，不重复计数。认证请求拒绝重定向，异常不输出凭据或远端响应正文。

在 Langfuse 的 Sessions 查找本次 run ID，或在 Traces 中查看 `retrieval.evaluation`，通过 metadata 的 sample_id/strategy 区分样本与策略，再看 Scores 中的 `hit_at_5` 与 `mrr_at_5`。本节两轮历史验收使用追踪与评分视图；后续标准 Experiments 接入及执行入口见 [数据集实验接入](2026-10-08-langfuse-dataset-experiments.md)。[评分写入方式](https://langfuse.com/docs/evaluation/evaluation-methods/scores-via-sdk)

## 验收记录

已完成：Python 14 项测试通过；最终 `mvn package` 349 项，0 失败、0 错误，8 项外部测试默认跳过。显式真实 Java 导出两轮均为 4 项通过，无跳过；独立代码审查的认证重定向与快照失败拒绝问题已修复，无剩余 P1/P2。

两轮均使用相同冻结前 10 条标签、用户与生产配置，每轮 20 条实际 trace、40 个分数均经 Cloud 读取核验；首轮全精排降级，证据保留。第二轮预热后 6 条精排成功、4 条因现有 10 秒超时降级；两轮均未改变生产超时、语料或权限。评分上传遇到 HTTP 429 时按 Retry-After 有界重试，稳定 score ID 保证重试不重复计数。

| 第二轮实际策略 | 有效/总数 | Hit@5 | MRR@5 |
| --- | --- | --- | --- |
| BM25 | 10/10 | 70% | 0.4950 |
| 当前混合链路（含 4 条降级） | 10/10 | 70% | 0.6250 |

成功精排的配对分母是 6，其他 4 条明确排除；该子集结果不能外推全体。完整原始数据、标签、配置、逐条分数、Cloud 证据和重算命令已保存：

- [第二轮报告](../eval/retrieval/results/retrieval-20261008-171833-1130308a/report.md)
- [首轮降级报告](../eval/retrieval/results/retrieval-20261008-170718-d5497044/report.md)
- [成功精排 Cloud 样例](https://jp.cloud.langfuse.com/project/cmuyeaxqk001oad0eov2lkv26/traces/0202f3320b86e323bac30271219b0cbc)

后端与前端继续运行。本阶段仅新增测试评测入口与离线评分能力，不需要生产重启；正式 100 组指标、TTFT 基线对照和路由准确率尚未重测。
