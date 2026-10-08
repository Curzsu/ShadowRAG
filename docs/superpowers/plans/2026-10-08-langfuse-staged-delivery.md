# ShadowRAG Langfuse 分阶段实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking. 只有用户明确选择代理协作时才使用 superpowers:subagent-driven-development。

**Goal:** 分阶段接入真实问答追踪和离线评测，让 TTFT、路由准确率、Hit@5、MRR@5 都有逐条记录和可重算依据。

**Architecture:** Java 使用 OTel SDK 直接异步导出到 Langfuse Cloud，聊天主体为问答、模型、搜索三种节点；随后按用户要求在搜索下补充 embedding/retrieval/rerank 耗时。离线评测继续使用现有 Python 脚本，共用一个可选上报模块；检索结果通过测试代码调用实际 Java 服务导出。每个阶段独立验收，不一次创建全部框架。

**Tech Stack:** Java 17、Spring Boot 3.4.2、OpenTelemetry Java SDK / OTLP HTTP exporter、Langfuse Python SDK、PowerShell、JUnit / Mockito、Python unittest。

---

日期：2026-10-08。状态：阶段 1、2、5 已完成并验收；阶段 3、4 未开始。阶段 5 为 10 组链路验收，精排成功 6 条、降级 4 条，不代表正式简历指标。依据：[审核后的 SOP](../../research/2026-10-08-langfuse-cloud-integration-sop.md)。

这是阶段任务与验收清单；实际修改代码时逐项落实测试与最小实现，不在规划阶段生成尚未验证的 SDK 调用代码。新增路径是拟定交付路径，不代表文件已经存在。

## 1. 阶段与依赖

| 阶段 | 核心交付 | 到这里可以做什么 | 依赖 |
| --- | --- | --- | --- |
| 1：连接与开关 | 配置加载、OTel provider、小批 Cloud 连通验证 | 确认当前 JP 项目能接收记录，关闭时无上传 | 当前仓库 |
| 2：真实聊天追踪 | 三种节点、模型原生 TTFT、生命周期和跨线程关联 | 看真实问答是否搜索、慢在哪轮、是否保存成功 | 阶段 1 |
| 3：TTFT 对照 | 共用 Python 上报模块、版本快照、原脚本加关联 | 比较强制检索与按需检索的流程 TTFT，查看逐条记录 | 阶段 1、2 |
| 4：路由评测 | 当前策略对齐、逐条 route_correct、路由报告 | 比较工具描述版本，定位错分样本 | 阶段 3 的公共模块与版本快照 |
| 5：检索评测 | Java 实际链路导出、同权限 BM25、Hit/MRR 评分 | 比较 BM25 与实际混合+精排结果 | 阶段 2；用户要求提前实施，评分 REST 写入在阶段 5 内补齐 |

推荐顺序 1 → 2 → 3 → 4 → 5。阶段 4 与 5 在依赖完成后可以交换顺序；这是工作依赖说明，不要求并行代理。

阶段 1 是连接验证，不算业务接入完成。阶段 2 是第一个可用于真实聊天调试的版本。阶段 3～5 分别补足三个评测闭环，可以按需求停在任一已验收阶段。

## 2. 共同边界

- 使用现有 JP 项目与根目录 `.env.langfuse.local`。凭据不重复收集、不进入 Git、前端、命令参数或日志。
- 配置文件通过 scripts/langfuse/run-with-langfuse.ps1 加载，已验证连接；常规启动默认关闭。
- `LANGFUSE_ENABLED` 默认 false；正文默认不采集。开发/小样本评测先全量，不实现采样配置。
- 只新增必要埋点、配置与评测关联。保留现有聊天状态机、事务、权限、工具预算、SSE 和记忆逻辑。
- 上传在后台有界批处理；采集失败不能改变问答结果。评测先保存原始结果，进程结束时限时刷新。
- Python 模块关闭时不要求安装 Langfuse SDK，也不发遥测请求。启用而缺少依赖/配置时给出脱敏诊断，保留本地评测能力，并明确本次没有完成 Cloud 关联。
- 只为新执行的调用建立记录；重传评分关联已有 trace，不为旧报告补造调用过程。
- 阶段 1、2 已用 observations v2 核验 Cloud 的连接与真实问答。代码测试通过与 Cloud 实际展示分别记录，不能以其中一个代替另一个。
- 仓库已有其他未提交改动；逐阶段实施前检查状态，不覆盖用户工作。提交/推送按照用户的交付指令执行，不自动合并其他改动。

## 3. 阶段 1：连接、配置与关闭开关

