# ShadowRAG 知识库 ReAct 接续设计

日期：2026-10-06  
状态：R1～R3 验收通过，改动保留在专用分支，见 [ReAct验收](../../eval/chat_stream/knowledge-base-react-acceptance.md)
前置：[聊天去除Flux计划](../plans/2026-10-06-remove-flux-chat.md) 的阶段3验收通过

## 1. 用户目标与执行顺序

用户希望聊天调用简化后尽快实现ReAct，MCP暂不处理。因此主顺序调整为：

```text
当前基线 → 普通HTTP读取准备 → 聊天完整切换 → 聊天验收
        → ReAct消息基础 → ReAct循环与SSE → ReAct验收
```

Embedding、Reranker、MinerU和全项目依赖清理暂缓，不作为ReAct前置条件。保留检索内部的现有客户端，可以直接复用已经可用的知识库搜索。

聊天去Flux阶段0～3已完成，以 `b399f9c` 为基线在 `codex/knowledge-base-react` 实施R1～R3。具体步骤见 [实施计划](../plans/2026-10-06-knowledge-base-react.md)。本设计保留阶段过渡说明，R1的单工具保护已在R2被完整循环替换。

## 2. 最小可用范围

首版仅一个工具：`search_knowledge_base`。

允许模型改写query、拆分问题、重复查找或同一轮提出多个搜索调用。收到工具结果后继续携带搜索工具请求模型，直到模型不再请求工具，或执行预算触发收尾。

不引入MCP客户端、通用插件系统、工具动态注册、规划器、并行Agent或新的AI框架。工具只有一个时使用一个明确的知识库执行器即可，不为未来MCP先建设复杂ToolRegistry。工具执行先串行，保持调用和结果顺序。

示例验收问题：根据知识库中的两份报告，比较收入变化；第一次检索只拿到报告A，模型继续检索报告B，再生成有来源的回答。模型是否确实选择第二次检索由模拟响应控制，不能用真实模型的偶然输出作为单元测试判定。

## 3. 阶段拆分

| 阶段 | 交付内容 | 主要文件 | 完成标准 |
| --- | --- | --- | --- |
| R1 | 完整工具调用与成组消息预算 | ModelRoundResult、BlockingModelHttpClient、ModelDelta、ContextBudgetService、模型模拟夹具 | 多调用分片不混淆，工具消息配对完整；生产仍保持已通过的两次调用流程 |
| R2 | 普通循环、回合SSE、预算和保存联动 | AgentLoopService、KnowledgeBaseSearchTool、ChatHandler、ChatStreamService、ChatOutput、前端SSE/聊天store/消息组件、提示词 | 能连续搜索，逐段显示回合正文，最终答案保存一次 |
| R3 | 功能与资源验收 | 模型、Agent、MVC、前端测试及验收记录 | 多轮检索、结束、权限、取消、预算和保存通过 |

R2中的循环、前端事件和后端保存必须一起接入，不能先允许多轮正文拼接，之后再补“只保存最终回答”。

## 4. R1：消息基础

### 4.1 模型响应

扩展去除Flux阶段已创建的ModelRoundResult，保存完整正文、可选推理协议字段、调用列表和完成原因。工具调用对象至少有index、id、name和argumentsJson。

- 按index累积工具ID、名称和参数，支持交错分片。
- 相同完整名称在后续帧重发时不重复拼成两个名称；真正的名称分片按协议组装。模拟服务器也应区分首帧元数据和后续参数。
- 检查调用ID非空、同轮唯一、名称非空；缺失或重复的ID属于协议错误，不补造ID。完整调用的参数不是合法JSON对象、query非法或工具名称不受支持时，R2追加带原调用ID的工具错误结果，不执行搜索。
- 不再只读取tool_calls[0]。一轮结束前不执行任何工具。
- 需要推理字段回传的模型保留其协议内容，不作为普通正文发送、保存或日志输出；按当前供应商适配验证。
- 延续DONE、截断、限额、真实连接取消等已有契约，复用HTTP读取和资源管理组件。

