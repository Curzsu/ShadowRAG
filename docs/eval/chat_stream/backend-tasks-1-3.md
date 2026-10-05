# 后端 SSE 重构：任务 1–3 交付与验证记录

日期：2026-10-04。分支：codex/backend-chat-sse。基线：6cb1e866a503af568bb58d8913c73dfb8154243a。

状态：任务 1–3 已完成，分任务及整体独立审查通过。最终相关回归 175 项全部通过；全量测试仍有 9 个基线已有错误。本记录不代表任务 4–7 或整体验收已完成。代码保留在上述分支的工作区，尚未提交。

## 实施范围

本次只执行 [实施计划](../../superpowers/plans/2026-10-03-websocket-to-sse-refactor.md) 的任务 1–3，接口和验收要求见 [设计方案](../../superpowers/specs/2026-10-03-websocket-to-sse-refactor-design.md)。

| 任务 | 交付内容 |
| --- | --- |
| 1 | 不可变命令与事件模型；请求去重、会话单请求租约、原子状态、先到取消、容量和轻量终态保留。 |
| 2 | 可取消的冷模型流；第一轮正文即时输出；权限检索与第二轮在同一订阅链；显式会话历史与单次保存。 |
| 3 | POST SSE 与独立取消 API；串行写入、心跳、总期限、缓冲限制、提交边界、资源清理与 JWT 回归。 |

前端、代理、测试页和旧 WebSocket 删除仍属于任务 4–7。当前页面继续使用原入口，后端增加的新接口供后续接入。没有修改数据库结构，也没有调用付费模型、提交或推送代码。

## 新接口

两个接口均使用 Authorization Bearer 身份；用户名来自认证 Principal。

- POST /api/v1/chat/stream：JSON 字段 conversationId、requestId、message。两个 ID 使用标准 UUID；会话必须存在且属于当前用户。正文保留原值，拒绝纯空白及超过 16000 个 UTF-16 单元的内容。
- POST /api/v1/chat/requests/{requestId}/cancel：普通 HTTP 请求；响应包含当前用户命名空间中的实际请求状态，支持取消先于生成。

SSE 依次输出 meta、chunk/tool_progress 和 completion；失败先输出安全 error。事件 JSON 包含 type、requestId、conversationId、连续 seq 和 data；心跳为注释，不增加 seq。响应使用 UTF-8，并设置 Cache-Control: no-cache, no-transform 和 X-Accel-Buffering: no。

启动前错误使用 HTTP JSON：400 参数错误、401 未认证、403 无权会话、404 会话不存在、409 重复/先到取消/会话忙、429 容量不足、500 启动失败。响应已经开始后使用 SSE 终态；网络写入异常只清理资源。

## 关键实现决定

- 模型供应商的成功完成必须包含 [DONE]；普通 EOF 或只有 finish_reason 不授权保存部分答案，也不会启动下一轮模型。
- 生产 emitter 等待 MVC 完成初始化后才启动，避免框架的提前发送缓存绕过队列限制。
- 阻塞历史、检索和发送使用有界工作池。每个请求只有一个模型订阅和一个串行发送队列；取消同时覆盖两轮模型及迟到检索结果。
- 只有全部正文成功写出并赢得 COMPLETING 状态的请求才能保存。MySQL 中用户/助手消息使用同一事务；事务返回成功后才发送 finished。
- 已进入 COMPLETING 时取消返回 completing；提交期间断线不重新保存，提交结果可从历史恢复。此前的取消、超时、错误和缓冲溢出不保存。
- MySQL 提交后的 Redis 故障不重试数据库保存。读取历史时比较缓存覆盖范围与数据库已提交消息的最大序号；缺失或内容形状非法时从数据库恢复。提交后的缓存更新查询本会话实际前一轮最大序号，先读版本再读内容，以有界 CAS 重试保留有效摘要；覆盖不一致时重建，避免 Redis 恢复过程中掩盖此前丢失的轮次。消息 ID 不假设连续。
- 保留现有 JWT 有效期：access 3600000 ms、预刷新 300000 ms、过期宽限 600000 ms、refresh 604800000 ms。刷新必须检查签发缓存及撤销状态；Redis 验证异常拒绝认证。
- 本次仍为单实例实现。多实例的取消路由与会话并发控制需要另行设计。