**交付边界：** 暂不修改聊天、Agent 或检索流程。

**Files：**

- 修改：`pom.xml`、`src/main/resources/application.yml`。
- 新增：`src/main/java/com/yizhaoqi/smartpai/config/LangfuseProperties.java`。
- 新增：`src/main/java/com/yizhaoqi/smartpai/config/LangfuseConfiguration.java`。
- 新增：`src/main/java/com/yizhaoqi/smartpai/observability/LangfuseTracing.java`。
- 新增：`scripts/langfuse/run-with-langfuse.ps1`。
- 测试：`src/test/java/com/yizhaoqi/smartpai/observability/LangfuseConfigurationTest.java`、`LangfuseConnectionSmokeTest.java`（同目录）。
- 测试：`scripts/langfuse/test-run-with-langfuse.ps1`，使用临时虚构配置和子进程读取结果，不输出真实密钥。

### Task 1.1：配置加载

- [x] 启动脚本只读取 ENABLED、BASE_URL、PUBLIC_KEY、SECRET_KEY、ENVIRONMENT、CAPTURE_CONTENT 六个允许键；不执行文件内容。
- [x] 处理 BOM、空行、注释与成对引号；已有进程环境变量优先。启动子进程后恢复脚本自身修改的环境，避免污染后续操作。
- [x] Java 使用同一组环境变量；默认关闭、默认不采正文。开启但缺少凭据时退化为关闭，并给出不含值的诊断。
- [x] 脚本验证覆盖引号/BOM、外部环境优先、未知键忽略、文件内文本不执行、子进程结束后环境恢复。

### Task 1.2：SDK 与导出

- [x] 固定同一 OTel BOM 下的 Java 17 兼容 API、SDK、exporter 与测试依赖，不引入自动 Agent 或 Collector。
- [x] Spring 管理 provider；一个追踪辅助类收口常用属性与创建节点，关闭时复用 OTel 原生无操作 tracer。
- [x] HTTP/protobuf 端点为 BASE_URL + `/api/public/otel/v1/traces`；内存构造 Basic Auth，设置 `x-langfuse-ingestion-version: 4`。
- [x] 使用 SDK 有界批处理默认队列，设置 3 秒导出超时；不增加同步请求上传、通用适配器或丢弃监控框架。
- [x] 单元验证关闭/缺配置不出网；mock OTLP 验证路径、协议与认证头，401/连接失败不抛入业务。测试输出不得打印头值。

### Task 1.3：实际 Cloud 冒烟

- [x] 连接测试默认跳过，只有显式开关才使用本地配置向 JP 项目发送一条 `integration.smoke` 记录。
- [x] 明确它是连通性测试，不标为真实模型调用，不填写模型 TTFT 或业务评分。
- [x] Cloud 中确认项目、environment、记录名称与时间；保存 trace 链接及验证结论。

**验收：** 配置加载测试、provider/mock 导出测试通过；Cloud 可见冒烟记录；关闭后零遥测请求。认证或网络未通时只记录“本地通过 / Cloud 待验证”。

**验证入口：**

```powershell
powershell -NoProfile -File scripts/langfuse/test-run-with-langfuse.ps1
mvn "-Dtest=LangfuseConfigurationTest" test
```

连接测试通过启动脚本注入配置后执行 `LangfuseConnectionSmokeTest`；开关与执行方式在该测试实现时写入运行说明，默认 Maven 测试不得向 Cloud 上传。

## 4. 阶段 2：真实问答追踪与模型 TTFT

**Files：**

- 修改：`src/main/java/com/yizhaoqi/smartpai/service/chat/ChatStreamService.java`、`ChatRequestContext.java`。
- 修改：`src/main/java/com/yizhaoqi/smartpai/service/AgentLoopService.java`、`KnowledgeBaseSearchTool.java`、`HybridSearchService.java`。
- 扩展：阶段 1 的 `LangfuseTracing.java`。
- 测试：现有 `service/chat/ChatStreamServiceTest.java`、`service/AgentLoopServiceTest.java`、`service/KnowledgeBaseSearchToolTest.java`、`service/HybridSearchServiceLoggingTest.java`（均位于 `src/test/java/com/yizhaoqi/smartpai/`）。
- 新增测试：`src/test/java/com/yizhaoqi/smartpai/observability/LangfuseTracingTest.java`。

### Task 2.1：根节点与异步生命周期