R1暂不切换生产循环。旧两次调用路径显式只接受一个search_knowledge_base调用；遇到多个调用安全失败，不能静默丢掉其余调用并伪造结果。R2再启用多调用执行。

### 4.2 上下文

每轮发给模型的历史形式：

```text
system + 旧对话 + 当前user
assistant（content及本轮全部tool_calls）
tool（对应调用1）
tool（对应调用2）
下一次模型请求
```

修改ContextBudgetService，避免逐条删除当前user或把assistant与tool结果拆开。首版保护整个当前用户回合及其工具协议骨架；先删除更旧的完整问答，再截断本回合工具正文，不删除调用ID或结果消息。截断仍超过预算时安全失败。

每轮预留工具定义和模型输出的token空间。为全部工具结果设置正文预算，不能只截断最后一个；保留稳定的来源ID，如fileMd5和chunkId，用于同一请求内的去重和引用。

## 5. R2：普通Java循环

ChatHandler继续加载用户会话和构建初始消息，委托一个请求内的AgentLoopService。它只生成业务输出，由ChatStreamService决定终止和保存；循环状态不得放在Spring单例服务字段。

模型适配器采用PaiCLI的“同步返回完整结果，同时回调增量正文”的形态：普通方法读取供应商SSE，每收到正文分片调用Consumer，流完整结束后返回ModelRoundResult。调用运行在聊天生成线程池，增量回调交给已有SSE发送队列。这个方法不会等到全部生成完才把正文发给前端，也不需要Flux。所有轮次使用同一份请求内消息列表，预算裁剪也针对这份真正发送给模型的列表。

```text
循环前检查请求状态与剩余时间
携带搜索工具请求模型，逐段发出当前回合正文
本轮完整结束：
    没有工具调用 → 标记回合为最终答案，返回
    有工具调用 → 标记回合为中间说明
                 追加完整assistant消息
                 串行执行搜索并追加全部对应tool结果
                 回到循环
预算触发 → 在剩余时间允许时，关闭工具请求一次部分结果收尾
```

KnowledgeBaseSearchTool仅接受合法query参数，调用现有`searchWithPermission(query, command.username(), 10)`。用户名、会话ID和权限上下文来自服务端，不能由模型决定。

每个工具执行前、执行返回后，以及下一次模型请求前都检查ChatRequestContext。全部轮次复用同一个请求截止时间和ChatGenerationResources；不为每轮创建独立的取消上下文。一次模型读取结束时释放本次连接资源，保留请求状态供后续轮次使用。

无结果返回明确的空检索结果，让模型可以改query。可恢复的参数或搜索错误返回带原调用ID的错误结果；模型流损坏、取消、总超时则结束请求。工具结果作为不可信资料，不允许结果中的指令覆盖system规则或授予新能力。

### 5.1 首版预算

- `ai.agent.max-tool-rounds=3`：最多3轮工具执行。
- `ai.agent.max-tool-calls=6`：单请求最多6次实际工具执行。
- `ai.agent.repeated-call-limit=3`：连续相同工具和归一化参数达到3次后停止继续执行该重复动作并转收尾。
- 原300000ms总生成超时继续覆盖排队、全部模型请求和工具执行，不为每轮重新计时。
- `ai.agent.finalization-reserve-ms=10000`：继续工具前判断是否已进入收尾预留时间；已经耗尽总时间或用户取消时不再调用模型。

在每次模型请求前判断是否还有工具预算。完成第3轮工具执行后，第4次请求直接进入无工具收尾，不能先发第4次带工具请求、再追加第5次收尾。首版单请求最多4次模型调用，包含收尾；取消或已超时不占用新的模型调用。

工具预算在一轮执行中触发时，对本轮尚未执行的调用追加明确的预算错误结果，不能留下无响应的tool_call。连续相同调用的第3次在执行前拦截并追加错误结果。关闭工具后最多一次收尾，正文明确标注部分完成；不把未检索到的事实编造成答案。收尾响应若仍包含tool_calls，视为供应商违反无工具契约并失败，不再次执行工具或继续循环。

### 5.2 回合正文与SSE

