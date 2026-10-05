# ShadowRAG 提示词注入防护调研与高 ROI 落地建议

日期：2026-10-03。状态：调研与方案建议；未修改生产代码，未执行模型攻击实验。以下优先级和工期属于结合当前代码的工程判断，不是厂商性能结论。

## 推荐结论

首版优先完成三项：**修复摘要来源和消息角色边界；统一封装检索证据并保持预算裁剪后的结构完整；限制 Markdown/HTML 渲染与外部资源加载。** 保留并验证现有服务端权限过滤、只读搜索和第二阶段无工具的约束。规则扫描只做辅助审计，分类器、额外 LLM 审查和完整双模型架构暂缓。

这套方案复用当前 Java 和 Vue 链路，不新增推理服务、不增加模型调用次数；仍会有本地处理和上下文 token 开销。熟悉代码的开发者完成核心改造可粗估 1–3 个开发日，联调、数据复核与效果评测另计。具体工期需要实现时确认。

提示词分隔和检测属于概率性防护；权限校验、工具能力限制和禁止外部资源加载可以约束具体后果。微软实际采用预防、检测、影响限制的纵深防御，并明确这些机制的保证不同。[Microsoft 安全工程说明](https://www.microsoft.com/en-us/msrc/blog/2025/07/how-microsoft-defends-against-indirect-prompt-injection-attacks)

## 当前项目的实际边界

| 已核实的行为 | 代码位置与含义 |
| --- | --- |
| 当前唯一内置工具是 `search_knowledge_base` | [ChatHandler.java:52](E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java:52)。尚未实现 MCP 客户端；2026-10-03 的 MCP 文档是待实现设计。 |
| 检索使用服务端传入的用户身份，KNN 和 BM25 共用权限过滤 | [ChatHandler.java:240](E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java:240)、[HybridSearchService.java:93](E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java:93)。模型只生成 query，不决定身份和授权。 |
| 搜索后的第二次模型请求不携带 tools | [ChatHandler.java:253](E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java:253)。限制同轮恶意文档驱动后续工具动作，但不能阻止回答污染和历史污染。 |
| 检索正文及文件名直接拼成文本，再放入 tool 消息 | [ChatHandler.java:391](E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java:391)、[ChatHandler.java:303](E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java:303)。已有消息角色区分，仍需明确资料的信任属性。 |
| 历史摘要虽然标注“非可信”，正文实际仍拼进 system | [ChatHandler.java:202](E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java:202)。标签不能抵消角色提升。 |
| 更直接的入口：正文以 `[历史摘要]` 开头就先被认作摘要，之后才检查普通历史角色 | [ChatHandler.java:178](E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java:178)。普通用户历史正文可能因此进入 system；这是一条代码层面明确的信任边界错误，并不等于已经证明模型会执行其中攻击。 |
| 压缩服务也按正文前缀识别摘要 | [ConversationCompressionService.java:323](E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ConversationCompressionService.java:323)。需与聊天回放一并修正来源判断。 |
| 预算器会直接 substring 截断整个 tool.content | [ContextBudgetService.java:104](E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ContextBudgetService.java:104)。若改为 JSON 或分隔符，必须同时调整裁剪方式。 |
| 助手消息交给 Markdown 渲染器，来源文件名被插入 HTML | [chat-message.vue:39](E:/Curzsu/ShadowRAG/frontend/src/views/chat/modules/chat-message.vue:39)、[chat-message.vue:154](E:/Curzsu/ShadowRAG/frontend/src/views/chat/modules/chat-message.vue:154)。本机安装的 vue-markdown-shiki 默认 `html: true`；这是源码风险判断，未执行浏览器攻击验证。 |

## 主流方法与取舍

| 方法 | 工程做法 | 本项目的 ROI 判断 |
| --- | --- | --- |
| 指令与数据分离 / Spotlighting | 固定 system；资料保持低信任角色，标识来源、内容边界及用途 | **首选，低成本。** 先使用正常文本与结构化封装；不要首版就把中文文档全转 Base64。 |
| 服务端权限和最小工具能力 | 授权不交给模型；工具名、参数、资源范围和操作类型由代码校验 | **首选，主要复用已有能力。** 当前只读检索和第二阶段无工具值得保留并回归验证。 |
| 安全输出与渲染 | 不让生成的 HTML、图片 URL 或任意链接直接成为浏览器执行和出网指令 | **首选。** 对当前流式 Markdown 前端有直接落点。 |
| 规则扫描 | 检查伪造角色、覆盖指令和异常控制字符等已知特征 | **辅助层，成本低。** 语义变体能绕过；正常安全资料也会引用攻击语句。首版默认记录风险信号。 |
| 专用分类器 / 云端检测 | 在主模型前扫描问题、检索片段和工具返回 | **第二阶段候选。** 新增推理或网络依赖，要实测中文、长文、误报和延迟。 |
| LLM-as-judge | 独立审查输入、输出或拟执行动作 | **暂缓。** 全量同步审查增加模型调用，当前只读两阶段流程的收益有限。 |
| 双 LLM / CaMeL | 有权限的规划模型与读取不可信材料的模型隔离，再由执行器约束数据流 | **暂缓。** 完整方案有执行器、来源追踪和能力策略，明显超出首版范围。 |

Spotlighting 包含分隔、持续标记和编码等方法，核心是提示模型区分来源。原论文中的 GPT 实验效果不能直接套用到 GLM、DeepSeek、Ollama，更不能写成 ShadowRAG 的指标。[Spotlighting 原论文](https://arxiv.org/abs/2403.14720)

服务端工具参数验证、最小权限、规则和输出检查是常见组合，但规则和模型检测都不应替代授权。[OWASP 防护清单](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)

Meta Prompt Guard 2 提供 22M/86M 分类器，输入窗口为 512 token；其列出的八种评估语言不含中文，不能把英语基准外推到中文文档。[Meta 官方模型卡](https://github.com/meta-llama/PurpleLlama/blob/main/Llama-Prompt-Guard-2/86M/MODEL_CARD.md)

Azure Prompt Shields 有独立 Content Safety API，可扫描 `userPrompt` 和 `documents`，因此能作为 GLM/DeepSeek 前的检测服务；其托管 Spotlighting 功能不会因本项目使用 OpenAI-compatible 协议自动生效。[Microsoft 官方文档](https://learn.microsoft.com/en-us/azure/ai-services/content-safety/concepts/jailbreak-detection)

CaMeL 约束控制流和数据流；简单增加一个“文档摘要 LLM”不等于实现这种隔离。官方仓库将其标为研究产物。[CaMeL 原论文](https://arxiv.org/abs/2503.18813)、[官方代码](https://github.com/google-research/camel-prompt-injection)

## 首版具体改造

### 1. 修复历史摘要与 system 的边界

- system 只含开发者维护的静态规则；摘要正文移到单独、明确标为不可信记忆的低信任消息中，不能伪造 tool 消息来承载没有对应工具调用的摘要。
- 摘要类型由服务端生成和保存，客户端、历史导入和正文不能自报来源。真正由服务端生成的摘要也可能继承攻击内容，仍只能作为记忆数据。
- 取消聊天回放和压缩服务中“正文前缀即摘要”的判断。无法证明来源的旧记录按普通低信任历史处理；只有受控迁移才能补可信元数据。
- 摘要生成时，将固定摘要任务和待总结历史分开，明确只提取事实、决策和上下文，不采纳历史中的规则覆盖指令。该修改仍不能保证摘要模型永不误判。

OpenAI 的工程指南明确提醒不要把不可信变量插入高优先级 developer 消息，并建议通过低信任消息与结构化数据限制其影响。这同样支持这里对 system 边界的修正。[OpenAI 官方指南](https://developers.openai.com/api/docs/guides/agent-builder-safety)

### 2. 封装检索证据，配合预算裁剪

- 用一个简单 formatter 生成结构化上下文，如 `sourceId`、`fileId`、`chunkId`、`content`、`truncated`；文件名和正文都是不可信字段，由 JSON 序列化处理转义。
- 固定规则明确：文档、文件名、历史记忆和工具返回中的命令只作为被分析的材料，不能改变角色、授权、工具或回答规则。
- 先删除低优先级条目或裁剪 content 字段，再序列化。不能在完整 JSON 或边界标签上继续任意 substring；保留来源映射和截断标记。
- JSON 只帮助保持结构完整，不能强制模型忽略其中的语义指令。首版无需引入随机分隔符、Base64 编码或专门的语义重写模型。
- 需要文档依据的回答如果没有可用证据，应明确说明无法据此回答；不影响本来就无需检索的普通请求。

### 3. 限制浏览器渲染与外部资源

- 助手 Markdown 禁用原始 HTML；来源链接使用组件和文本转义，不能直接拼接模型输出或上传文件名。
- 引用编号只能映射到本次服务端返回的文档 ID，不能把模型生成的任意 URL 当来源。
- 默认禁止自动加载外部 Markdown/HTML 图片，只允许受控文档资源；不能用任意 URL 图片代理绕过限制。
- 限制链接协议与目的地；模型生成的外部链接至少不自动请求、不预取。安全策略要在流式输出期间和历史重新打开时同样生效。

浏览器加载模型生成的图片 URL 会发送网络请求，URL 中可以携带敏感内容；关闭原始 HTML 本身仍挡不住 Markdown 图片。这类外传路径有明确的安全工程依据。[Microsoft 对外传渠道的分析](https://www.microsoft.com/en-us/msrc/blog/2025/07/how-microsoft-defends-against-indirect-prompt-injection-attacks)

### 配套：保留硬约束，增加轻量审计

- 工具参数限定允许字段、字符串类型、非空和最大长度；身份、权限标签和检索范围始终由服务端决定。Schema 约束不能替代授权。
- 保留第二次请求无 tools 的流程，不增加通用执行工具。MCP 工具名单、外联目标和写操作控制等到实际接入时设计。
- 规则扫描使用规范化副本，保留原文；首版默认只记录风险，不因“忽略指令”等关键词拒绝整篇文档。自动隔离若后续需要，应作为经过正常样本验证的可选策略。
- 审计记录入口、请求 ID、文档/片段 ID 和命中规则；避免把完整知识库正文及敏感数据复制到安全日志。

## 如何验证效果并形成简历证据

先做能明确断言的工程回归：普通 `[历史摘要]` 用户消息不得进入 system；预算裁剪后上下文仍可解析；非法工具字段和类型不得执行；跨用户/组织私有文档不可进入上下文；渲染不发送外部图片请求；来源文件名按文本显示。

再建立候选的 60 条攻击 + 60 条正常样本。攻击覆盖直接覆盖指令、恶意检索文档、伪造角色/摘要、压缩后持续污染、混淆变体、Markdown 外传。正常样本必须含合法安全教程、攻击语句引用、代码、普通中文资料和多轮指代。样本需要人工复核，未复核前不能称为“人工标注集”。

每个样本同时定义正常任务和攻击者目标。使用无害测试标记、虚构敏感值和受控请求接收端，判断攻击者目标是否真的达成，不能只看检测器是否命中，也不能把所有拒答都算成功防护。

| 指标 | 口径 |
| --- | --- |
| 攻击成功率 ASR | 达成预先标注攻击目标的攻击测试 / 攻击测试总数；按入口分别报告。 |
| 正常请求误拒率 | 正常测试被错误拒绝 / 正常测试总数；单独记录规则或分类器误报。 |
| 正常任务效果 | 答案、依据和来源是否正确，避免用“一律拒绝”改善 ASR。 |
| 工程边界 | 区分模型尝试违规与后端实际越权/外传；分别核验权限、参数和资源加载。 |
| 运行开销 | 端到端 TTFT 的 P50/P95、总延迟、token 数和额外模型调用数。 |

固定模型版本、生成参数、检索材料、预算与样本；基线和改造版交错运行。完整重复三次时，两版本共 720 次问答流程，实际模型请求数取决于检索路由；同一案例重复不算新增独立样本。预算有限可先跑小型开发集，保留独立测试集用于最终验收。

这些数据只能支持该测试集、模型和条件下的结论，不能证明完全免疫提示词注入。当前路由评测资产可复用记录模型、Prompt、数据版本和延迟的思路，但不能直接作为注入防护证据。

## 指定文章的方案评估

已直接读取微信原文正文，并核对第 4.2 节技术对比表、第 5.3 节风险分级表。文章为火山引擎 AI 安全 / 字节跳动技术团队的[《字节实践：Agent 提示词注入攻击》](https://mp.weixin.qq.com/s/aI4RU0kdCzIkIDmXIQ-tfw)。其 AgentSentry 实践分为 L1 归一化、L2 来源隔离、L3 注入检测、L4 输出行为；四层是逻辑链路，不代表本项目必须按该顺序投资。

下表是针对 ShadowRAG 的取舍，不能视为对 AgentSentry 防护效果的复现。

| 文章中的方法 | 在 ShadowRAG 中采用的范围 | ROI 判断 |
| --- | --- | --- |
| L2 来源隔离、Spotlighting | 修复摘要进入 system 的路径；固定系统规则，检索正文、文件名、工具结果和摘要都保持各自来源和低信任属性；按记录裁剪后再封装。 | **最高优先级。** 有已核实的具体入口，不新增模型调用。 |
| L4 输出行为 | 保留后端权限与只读工具；校验工具参数；引用映射到真实文档；关闭原始 HTML 和自动加载外部图片。 | **高。** 将其简化为明确的代码约束，首版不部署专家行为模型。 |
| L1 归一化 | 保留原文，生成检测副本；处理 NFKC、指定控制字符、HTML/URL/Unicode escape；Base64 只做有界候选解码；最多两轮，并限制大小与数量。 | **中高，但依赖后续检测。** 已授权接入的文本来源复用一个组件；当前无需提前实现 MCP 全链路。 |
| L3 规则检测与风险分级 | 使用少量可解释规则，结合来源记录风险；先观察误报，再启用经过测试的拦截策略。正常安全资料可以完整引用攻击指令，不能按一个关键词拒绝。 | **中高。** 适合与 L1 一起做；不声称能识别未知语义攻击。 |
| L3 微调判别模型 / Guard 模型 | 有真实漏检需求后，再选择专项注入检测模型，或定义政策并验证现成审核模型。 | **首版暂缓。** 增加部署、推理、阈值与中文评测成本。 |
| StruQ、SecAlign、Instruction Hierarchy 训练 | 正确使用消息角色可以直接做；模型训练方法暂不移植。 | **当前低。** 固定商用模型 API 下无法通过应用代码完成模型训练。 |
| 完整信息流控制 / CaMeL | 只借鉴控制与数据分离、工具执行前校验；不引入自定义解释器和能力传播。 | **当前低。** 当前仅一个只读工具，完整架构投入过大。 |

需要区分三件事：

1. **有害内容审核不等于提示词注入识别。** Qwen3Guard 官方说明其定位为 prompt/response 安全审核，包含 Jailbreak 等类别；gpt-oss-safeguard 面向用户提供的政策进行分类。它们能否识别本项目中的业务劫持，需要专项测试。例如，诱导模型无视知识库事实、改答一个无害词语，也可能构成任务劫持，输出本身却不属于传统有害内容。[Qwen3Guard 官方资料](https://github.com/QwenLM/Qwen3Guard)、[gpt-oss-safeguard 官方报告](https://openai.com/index/gpt-oss-safeguard-technical-report/)
2. **应用消息隔离不等于复现训练方案。** StruQ 包含格式化前端和专门训练的模型；SecAlign 使用偏好优化；IH-Challenge 是指令层级训练数据。仅增加 XML/JSON 标签，不能在简历中写成实现了这些模型防御。[StruQ](https://arxiv.org/abs/2402.06363)、[SecAlign](https://arxiv.org/abs/2410.05451)、[IH-Challenge](https://arxiv.org/abs/2603.10521)
3. **L4 检查必须赶在实际影响发生前。** 当前 [ChatHandler.java:260](E:/Curzsu/ShadowRAG/src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java:260) 会立即转发生成片段，最终生成结束才审核无法撤回已经显示的内容或已经发出的图片请求。工具参数在执行前校验，HTML/图片策略在渲染前生效；若以后要拦截文本中的敏感信息，需要额外缓冲并处理跨片段匹配，首版不顺带承诺完整 DLP。

建议的实施顺序为：**L2 信任边界修复 → L4 明确行为约束 → L1 检测副本 + L3 规则及审计 → 对抗评测。** 首版只覆盖实际存在的消息、摘要和检索链路；之后接入 MCP，再补工具描述来源、工具名单和外联范围。熟悉代码时首版整体粗估 3–5 个开发日，包含基本回归，模型效果评测与样本复核另计；这是尚未实现的工程估算。

验收继续使用本报告前述攻击目标、正常误拒、依据正确性和延迟口径，尤其覆盖编码变体、合法攻击示例、伪造摘要、多轮压缩污染和流式渲染外传。

## 简历写法

**现在已经完成的调研/设计：**

完成 ShadowRAG 提示词注入威胁分析与防护方案设计，识别历史摘要进入高信任消息的风险，制定检索证据隔离、安全渲染及对抗回归评测方案。

**实施并验证上述改造后：**

为 ShadowRAG 落地轻量提示词注入防护，修复历史摘要的信任边界，隔离检索证据与系统指令，结合服务端检索权限、工具参数校验和安全 Markdown 渲染，在不增加模型调用次数的前提下完善 RAG 安全链路。

**取得真实数据后再补充：**

基于 N 条经人工复核的攻击与正常样本，对照验证攻击成功率由 A% 降至 B%，正常请求误拒率为 C%，新增 P95 首 token 延迟为 D ms。

N/A/B/C/D 必须用实际评测值替换，并保存模型版本和测试条件。不要写“完全杜绝注入”“100% 安全”或借用厂商论文数字。