- [x] 受理成功后创建 `chat.request`，请求对象持有根 Context；生成线程激活/关闭本线程 Scope。
- [x] 通常在已有 `Stream.remove` 清理路径结束根节点；关机等待耗尽时记录提交未知并提前收尾，独立一次保护避免重复结束。发送/清理线程直接更新保存的 span，不共享其他线程创建的 Scope。
- [x] 记录终态、已有 firstChunkMs/elapsedMs、durableCommitted、networkTerminalDelivered。沿用计时器，不创建第二套聊天计时。
- [x] 用现有夹具验证正常、取消、超时、保存失败、提交成功但通知丢失；两个并发请求不串链、线程 Context 恢复、根节点只结束一次。
- [x] 现有直接构造服务的测试需要无操作追踪路径；不强迫未启用功能的代码手工配置 SDK。

### Task 2.2：模型和搜索节点

- [x] 每轮 `streamWithTools` 创建 `llm.round`；映射为 generation，记录轮次、模型参数、工具名称/数量与实际首轮路由。
- [x] generation 设置真实开始时间。仅首个非空模型 content 到来时设置 `langfuse.observation.completion_start_time`，使用 ISO 8601 时间；工具参数、reasoning、合成说明不算首字。
- [x] Cloud 原生 TTFT 由 generation 开始时间与 completion_start_time 计算；没有 content 的工具调用轮次不编造答案 TTFT。
- [x] 实际搜索创建 `tool.knowledge_search`，记录 callId、最终有序 top 5 chunk ID/分数、结果数与受控错误码。预算拦截未执行搜索时不伪装实际搜索。
- [x] 在已有精排分支补 `rerank_status=success/skipped/fallback`，跟随当前搜索节点。
- [x] 用户追加要求：在 `HybridSearchService` 实际执行步骤下创建 embedding/retrieval/rerank 子 span，通过 `HybridSearchTracingTest` 与真实 Cloud 聊天验证计时、上下文恢复、跳过及异常降级；未执行步骤不创建节点。
- [x] 用内存 exporter 验证父子关系、空流/工具参数先到、精排失败降级与默认无正文。只采集脱敏受控字段，不上传供应商原始异常。

### Task 2.3：聊天联调和回退

- [x] 验证一条直接回答、一条检索后回答在 Cloud 的树形结构与原生 TTFT 展示。
- [x] 注入导出失败后问答仍完成、保存状态正确；应用退出时先收尾活动问答，再限时刷新/关闭 provider。
- [x] 使用开关关闭并重启，验证原聊天测试与行为一致。

**验收：** 真实聊天能定位模型与检索耗时，生命周期正确、采集失败隔离。到此即可作为日常调试工具使用；尚不代表简历中的指标已经重新评测。

```powershell
mvn "-Dtest=LangfuseTracingTest,ChatStreamServiceTest,AgentLoopServiceTest,KnowledgeBaseSearchToolTest,HybridSearchServiceLoggingTest" test
```

## 5. 阶段 3：TTFT 对照与共用评测上报

**Files：**

- 新增：`scripts/langfuse/eval_reporting.py`、`test_eval_reporting.py`、`requirements-eval.txt`。
- 新增：`src/test/java/com/yizhaoqi/smartpai/observability/EvaluationPolicyExportTest.java`，导出当前配置和 Java 工具定义，默认不运行外部调用。
- 修改：`docs/eval/agent_routing/ttft_benchmark.py`、`test_ttft_benchmark.py`。
- 更新运行说明：`docs/research/2026-10-08-langfuse-cloud-integration-sop.md`。

### Task 3.1：冻结评测策略与公共上报

- [ ] 导出当前 Java Tool 定义与基础 System Prompt，保存内容哈希、代码版本、模型参数。脚本可显式读取导出的 Tool 文件；旧默认用法保持可运行，并明确标识 legacy 策略。
- [ ] 上报模块可选初始化 SDK，支持真实执行的 trace/generation、首字时间、附加已有 trace 的 score 与退出刷新。不新增统一 runner、数据库或队列。
- [ ] 每次实际尝试有 run_id/sample_id/strategy/attempt；结果保存 traceId、observationId 和上报状态。
- [ ] score ID 由上述标识与 metric 稳定生成；重传只针对已存在 trace。SDK 接受排队不等于 Cloud 已收到，状态区分关闭、已排队、失败、Cloud 已核验。
- [ ] 模块测试用假的 SDK/export 调用验证关联和稳定 ID，不在单元测试中依赖真实账号。关闭时验证不导入必需的 SDK、不出网。

### Task 3.2：复用现有 TTFT 计时