沿用当前chunk事件，但ReAct时带递增的roundId。新增一个`round_end`事件，包含roundId与`kind=intermediate|final`；它只在本轮模型完整结束后发送，不允许错误流通过round_end伪装成成功。

- 前端逐段显示当前回合draft，保持真正的流式体验。
- 中间回合结束后，其draft变为中间说明，不拼入最终答案。
- final回合确认后，把对应draft作为最终答案；后续completion仍由原状态机控制。
- 后端发送端分别累计回合文本，只有确认final的回合进入持久化回答。
- round_end及工具进度同样经有界队列和单发送者输出，维持seq连续。
- 第一版不展示模型原始推理，普通正文不等于推理内容。
- 未实施ReAct的旧chunk不带roundId时，保留当前处理方式。

因此需要一起调整ChatOutput支持的事件类型、ChatStreamService的文本累计、前端validatePayload、聊天store和消息渲染。工具进度仍围绕search_knowledge_base，补充roundId/callId用于区分多次调用；首版可以用finished表示执行已结束，具体错误通过tool结果交回模型，不为MCP预设一组事件。

提示词允许在私有资料不足时改写查询继续搜索，同时保留“通用问题直接回答”的边界。不能为实现ReAct强制所有问题都查知识库。

## 6. R3：验收要求

1. 通用问题直接回答，零工具调用，正文真实流式显示。
2. 连续两次不同查询后生成答案，每次调用消息和结果正确配对。
3. 同轮两次搜索按顺序执行，参数分片交错仍不混淆。
4. 无结果或可恢复工具错误后，模型可以调整query；不能越权访问文档。
5. 3轮、6次调用、重复动作和总时间预算均有效；正常/部分收尾都不再暴露工具。
6. 在任意模型轮次、搜索中、收尾前取消后，不启动后续步骤、不保存半截答案。
7. 上下文超限时保留当前user和完整tool配对，不出现孤立tool消息。
8. 中间回合正文实时可见但不混入最终保存的答案；最终回答只追加一次，Redis提交后失败不重复保存。
9. roundId不倒退，round_end不重复；meta首个、seq连续、completion唯一。
10. HTTP与任务资源复用聊天阶段的验证，确保没有因为普通循环增加泄漏或重新使用Flux。

本地模拟供应商精确安排“检索A → 检索B → final”的序列，并断言模型请求3次、搜索2次、保存1次。真实模型联调仅作为用户另行安排的补充，不替代确定性测试。

R3完成后即可交付：普通Java编排的知识库ReAct、现有SSE流式输出、请求级取消及最终答案保存。MCP保持未实施；全项目去Reactor不是该交付的完成条件。

## 7. PaiCLI源码参考与适配

参考目录：`E:\Curzsu\paicli-main\paicli-main`。以下依据当前本地源码及测试读取整理，没有运行PaiCLI、调用真实模型或修改其代码。这里的ReAct指模型与工具交替执行的Agent流程。

### 7.1 核心流程

[Agent.java](/E:/Curzsu/paicli-main/paicli-main/src/main/java/com/paicli/agent/Agent.java:215) 的主流程是：

```text
while true:
    检查取消、整理实际消息历史、检查预算
    response = chat(messages, tools, streamListener)
    如果 response 有 tool_calls:
        追加 assistant(content, reasoningContent, 全部 tool_calls)
        执行工具
        按原调用ID追加每一条 role=tool 消息
        检查重复动作，继续循环
    否则:
        返回最终正文
```

ShadowRAG采用这个控制流程，把当前“第一次判断搜索、第二次固定回答”变成“结果不足则继续搜索”。是否继续由模型返回的结构化tool_calls决定；不解析正文中的“Action/Observation”等文字，也不把原始推理展示给用户。

### 7.2 文件与ShadowRAG阶段对应

