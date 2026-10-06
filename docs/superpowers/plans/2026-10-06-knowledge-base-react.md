# 知识库 ReAct 实施计划

目标：将普通Java的固定两次模型调用扩展为模型与知识库工具交替执行的有界循环，保持SSE流式输出、请求取消、权限和最终答案保存。

设计依据：[ReAct接续设计](../specs/2026-10-06-knowledge-base-react-design.md)。用户已授权实现R1～R3及专用分支；基线 `b399f9c`，分支 `codex/knowledge-base-react`。沿用当前工作区，不提交、推送或部署。采用当前会话顺序执行与最终独立审查。

## 约束与接口

- Java17、JDK HttpClient、Spring MVC SSE；不引入新框架或依赖、不实现MCP。
- 首版只有search_knowledge_base，串行执行；身份来自ChatCommand，调用searchWithPermission(query, username, 10)。
- 延续DONE完整性、总截止时间、资源所有权、两套有界线程池与保存仲裁。
- ModelToolCall(index,id,name,argumentsJson)；ModelRoundResult(content,reasoningContent,toolCalls,finishReason)。ModelDelta增加可选toolCallIndex，普通正文不含推理。
- ContextBudgetService.fitAgent(messages,reservedOutputTokens,toolDefinitionTokens)保护当前user至末尾，旧历史按完整回合裁剪、过大的tool正文共享长度上限截断，短结果保持完整，保留调用配对和来源索引。
- KnowledgeBaseSearchTool.execute(command,context,call,sources)返回有界、明确不可信且带稳定来源的结果；sources为请求内稳定编号映射，不缓存正文交付状态；同一次结果内去重，跨调用可重新提供正文。无效参数/未知工具/可恢复检索失败回填原callId错误。来源文件名保持可下载的完整身份，异常超长名称安全失败。
- AgentLoopService.generate(command,context,messages,output)使用请求内状态；3轮工具、6次实际执行、连续相同归一化调用第3次拦截、10000ms收尾预留。最多maxToolRounds+1次模型调用；一次无工具收尾。
- chunk含roundId，round_end含roundId/kind；进度含roundId/callId。发送者只将final回合正文保存；旧无roundId事件保持兼容。
- 前端验证回合顺序、工具配对和最终回合；实时draft、中间说明与最终答案分别管理。取消/错误不把draft变为最终成功。

## Review Focus

1. 交错tool分片、重复完整名称、缺失/重复ID必须正确处理，失败不执行工具。
2. 每次实际模型请求的消息都有完整assistant/tool配对，预算不能移除当前user或只截断最后一个tool。
3. 工具预算在同轮中耗尽时剩余调用都有错误结果；第4次默认调用已经关闭工具，不能多发第5次。
4. 中间正文实时输出但不污染保存；round_end重复/倒退、final后事件均拒绝，兼容旧chunk。
5. 各轮工具前后、模型前后、收尾前复用同一取消/截止时间；错误、队列拒绝、发送失败无追加保存或资源泄漏。

## R1：模型协议与上下文

- [x] 运行新分支后端/前端基线，记录Docker可用状态。
- [x] 先写真实HTTP多调用交错分片/名称重发/ID校验/推理隐藏与限额测试，观察失败。
- [x] 扩展完整模型结果和解析器；显式限制每轮调用数量、累计工具参数/元数据和推理，完整流后校验调用列表。
- [x] 先写多轮多调用上下文裁剪与超限测试，观察失败；实现fitAgent并保护当前回合骨架和各工具来源索引。
- [x] 暂时保持旧生产两次调用路径，显式只接受单个搜索调用；定向测试通过后记录R1。

验证：ModelReActProtocolTest、ContextBudgetAgentTest、原模型/代理/上下文/Handler定向测试。

## R2：循环、保存与前端一次切换

- [x] 先写真实Agent配合本地模型的多轮、同轮多调用、错误恢复、预算/重复/取消测试；新增配置校验测试，观察失败。
- [x] 实现AgentLoopService、KnowledgeBaseSearchTool及ai.agent配置；ChatHandler只准备历史与保存，委托循环。
- [x] 先写后端回合隔离保存和非法回合测试；新增round_end并实现发送侧回合文本累计，保持原seq、终止和提交边界。
- [x] 先写前端回合协议、draft/中间/最终状态测试；接入验证、store与消息组件；无原始推理展示。
- [x] 迁移原Handler预期到显式回合契约；知识库问题允许连续检索，通用问题仍直接答复。
- [x] 后端定向套件、前端聊天套件及生产构建/typecheck通过，记录R2。

## R3：完整验收

- [x] 实际MVC SSE链路验证“搜索A→搜索B→final”：模型3次、搜索2次、保存1次，来源稳定，中间说明实时可见但不保存。
- [x] 验证同轮多搜索、空结果/错误恢复、3轮/6次/重复/时间预算、各轮取消和上下文配对；复用原HTTP资源与Nginx验收。
- [x] 在Docker环境运行不排除类的mvn test；如环境失败记录具体原因，不删除测试。前端test:chat、build后typecheck、后端package。
- [x] 最终独立只读审查；重要问题补失败测试后修复，回归通过。
- [x] 更新README、设计状态、验收数据与阶段清单；停在R3，不自动提交或推送。

验收文档：docs/eval/chat_stream/knowledge-base-react-acceptance.md。各阶段日志：.superpowers/sdd/2026-10-06-knowledge-base-react/。