- [ ] 保留 `perf_counter` 计时：离线策略开始 → 首个非空模型 content。强制检索包括真实检索；异步上报与评分放在计时结束后。
- [ ] 为模型 generation 标记首字时间以支持原生模型 TTFT；流程耗时另存 `benchmark_ttft_ms` score，不用模型原生 TTFT替换它。
- [ ] 增加显式重复次数参数、attempt 字段；预热不进入统计，交替两组顺序，失败与错路由保留。
- [ ] 现有 agentic 分支只对 DIRECT 样本测首轮直接回答，误触发工具的样本记录失败/错路由；不宣称已运行完整多轮生产 Agent。
- [ ] 核对强制检索使用的 HTTP 入口实际权限与精排分支，报告记录检索配置。不能因为 URL 包含 hybrid 就认定与 Java 聊天链路相同。
- [ ] 扩展已有测试：首段为空、先工具参数/说明后答案、两组计时边界、重复配对、失败保留、评分在停止计时之后。

### Task 3.3：小批验收

- [ ] 先用 10 条已复核的 DIRECT 样本联调；固定两组模型/参数/基础 Prompt，记录策略差异。
- [ ] 保存 metadata、逐条 raw results、含 P50/P95 与配对分母的报告；已有 JSON/JSONL 输出补字段，不重建输出平台。
- [ ] 从本地结果重算报告，抽查 Cloud 模型 TTFT 与流程 TTFT score，能追到同一条样本。

**验收：** 得到新运行的性能对照与 Cloud 关联。旧 34.34% 不能直接贴上“Langfuse 实测”；新结果按本次运行填写。

```powershell
python -m unittest discover -s scripts/langfuse -p test_eval_reporting.py
python -m unittest discover -s docs/eval/agent_routing -p test_ttft_benchmark.py
mvn "-Dtest=EvaluationPolicyExportTest" test
```

## 6. 阶段 4：路由准确率与错分定位

**Files：**

- 修改：`docs/eval/agent_routing/route_predict.py`、`route_eval.py`。
- 测试：`docs/eval/agent_routing/test_route_predict.py`、`test_route_eval.py`。
- 复用：阶段 3 的策略快照和 `eval_reporting.py`。

### Task 4.1：当前策略与标签

- [x] 保存当前生产 Java 配置、Tool/Prompt、模型参数与代码哈希；本阶段直接从真实 Bean 导出策略，避免历史 Python 定义与生产混用。
- [x] 2026-10-06 的策略已转为事实性问答优先检索，本次生成 10 条当前候选标签，明确 pending_human_review；完整人工复核仍待后续完成。
- [x] 区分开发集与冻结测试集；本阶段仅冻结 10 条联调样本，不调 Tool Description、不修改线上策略。

### Task 4.2：调用关联和逐条评分

- [x] 每次实际模型尝试记录 generation 与预测路由；重试保留尝试编号，最终评分依据明确。
- [x] 上传 `route_correct`（0/1）到对应已执行 trace；记录 expectedRoute、predictedRoute、错误与版本。
- [x] 复用现有评测计算整体准确率、DIRECT 子集准确率和混淆矩阵，失败计入整体分母且不伪造成功路由评分。
- [x] 测试使用已知标签与预测，验证正确/错误/缺预测、重试选择、未复核报告标识与评分关联。

### Task 4.3：小样本与报告

- [x] 先验证 10 条样本；Cloud 已回读核验 10 条 trace 和评分，可筛 route_correct=0，本地报告可重算。
- [x] 本次仅候选集联调，不报告正式准确率；只有冻结标签已复核且实际跑完目标测试集，才报告正式准确率，原简历数字不作为验收目标。

**验收：** 每条预测可关联真实调用，准确率可复算，当前/历史策略不混用。

```powershell
python -m unittest discover -s docs/eval/agent_routing -p test_route_predict.py
python -m unittest discover -s docs/eval/agent_routing -p test_route_eval.py
```

## 7. 阶段 5：实际检索链路与 Hit@5 / MRR@5

**为什么单独做：** 旧 Python 脚本自行执行 BM25/KNN/RRF，没有调用 Java Cross-Encoder 精排，不能直接代表当前最终链路。

**Files：**

- 新增：`src/test/java/com/yizhaoqi/smartpai/observability/RetrievalEvaluationExportTest.java`。
- 修改：`docs/interview/eval_retrieval.py`。
- 新增：`docs/interview/test_eval_retrieval.py`。
- 复用：阶段 2 的追踪。阶段 3 未实施，评分 REST 写入能力已在 `eval_retrieval.py` 中最小实现，默认关闭；不顺带交付 TTFT 或路由评测。

### Task 5.1：Java 测试入口导出实际结果

