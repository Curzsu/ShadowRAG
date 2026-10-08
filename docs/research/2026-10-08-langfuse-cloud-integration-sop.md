# ShadowRAG 接入 Langfuse Cloud SOP

日期：2026-10-08。版本：审核后的精简版。阶段 1、2 已完成，见[实施记录](2026-10-08-langfuse-stage-1-2-progress.md)；阶段 5 已实现实际检索导出与 Hit@5/MRR@5 评分，完成 10 组小样本 Cloud 验收，见[检索评测记录](2026-10-08-langfuse-retrieval-evaluation.md)。阶段 3、4 尚未实现。

## 当前版本的启动方式

在项目根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/langfuse/run-with-langfuse.ps1
```

脚本读取 Git 忽略的 `.env.langfuse.local`，现有进程环境变量优先；只为启动过程注入配置，结束后恢复环境。需要本机既有数据库、Redis、ES、向量与精排服务可用。常规 `mvn spring-boot:run` 不加载该私有文件，默认关闭 Langfuse。回退可将 `LANGFUSE_ENABLED=false` 后重启。

当前只上传关联 ID、耗时、状态、模型参数、版本哈希与检索结果 ID/分数。`LANGFUSE_CAPTURE_CONTENT` 暂为保留配置，即使设为 true，这两个阶段也不上传正文。Cloud 使用已有 HTTPS_PROXY/HTTP_PROXY；本机测试端点绕过代理。启用时请查看真实新请求的 Tracing，而非把连接冒烟记录当作问答。

原生 TTFT 对应每轮模型首个非空 content；检索轮只有工具调用而没有 content 时留空。已有 `server_first_chunk_ms` 是后端首次发送耗时，可能包含中间说明。简历中的流程 TTFT 降幅、路由准确率、Hit@5/MRR@5 仍需后续阶段的独立评测。

## 1. 审核结论：保留闭环，收缩首版

目标：能查看一次真实问答的模型/检索调用；能把离线 TTFT、Hit@5、MRR@5、路由评分关联到逐条样本；原始结果本地可重算。

推荐路径保持为 Java OpenTelemetry → Langfuse Cloud，评测复用现有 Python 脚本。以下要求不再作为首版前置条件：

| 原要求 | 审核后的处理 | 原因 |
| --- | --- | --- |
| 采集接口 + 自定义无操作实现 + OTel 实现 | 一个小的追踪辅助类，关闭时使用 OTel 原生无操作 tracer | 当前只有一个后端，不需要通用可观测性框架 |
| 精排独立 span，采集上下文裁剪前后 token | 初版保留三种主体节点；用户要求拆分搜索耗时后增加 embedding/retrieval/rerank，裁剪 token 仍后置 | 能定位搜索内部瓶颈，精排状态仍作为搜索属性 |
| 模型、服务端和新 HTTP 客户端三套首字埋点 | 复用已有服务端首段日志和离线 TTFT 计时；首版只增加模型首字时间 | 避免为了计时修改前端或 SSE 协议 |
| 新增统一实验运行入口并立即采用完整 Experiments 结构 | 现有脚本加一个共用的 Langfuse 上报辅助模块；先用 run/sample/strategy 标识和 scores | 逐条关联与基线比较已经可用；标准实验页面分组后续需要时增加 |
| 强制同时完成 BM25、KNN、RRF、RRF+精排四策略消融 | 首版比较 BM25 与实际混合+精排链路 | 足够验证简历所述基线改善；拆分贡献是下一步分析 |
| 专门采样配置、遥测丢弃计数、详细队列调优 | 本地开发和评测先全量采集，使用 SDK 有界批处理及安全诊断 | 当前免费额度与样本规模不需要自建采样/监控系统 |
| Cloud 追踪导出、完整语料快照和自动归档 | 本地保留原始结果、运行配置、版本标识和 trace 链接 | 足够解释并重算指标，自动归档暂无实际需求 |
| 供应商 usage 协议、成本核算、异步摘要追踪 | 移出本次交付 | 不影响 TTFT、检索和路由指标；usage 帧兼容需要独立改造 |

跨线程关联、准确结束 span、采集失败不影响聊天、关闭开关和敏感内容控制继续保留。这些关系到接入正确性，不能用精简范围来省略。

交付分为聊天追踪与评测关联两部分，执行时拆为五个阶段：连接配置、真实聊天、TTFT 对照、路由评测、检索评测。详见 [分阶段任务与验收清单](../superpowers/plans/2026-10-08-langfuse-staged-delivery.md)。检索评测与生产链路对齐可能是主要工时；按阶段记录实际工作量，不以固定工期或指标提升作为接入承诺。

## 2. 配置来源和 Cloud 连接

已选项目 shadowrag-dev，JP 地址 https://jp.cloud.langfuse.com。Public Key、Secret Key 和开关已保存在根目录 .env.langfuse.local，该文件已核对被 Git 忽略。凭据不进入 SOP、前端或诊断日志。

这个文件目前只是配置存储，Spring Boot 和 Python 都不会因为它存在而自动加载。实施时增加一个薄的启动脚本：只解析允许的 LANGFUSE_* 键，将值注入当前进程环境，再启动后端或评测程序。不执行文件中的文本、不把密钥放入命令参数、不修改机器级环境。已存在的进程环境变量优先。启动时验证文件引号和编码处理正确。

Java 从环境变量读取以下配置，Python 上报辅助模块读取同一组配置：

| 变量 | 用法 |
| --- | --- |
| LANGFUSE_ENABLED | 默认 false；启用后缺少凭据时给出不含凭据的告警，关闭采集 |
| LANGFUSE_BASE_URL | 区域地址，不包含 API 路径 |
| LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY | 仅在后端或离线脚本使用 |
| LANGFUSE_ENVIRONMENT | 聊天为 development，评测为 experiment |
| LANGFUSE_CAPTURE_CONTENT | 默认 false；阶段 1、2 保留此键但不实现正文采集 |

本地文件现存的 LANGFUSE_SAMPLE_RATE=1.0 与全量采集一致；首版不新增采样实现或配置类字段，启动脚本也无需将此键注入。正式指标评测始终全量采集。

pom.xml 引入同一个 OTel BOM 管理的 API、SDK 和 OTLP exporter，实施时固定并验证 Java 17 兼容版本。配置一个 Spring 管理的 tracer/provider，关闭时使用 SDK 已有的无操作能力。业务埋点由一个小辅助类收口，不增加接口、多实现或厂商适配器。

导出使用 HTTP/protobuf，完整端点为区域地址加 /api/public/otel/v1/traces，在内存生成 Basic Auth，附带 x-langfuse-ingestion-version: 4。使用 SDK 的有界异步批处理与默认队列/批次设置，只设置有限导出超时（首版 3 秒）；正常请求中不等待远程上传或刷新。失败不改变业务返回，诊断中不打印凭据或正文。应用关闭时先结束聊天埋点，再限时刷新并关闭 provider。

不安装自动 Java Agent，也不部署 Collector。连接与属性映射遵循 [Langfuse OTel 文档](https://langfuse.com/integrations/native/opentelemetry)；无操作 tracer、批处理和关闭行为使用 [OTel Java SDK](https://opentelemetry.io/docs/languages/java/sdk/) 的已有能力。

## 3. 聊天主体与搜索内部计时

    chat.request
    ├── llm.round
    ├── tool.knowledge_search
    └── llm.round

2026-10-08 经用户要求补充搜索内部计时，新请求的搜索节点可展开为：

```text
tool.knowledge_search
├─ embedding
├─ retrieval
└─ rerank
```

| 子节点 | 计时边界 |
| --- | --- |
| embedding | 查询向量客户端调用及返回向量转换，包含本机/远端服务的等待时间 |
| retrieval | 权限查询构造、KNN/BM25 调用和 Java RRF 融合；不含向量生成与精排 |
| rerank | 候选文本准备、Cross-Encoder 客户端调用和结果重排，包含网络/服务等待；不等同于服务端纯推理时间 |

精排关闭或召回为空时不创建 rerank 节点，工具属性仍显示 skipped。混合召回失败后执行 BM25 会出现两个 retrieval 节点，分别记录失败的 hybrid_rrf 和实际 bm25_fallback 尝试。文件名补充、权限用户解析与工具输出格式化等开销仍属于外层工具总耗时，子节点耗时不要求精确加总为工具总耗时。只有真实搜索工具或已有追踪父节点存在时创建子节点；关闭功能不创建独立遥测根节点。

异常仅记录受控错误码，所有子节点均在 finally 结束且恢复 Scope；不上传查询、候选正文、向量值或供应商异常。旧记录没有这些起止时间，无法准确补拆，需重新发起请求查看。

| 位置 | 首版必需记录 |
| --- | --- |
| service/chat/ChatStreamService.java：受理与 Stream.remove | requestId、会话 UUID、实际终态、已有 firstChunkMs/elapsedMs、durableCommitted、networkTerminalDelivered |
| service/AgentLoopService.java：每次 streamWithTools | roundId、实际模型与参数、模型首字耗时、工具名称/数量、首轮 SEARCH/DIRECT、预算收尾原因 |
| service/KnowledgeBaseSearchTool.java：实际搜索 | callId、返回数、最终 top 5 的有序 chunk ID/分数、工具错误码 |
| service/HybridSearchService.java：已有精排/降级分支 | 给当前搜索节点补充 rerank_status=success/skipped/fallback，不建立独立 span |

根节点在请求受理后开始，在已有 remove 的一次清理保护下结束。复用原有状态、计时和清理逻辑；不新增聊天状态机、不调整事务边界。模型返回不代表问答已保存成功，COMPLETING 期间的断线也不能覆盖提交结果。

关机沿用原有 10 秒提交等待；等待耗尽或被中断时，先结束遥测根节点，记录 `state=COMPLETING`、`shutdown_unresolved=true`、`durable_commit_outcome=unknown`，不填虚假的提交成败。数据库事务继续持有自己的结果，之后也不会重复结束已关闭的 span。

将根 Context 保存到请求对象，生成任务在线程内开启/关闭 Scope，使每轮模型和搜索成为其子节点；发送/清理回调直接更新并结束保存的根 span。只在需要创建子节点的线程激活 Context，不在所有定时器和发送任务中新增 Scope。Scope 不跨线程共享，线程复用时不能串链。

辅助类统一写入 session、environment、应用版本和必要关联字段；子节点需要筛选的字段直接复制，不引入 Baggage 框架，也不向模型请求注入身份/评测字段。Prompt/Tool 哈希在版本确定后计算并复用，不在每个 chunk 回调中计算。

正文默认不上传，保留 requestId、会话 UUID、chunk ID、评分和耗时即可定位到本地记录。凭据、原始用户名、完整文档、reasoning/thought_signature 和供应商原始异常文本不采集。

## 4. 保留现有计时口径

- 模型原生 TTFT：某轮模型请求开始到首个非空模型 content。模型节点映射为 generation，使用真实开始时间，在首个 content 到来时设置 ISO 8601 的 langfuse.observation.completion_start_time，由 Langfuse 计算 TTFT；不另建同义 score。排除工具参数、reasoning 和系统合成的“部分完成”文字；未知时留空。
- server_first_chunk_ms：复用已有 firstChunkAt - startedAt。表示后端首次发送耗时，首段可能是中间说明；不称为浏览器端 TTFT。
- benchmark_ttft_ms：复用 ttft_benchmark.py 从离线流程开始到首个非空模型 content 的计时，强制检索组包含真实检索。首版不新增浏览器/聊天 HTTP 的计时模式。

根 span 总耗时不能代替 TTFT。新报告记录计时定义；历史 34.34% 不改名为 Langfuse 或浏览器的新实测值。正式对照使用相同模型、基础 Prompt、参数、样本与并发，保留两种策略的必要差异；上传和评分放在计时结束之后。

## 5. 直接给现有评测脚本加关联

增加一个小的共用 Python 模块，负责可选的 Langfuse SDK 初始化、真实调用追踪和评分上传。现有脚本仍负责运行与计算，默认不启用上报时保持当前用法；不新增通用实验调度器、任务队列或业务 HTTP 接口。SDK 依赖仅在启用 Langfuse 时必需。

每条样本的每种策略/每次重复使用独立 trace，在实际执行前开始，完成后结束。通过官方 Python SDK 创建模型 generation/步骤记录；上报失败仍先保存本地结果，遥测数据收集不承诺零开销。必要字段为 run_id、sample_id、strategy、attempt、版本与模型参数，结果保存 traceId 和 observationId。运行版本和基础参数放在各 trace 上，便于在 Cloud 中筛选。

首版可以在 Cloud 的追踪/评分视图筛选运行，本地脚本负责汇总和对照；不声称已经拥有标准 Experiments 页面。如后续需要该页面的侧边比较，再采用官方实验接口。

| 指标 | 复用与必要修正 | 逐条 score |
| --- | --- | --- |
| Hit@5 / MRR@5 | 复用 docs/interview/eval_retrieval.py 的标签与公式；必须接实际 Java 混合+精排结果，先比较 BM25 与最终链路 | hit_at_5（0/1）、mrr_at_5（排名倒数/0） |
| 路由准确率 | 复用 route_predict.py / route_eval.py；固定本次 Prompt/Tool，并复核预期路由 | route_correct（0/1） |
| TTFT | 复用 ttft_benchmark.py 的既有计时与真实检索基线，记录全部失败与完成配对 | benchmark_ttft_ms；汇总 P50/P95 |

检索评分：相关 chunk 由评测集预先标注；前五条至少命中一个为 Hit@5=1，首个相关结果排名的倒数为 RR@5，未命中为 0。Langfuse 不自动提供这些相关性标签。

检索链路对齐是必需工作：旧 Python hybrid 分支只有 RRF，没有 Cross-Encoder。使用一个最小测试入口调用现有 Java searchWithPermission，返回最终有序结果和 trace 标识；BM25 基线复用同语料、同用户可见集合与分析器。仅在测试代码中接入，不为评测改造生产工具接口。现有 /search/hybrid 会根据 userId 分支执行不同实现，不能未经确认就视作等同于聊天的权限检索+精排链路。标签、语料、候选规模与 topK 固定，先跑两种策略；KNN/RRF 单独消融后续需要时增加。

路由链路对齐同样必需：现有 Python 脚本内的 SEARCH_TOOL 仍是旧策略。导出当前 Java 工具定义与 Prompt 给脚本使用，或明确运行历史版本；2026-10-06 已改成事实性问答优先检索，旧标签需要重新复核。开发集用于调参，冻结测试集用于报告；真人复核前称为候选集结果。

先用 10 条样本验证关联。正式 TTFT 建议每条至少重复 3 次，交替两组顺序并保留预热、失败和重复次数；联调不要求先跑完整数据集。报告说明分母及重复聚合方式，不选择性删除慢样本。差异按实际结果填写；78%→90% 是 12 个百分点，不是相对提升 12%。

评分通过 [Langfuse SDK / Scores API](https://langfuse.com/docs/evaluation/evaluation-methods/scores-via-sdk) 关联到实际 trace/observation。稳定 score ID 使用 run+sample+strategy+attempt+metric，方便本地重传而不重复计数。只追踪新执行的调用，不为历史报告编造调用过程。

每次保留三类本地产物：metadata.json（代码/数据/Prompt/Tool 版本、模型/检索配置与计时口径）、raw-results.jsonl（逐条输出、耗时、错误、traceId 与评分上传状态）、report.md（指标、分母、混淆矩阵和对照结果）。沿用现有输出结构，必要时补字段，不强制重写目录或新增报告平台。免费历史访问窗口之外仍能本地重算。[访问窗口说明](https://langfuse.com/docs/administration/data-retention)

## 6. 必需验证和完成标准

使用 SDK 内存 exporter 检查父子关系/字段，使用一个 mock OTLP 服务检查认证头与失败隔离；不建立完整遥测故障平台。复用现有模型、聊天和生命周期测试夹具，优先验证新增风险：

1. 关闭功能：零遥测请求，聊天行为一致；本地配置能被启动脚本正确加载。
2. 直接回答与检索：节点、实际路由、结果顺序和精排降级记录正确。
3. 并发复用线程：两个请求不串链，Scope 恢复，根 span 只结束一次。
4. 正常、取消、超时与保存失败：观测遵从已有终态；数据库提交成功但完成通知丢失时不误报未保存。
5. Cloud 不可达/认证失败：不改变聊天结果，诊断没有正文或凭据；批处理采用 SDK 自带有界队列。
6. 评分公式与重传：已知小样本能算出正确 Hit@5/MRR@5/路由评分；原始结果重算与报告一致，评分不重复。

受影响测试通过后，运行仓库要求的后端检查。Cloud 联调只需一小批真实脱敏请求确认展示、关联与 units 消耗；不以完整评测、压力测试或造出预期百分比作为“采集接入完成”的前提。

完成分两步：聊天接入以正确的真实追踪和失败隔离验收；评测关联以新运行的逐条记录、评分和可重算报告验收。正式数据扩充、人工标签复核和简历指标定稿单独记录，不与基本接入混成一个无限扩大任务。

回退为 LANGFUSE_ENABLED=false 后重启，停止新遥测，不需要数据库或前端迁移。本次不改流式 usage 解析、记忆压缩、生产路由策略或 SSE 协议。

实施顺序：加载现有本地配置 → 小批 OTel 连通验证 → 三种聊天主体节点与必要测试 → 现有评测加关联/评分 → 小样本对照与本地重算。搜索内部三段耗时已按用户要求补充；裁剪指标、实际 token、摘要追踪、采样或标准实验页面仍在出现明确需求后再增加。