## 默认配置

参数绑定前缀为 chat.streaming；配置非法时启动失败。

| 参数 | 默认值 |
| --- | --- |
| generation-timeout-ms | 300000 |
| emitter-timeout-ms | 320000 |
| heartbeat-interval-ms | 15000 |
| terminal-retention-ms | 300000 |
| max-active-requests | 100 |
| max-retained-requests | 10000，包含活动请求和先到取消记录 |
| max-retained-per-user | 200，包含活动请求和先到取消记录 |
| worker-threads | 16 |
| worker-queue-capacity | 1024 |
| max-pending-events | 每请求 64 个业务事件 |

MySQL 保存事务 timeout 为 10 秒。定时任务取消后从调度队列移除；保留记录只包含状态和过期时间，不引用正文、连接或订阅。

## 验证记录

环境为 Windows、JDK 21 编译 release 17、Maven 3.9.11、项目现有 Spring Boot 3.4.2。模型采用本地 JDK HttpServer；HTTP 验证使用嵌入式 Servlet、实际网络连接和真实安全过滤链，DB/Redis/检索边界使用测试替身。未将 MockMvc 响应内容作为真实断线证据。

| 检查 | 当前结果 |
| --- | --- |
| 基线全量测试 | 61 项，0 failures、14 errors；已有外部数据库环境与旧 mock 缺失问题。 |
| Task 1 最新专项 | 29 项通过；覆盖终态竞争、取消回调竞态、UUID 大小写归一及安全诊断。 |
| Task 2 最新专项 | 90 项通过；覆盖两轮取消、供应商截断、历史形状检查、提交后缓存恢复及版本冲突。 |
| Task 3 最新专项 | 56 项通过；覆盖实际 HTTP 断线、跨用户取消、异步错误、日志隐私、工作池拒绝及服务关闭清理。 |
| 最终相关联合回归 | 175 项，0 failures、0 errors、0 skipped；2026-10-04 19:34:45 +08:00，BUILD SUCCESS。覆盖最后的缓存诊断调整。 |
| 最终全量回归 | 198 项，0 failures、9 errors、0 skipped；2026-10-04 19:36:33 +08:00，BUILD FAILURE。 |
| 分任务及整体独立审查 | 规范符合、代码质量批准；无遗留 Critical/Important 问题，缓存诊断建议已关闭。 |

29、90、56 三组均已包含在 175 项联合回归中，不能重复累加。各缺陷都先用失败回归复现，再实现修复。统计和逐类结果见 [结构化测试记录](backend-test-results.json)。原始日志、任务报告与独立审查报告保留于本地忽略目录 `.superpowers/sdd/2026-10-03-websocket-to-sse-refactor/`；联合日志为 `backend-final-green.log`，全量日志为 `backend-final-full.log`，整体审查为 `backend-final-review.md`。

全量的 9 个错误分别为 ParseServiceTest 6 项、UploadServicePerformanceTest 1 项、SmartPaiApplicationTests 1 项因已有数据库环境不可连接而无法加载应用上下文，以及 UserServiceTest.testRegisterUser_Success 缺少 OrganizationTagRepository 测试替身导致的 1 项错误。它们均在原基线出现；原基线另外 5 个 JWT 测试替身错误已随本次授权的认证测试更新解决。没有为使全量变绿而跳过这些测试。

复核命令（仓库根目录，使用正常 Maven 缓存）：