- [x] 使用明确的测试用户与冻结语料，调用 `HybridSearchService.searchWithPermission(query, userId, 10)` 得到实际混合+精排最终有序结果。
- [x] BM25 基线在测试代码中复用当前服务的权限用户/组织解析和 `textOnlySearchWithPermission`。可使用 Spring 测试的 ReflectionTestUtils 调用现有私有方法，不增加生产评测接口，不通过屏蔽向量调用伪装基线。
- [x] 两组使用相同用户可见集合、索引/分析器与输出 topK。实际链路先按聊天 topK=10 执行，再取前 5 评分，不能把调用参数改为 5 后声称与原候选规模一致。
- [x] 每条样本/策略在 Java 实际执行前创建 trace，输出 run/sample/strategy、最终有序 chunk key、分数、精排状态、错误与 traceId。Python 附加 score 到返回的 trace，不创建重复的虚构执行树。
- [x] 外部 ES/数据库/向量/精排依赖的导出运行需显式启用，普通单元测试默认不访问它们。用服务 mock 与固定有序结果验证导出格式和 topK，不把外部服务稳定性当单元测试条件。

### Task 5.2：评分和原始结果

- [x] Python 增加读取 Java 导出结果的模式，保留旧脚本模式但明确标记 legacy RRF。
- [x] 相关性标签统一用 `fileMd5:chunkId` key，与 Java 导出一致。保存检索顺序、相关 key 和失败记录，而不只保存最终均值。
- [x] 复用公式：前 5 至少一个相关结果 → Hit=1；首个相关结果在第 r 位 → RR=1/r；未命中 → 0。全体逐条 RR 的均值才是 MRR@5。
- [x] 上报 `hit_at_5`、`mrr_at_5` 到实际 Java trace。失败统计与有效样本分母同时报告，不静默把搜索异常当作正常无命中。
- [x] 固定夹具：首位命中为 (1,1)、第三位命中为 (1,1/3)、相关结果仅在第六位为 (0,0)、无相关结果为 (0,0)。

### Task 5.3：小样本链路验收

- [x] 先跑 10 组有相关 chunk 标注的样本，比较 BM25 与实际混合+精排。
- [x] 抽查权限集合、结果顺序与 rerank_status；若精排配置关闭/降级，报告实际状态，不标为精排成功。
- [x] 本地重算均值与报告一致，Cloud 分数能追到实际 Java 调用。
- [x] 首版只完成上述两种策略。KNN/RRF 分拆消融不作为阶段 5 前置条件。

**验收：** Hit@5/MRR@5 来自当前真实链路和人工相关性标签，并且可以逐条复查。

```powershell
mvn "-Dtest=RetrievalEvaluationExportTest" test
python -m unittest discover -s docs/interview -p test_eval_retrieval.py
```

第一条默认只运行不出网的格式测试；实际导出在实现后通过显式开关和冻结数据运行。

## 8. 正式指标定稿：按需在对应阶段后执行

这一项是指标验收，不再扩建接入架构。某个指标的工程阶段完成后即可单独启动正式评测，不必等其他所有指标。

- [ ] 冻结目标样本数量、人工标签、语料/索引版本、模型/参数、Prompt/Tool、检索与精排配置。
- [ ] 正式 TTFT 每条至少重复 3 次，记录预热、顺序、失败和配对分母；明确 P50/P95 是按尝试还是按样本聚合。
- [ ] 保存本地版本配置、原始结果、报告与 trace 链接；本地结果可脱离 Cloud 历史访问窗口重算。
- [ ] 数据不足 100 组/200 条就按实际数量写，未复核不宣称独立人工评测；百分比提升和百分点区分。
- [ ] 简历注明 Langfuse 用于追踪与评分管理。质量标签来自评测集，流程 TTFT 来自明确计时边界，不宣称平台自动判定全部指标。

## 9. 每阶段的交接与后置事项

每阶段记录四项：已完成的 Task、运行过的测试与结果、Cloud trace/score 抽查链接、未满足的外部条件。当前阶段验收失败先定位，不自动扩大到下个阶段。文档更新只勾选实际完成项目；计划中的命令不代表已经运行通过。

阶段 1～2 的回退是关闭 LANGFUSE_ENABLED 并重启；阶段 3～5 关闭上报仍能保留原评测输出。阶段结束运行受影响测试和仓库要求的后端检查；真实 Cloud 冒烟独立验证。正式大批模型调用与人工标签复核不阻塞前两阶段的聊天接入验收。

首版后置：token/成本核算、摘要追踪、裁剪指标、采样、完整 Experiments 页面、通用评测调度器、自动归档。精排独立 span 已按用户追加要求完成；其他事项出现明确使用需求再单独规划。