| PaiCLI实现 | 已确认的行为 | ShadowRAG适配位置 |
| --- | --- | --- |
| [LlmClient.java](/E:/Curzsu/paicli-main/paicli-main/src/main/java/com/paicli/llm/LlmClient.java:11) | chat普通返回ChatResponse；StreamListener逐段收到正文或推理；结果含完整工具列表 | R1：BlockingModelHttpClient增量回调与ModelRoundResult |
| [AbstractOpenAiCompatibleClient.java](/E:/Curzsu/paicli-main/paicli-main/src/main/java/com/paicli/llm/AbstractOpenAiCompatibleClient.java:558) | 按index累积多个调用，再组装id/name/arguments | R1：扩展现有只处理第一个调用的解码器；工具参数完整后才执行 |
| [Agent.java](/E:/Curzsu/paicli-main/paicli-main/src/main/java/com/paicli/agent/Agent.java:257) | 先追加包含全部调用的assistant，再回填全部tool结果，然后continue | R2：请求内AgentLoopService与单一KnowledgeBaseSearchTool |
| [Agent.java收尾](/E:/Curzsu/paicli-main/paicli-main/src/main/java/com/paicli/agent/Agent.java:341) | 预算触发后只进行一次tools为空的模型调用，并标记部分完成 | R2：最多一次无工具收尾，受原总截止时间约束 |
| [AgentBudget.java](/E:/Curzsu/paicli-main/paicli-main/src/main/java/com/paicli/agent/AgentBudget.java:45)、[RunawayGuard.java](/E:/Curzsu/paicli-main/paicli-main/src/main/java/com/paicli/agent/RunawayGuard.java:31) | 执行预算与重复动作提醒分开；重复动作参数进行JSON归一化 | R2：首版只保留轮数、调用次数、重复参数和时间限制，不引入整套CLI预算与提醒系统 |
| [ToolResultBoundary.java](/E:/Curzsu/paicli-main/paicli-main/src/main/java/com/paicli/tool/ToolResultBoundary.java:26) | 工具正文带不可信资料边界，处理伪造的边界标签 | R2：检索结果明确标为资料；若采用标签，转义文档中同名标签。权限仍由搜索服务验证 |
| [AgentBudgetFinalizationTest.java](/E:/Curzsu/paicli-main/paicli-main/src/test/java/com/paicli/agent/AgentBudgetFinalizationTest.java:23) | 模拟固定响应，记录每次请求的messages/tools，断言收尾没有工具 | R3：复用测试方法，断言搜索A→搜索B→final及预算收尾的实际请求序列 |

### 7.3 不直接照搬的行为

1. **默认预算。** PaiCLI默认不限制硬轮数和token总量，RunawayGuard连续3次提醒，AgentBudget默认连续5批相同工具调用触发停滞收尾。这与ShadowRAG首版3轮工具、6次实际调用、第3次相同动作拦截的规则不同。服务端采用第5.1节的有界规则，降低等待时间和单请求占用。
2. **取消上下文。** [CancellationContext.java](/E:/Curzsu/paicli-main/paicli-main/src/main/java/com/paicli/runtime/CancellationContext.java:6) 使用静态CURRENT和InheritableThreadLocal。ShadowRAG有多个用户并发请求和复用线程池，应延续现有ChatRequestContext，避免一个请求取消影响另一个请求。
3. **流完成与调用ID。** PaiCLI允许finish_reason作为完成标志，并在缺失ID时补本地call_index。ShadowRAG继续要求供应商DONE和有效调用ID，不降低已有截断检测标准。名称累积也不能直接复制append：需要覆盖本项目模拟流重复完整名称的情况。
4. **工具与输出。** PaiCLI还有通用ToolRegistry、读工具并行、审批交互、CLI渲染器等能力。ShadowRAG首版串行执行一个搜索工具，使用现有SseEmitter；CLI“每轮清空渲染缓冲”对应Web端显式roundId/round_end及最终回答保存，不能把每轮正文全部拼成最终答案。
5. **日志与推理。** PaiCLI有推理追踪日志和会话账本。ShadowRAG仅在供应商协议要求时于请求内保留推理字段，不新增原始推理日志或数据库保存。

### 7.4 实施顺序保持不变

先完成聊天去Flux阶段0～3，再以完成后的普通HTTP接口执行R1→R2→R3。PaiCLI可作为循环、消息回填、流式回调和收尾测试的参考；无需先迁移其他检索客户端或接入MCP。