```powershell
mvn -B "-Dtest=ChatRequestRegistryTest,ChatStreamingPropertiesTest,DeepSeekClientStreamingTest,ChatHandlerStreamingTest,ChatHandlerHistoryTest,ConversationServiceHistoryTest,ConversationMessageServiceTest,ContextBudgetServiceTest,ConversationCompressionServiceTest,RerankerClientResponseTest,HybridSearchServiceLoggingTest,ChatStreamServiceTest,ChatControllerSseTest,ChatStreamingHttpTest,JwtUtilsRefreshTest,TokenCacheServiceTest" test
mvn -B test
```

## 后端验收证据映射

下表仅标记本阶段验证到的后端行为；完整验收仍按设计方案逐项执行。

| 验收项 | 证据与结果 | 剩余边界 |
| --- | --- | --- |
| A01–A02 | DeepSeekClientStreamingTest、ChatHandlerStreamingTest：冷流、首轮即时正文、搜索与第二轮顺序、同链取消通过。 | 页面和部署链路联调未执行。 |
| A05–A08 | JwtUtilsRefreshTest、TokenCacheServiceTest、ChatControllerSseTest、ChatStreamingHttpTest：认证/撤销/宽限刷新、归属、参数边界与流前 JSON 通过。 | A06 前端接收并使用 New-Token 待任务 4–5。 |
| A09–A16 | ChatRequestRegistryTest、ChatStreamServiceTest、ChatHandlerStreamingTest、ChatStreamingHttpTest：重复、会话租约、取消先到、状态竞争、不同用户隔离通过。 | A11 的前端消息归属待任务 5。 |
| A17 | ChatStreamingHttpTest：真实下游关闭后，上游订阅取消且本地模型 HTTP 连接关闭。 | 使用本地模型；部署网络链路待任务 7。 |
| A18–A20 | ChatStreamServiceTest：注释心跳、总期限不因心跳重置、模型/工具/历史错误及单次终态通过。 | A18 默认 15 秒参数经真实 Nginx、页面状态待任务 7。 |
| A25–A27 | ChatStreamServiceTest、ChatHandlerHistoryTest、ConversationMessageServiceTest、ConversationServiceHistoryTest：仅提交赢家保存、已发送正文与保存一致、成功先于 finished、缓存恢复通过。 | 真实 MySQL 事务/超时、Redis 故障与 SQL 查询计划尚未验证。 |
| A28–A29 | ChatRequestRegistryTest、ChatStreamServiceTest：容量、TTL、缓冲上限、清理身份、工作池拒绝及健康连接关闭通过。 | 50 并发及 1000 请求的资源观测待任务 7。 |
| A35 | ChatStreamingHttpTest、HybridSearchServiceLoggingTest、命令诊断测试：专用测试标记验证 token、完整 prompt、原始检索内容不进入新链路日志或错误。 | 后续前端、代理及测试页仍需隐私回归。 |
| A36 | 历史、预算、压缩、重排相关旧测试以及 ChatStreamServiceTest 服务关闭回归通过。 | 原项目全量仍有上述 9 个错误；完整生产依赖启动未证实。 |

## 本阶段的验收边界

本阶段提供后端层面的 A01–A02、A05–A20、A25–A29、A35–A36 证据；其中涉及前端、真实基础设施或压力测试的部分仍需任务 4–7。A03–A04、A21–A24、A30–A34 不作为本阶段已完成项。

尚需完整客户端解析/页面联调、真实 Nginx 分帧与默认心跳验证、50 并发及 1000 请求资源测试、现有页面构建，以及真实 MySQL/Redis 的事务和故障验证。不能据本阶段测试声称全部 A01–A36 已验收或可以单独发布。

后续进展（2026-10-05）：前端任务 4–5 已完成，结果与现有类型检查基线限制见 [前端交付记录](frontend-tasks-4-5.md)。任务 6–7 的旧链路清理、部署及完整基础设施/负载验收仍待执行。
