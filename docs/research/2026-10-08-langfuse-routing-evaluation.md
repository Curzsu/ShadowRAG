# 当前策略路由评测

范围：对应阶段 4，复用 Java 生产首轮消息构造、DeepSeekClient 和 KnowledgeBaseSearchTool，不执行知识检索和后续回答。历史 Python 请求构造及历史标签保留。

Ruling: 当前策略为事实性问答优先检索，历史通用事实问题 DIRECT 标签不适用。本轮选取 10 条原有样本，生成当前策略候选标签，保留 historicalExpectedRoute。用户无法定位已复核文件，故全部标记 pending_human_review。代价：此次准确率只能证明链路可运行，不能支持正式简历指标。

Ruling: 当前阶段先交付 10 条联调，不改 Prompt、不做基于测试集的调优，不运行完整 120/200 条验收。样本分布 7 SEARCH、3 DIRECT，因此需同时展示 DIRECT 子集结果和混淆矩阵。

启动：在项目根目录运行 `./scripts/langfuse/run-routing-evaluation.ps1`。已确认的当前标签文件可通过 `-Dataset 路径` 指定，须包含同一组 smoke 样本 ID、expectedRoute、annotationStatus 等原有字段。

每次创建独立目录：dataset.jsonl 和来源校验、policy.json（当前 Prompt/Tool/参数/代码哈希）、predictions.jsonl（真实 trace/span 与每次调用）、report.json/md、scores.json、upload-status.json、cloud-verification.json。

评分：成功预测上传 route_correct=0/1 到实际评测根节点；失败不伪造路由评分，独立列出且计入整体准确率分母。正常首轮一次调用，默认不重试。辅助导出器支持有界重试，保留全部 generation，并明确 selectedAttempt。

隐私：云端记录样本 ID、标签、预测、策略与查询哈希；不记录问题正文、工具参数、回答正文或密钥。通过本地报告中的样本 ID 定位错分问题。

验证记录：真实运行 `routing-eval-20261008-smoke-01`，10 条 trace、10 次 generation、10 个 route_correct 已经 Cloud 回读核对。候选标签下正确 9/10，DIRECT 子集 3/3。SEARCH 行预测 SEARCH=6、DIRECT=1；DIRECT 行 SEARCH=0、DIRECT=3。无调用失败。

错分候选：test-direct-056，多轮问题“那精确率和召回率应该怎么看？”，当前候选标签 SEARCH，模型 DIRECT；实际 trace `893fc44cd73e610c9c4b2ba3ce3345f0`。先人工核对问题与历史、当前策略，不能用本测试样本反复调 Prompt 后声称独立测试效果。

审查修复：Markdown 补 DIRECT 子集和混淆矩阵；Cloud 入库可见性使用最多 6 次、间隔 5 秒回读；10 条单例每次最多 90 秒，整体测试预算 1200 秒。原失败是异步入库尚未可见，再回读同一批 trace/score 已通过；未补造数据或额外模型调用。

最终检查：Java `mvn package` 成功，353 个测试、0 失败、0 错误、9 跳过（opt-in 联调默认跳过）；真实路由联调另外运行，3 个测试全部通过。Python 路由历史回归 18 个、新增评分测试 4 个全部通过。最后 Cloud 回读再次验证 annotationStatus、selectedAttempt、父子节点、策略与消息哈希、NUMERIC 分数与评分对象；10 条 trace / 10 次 generation / 10 分数匹配。
