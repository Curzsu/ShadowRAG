# Langfuse 检索实验接入

用户批准的范围：读取已导入的 retrieval-eval-50，运行实际 Java BM25 和混合检索＋精排，关联 Dataset Item、实验、trace 和 Hit@5 / MRR@5。先以样本编号前 10 条验收；不扩充或修改人工标签。

Ruling: 使用官方 OTel experiment 属性而非旧 dataset-run-items 写接口 — 现有 Java OTel 可以直接复用，旧接口已弃用 — 属性错误会导致实验无法呈现，因此必须回读云端验证。

Ruling: 实验上传已有数据集的问题、相关 chunk ID，以及实际结果 ID / 分数 / 状态；不上传检索文档正文 — 用户已授权导入这些问题和标签 — 普通聊天仍不采集正文。

Ruling: 保留现有 10 秒精排超时及降级行为 — 此阶段只增加实验关联 — 超时会影响精排成功样本数量，报告必须区分成功与降级。

## 执行方式

在项目根目录运行：

```powershell
./scripts/langfuse/run-retrieval-experiment.ps1 -Username Lukesu -Limit 10
```

账号需显式填写，两个策略共享该账号的权限。`-Limit 50` 可执行全量人工标签集，每次新建两组实验，避免覆盖历史运行。凭据复用私有 `.env.langfuse.local`，不进入命令行或结果文件。

脚本先读取指定时间点版本的 Cloud Dataset，按数值 sample_id 排序选样，然后调用现有 Java 评测链路。保持权限、索引前后校验，最终根据实际排名计算 Hit@5、MRR@5 并上传到根 observation。实验输出只含 chunk ID、分数及状态。

在 Langfuse 打开 **Datasets → retrieval-eval-50 → Experiments**，找到同一运行前缀的 `-bm25` 和 `-hybrid_rerank` 两组。对比 aggregate score，再进入单个样本查看真实返回排名和调用链。混合实验的整体分数包含降级结果；严格精排对照应使用本地 report.json 的 paired 成功子集，不将成功子集解释为全量结果。

## 验证记录

- Python 新测试先因缺少数据集读取模块失败，再通过；新增根 observation 分数上传测试通过。
- Java 新测试先因缺少实验参数重载编译失败，再通过；验证云端 item ID、独立 trace、根 ID、子节点关联、不上传文档正文及上下文恢复。
- 云端读取确认 50 条 ACTIVE 样本，冻结前 10 条；每条保留真实 DatasetItem.id。
- 首次启动因连续加载环境变量留下空值而失败，未执行检索；修复加载器空值判断，并通过空值重新加载、非空覆盖值保留的回归测试。
- 独立审查要求评分前检查云端样本身份：新增错误 item ID 拒绝测试先失败，补充 dataset / item / version 与根 observation 校验后通过。Python 评分 15 项、实验及配置加载 4 项通过。
- 审查补充：回读允许实际跳过精排的降级调用链；分批读取分数，避免全 50 条生成 200 个分数时受单页 100 个限制。相应测试先失败，修复后通过；修复后完整云端核验再次通过。
- 完整 `mvn package`：350 项，0 失败、0 错误，8 项外部测试默认跳过。真实实验验收另以显式开关执行，5 项全部通过。
- 实际执行 `retrieval-exp-20261008-180248-9d8c0c85`：真实 Java 验收 5 项通过，无跳过；两个实验各 10 条，40 个根 observation 分数已逐项回读核验，输入、标签、实际排名及子节点亦已核验。

| 本轮全 10 条实际链路 | Hit@5 | MRR@5 | 精排情况 |
| --- | --- | --- | --- |
| BM25 | 70% | 0.495 | 不适用 |
| 混合检索链路 | 60% | 0.600 | 2 成功、8 超时降级 |

本轮严格成功精排配对子集只有 2 条，不具备代表性。以上指标是 10 条联调结果，不替代简历上的 100 组正式评测。

云端实验名：

- `retrieval-exp-20261008-180248-9d8c0c85-bm25`
- `retrieval-exp-20261008-180248-9d8c0c85-hybrid_rerank`

完整本地结果：[运行目录](../eval/retrieval/results/retrieval-exp-20261008-180248-9d8c0c85/report.json)，[云端验收证据](../eval/retrieval/results/retrieval-exp-20261008-180248-9d8c0c85/cloud-verification.json)。

官方契约：[OTel 实验关联](https://langfuse.com/integrations/native/opentelemetry/experiments)。
