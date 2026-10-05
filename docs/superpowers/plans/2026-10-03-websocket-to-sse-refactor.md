# WebSocket → SSE Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 执行进度及验证边界见下方记录。

**Goal:** 将项目的浏览器聊天 WebSocket 完整迁移为 POST SSE，交付可取消、按会话隔离、终态一致并通过明确验收的聊天链路。

**Architecture:** 保留 Spring MVC，Controller 返回 SseEmitter。DeepSeekClient 与 ChatHandler 返回可取消的冷 Flux，ChatStreamService 统一管理订阅、串行发送与保存；本地 Registry 管理请求、会话租约和终态。前端 fetch 解析 SSE，独立的请求会话控制器管理消息归属与异步竞态。

**Tech Stack:** Java 17、Spring Boot 3.4.2 管理的 Spring MVC/Reactor/WebClient、Vue 3.5.13、Pinia 3.0.2、Vite 6.3.5、Node >=18.20.0、现有 tsx 4.19.4、JUnit/Mockito、Node 原生 node:test。新增 reactor-test 仅用于测试且由现有 Boot BOM 管理版本。

**Spec:** [设计、接口与 A01–A36 验收标准](../specs/2026-10-03-websocket-to-sse-refactor-design.md)。所有任务以该方案为准，执行前读取全文。

## 执行进度（2026-10-05）

任务 1–3 已在 codex/backend-chat-sse 工作区完成，分任务与整体独立审查通过，最终相关回归 175 项全部通过。全量 198 项仍有 9 个原基线已有的数据库环境/测试配置错误。代码尚未提交。勾选表示本阶段实施步骤已完成，不代表全部 A01–A36 已验收。

任务 4–5 的历史阶段结果保持在 [交付记录](../../eval/chat_stream/frontend-tasks-4-5.md)：当时 54 项通过、typecheck 有 6 个既有第三方错误。任务 6 已完成旧协议/依赖/部署清理、两份页面及独立复审修复；任务 7 的负载/资源/真实 Nginx/回归已有实际证据。本轮前端 79 项、typecheck/只检查 lint/build 通过；后端相关 180 项通过，全量 205 项仍有 9 个既有错误、2 个代理条件跳过，真实代理另跑 2 项通过。最新直连/代理首正文 p95 为 149/53 ms，默认心跳覆盖 95 秒工具空窗。本轮生产/开发/两份HTML浏览器、真实Gemini/Markdown与历史恢复已完成；A01–A36实际证据已记录，最后live后安全复扫/临时进程清理已通过。九个既有全量错误和真实基础设施未验边界保留。见 [完整矩阵](../../eval/chat_stream/README.md) 与 [任务 6–7](../../eval/chat_stream/tasks-6-7.md)。历史 [任务 1–3](../../eval/chat_stream/backend-tasks-1-3.md) 数量和环境边界保留。

## Global Constraints

- POST `/api/v1/chat/stream` 与 POST `/api/v1/chat/requests/{requestId}/cancel`；JWT 仅使用 Authorization，用户名仅使用认证 Principal。
- 保持 JWT 的 access token 3600000 ms、预刷新阈值 300000 ms、过期宽限 600000 ms、refresh token 604800000 ms；刷新必须验证 token 仍在有效缓存且未撤销，access 缓存/用户集合/黑名单覆盖 exp + 600000 ms，缓存异常拒绝认证。
- 使用显式 conversationId、客户端 UUID requestId；正文拒绝纯空白，长度上限 16000 个 UTF-16 code unit。
- 每个会话最多一个生成请求；终态与先到取消记录保留 300000 ms；本次单实例、同源代理验收，不提供断点续传或自动重放。
- 生成总期限 300000 ms，emitter 期限 320000 ms，心跳 15000 ms，完成保存事务期限 10 秒。
- 活跃请求最多 100；总记录最多 10000；每用户记录最多 200；工作池 16 线程/1024 排队任务；每请求待发送业务事件最多 64。
- 前端未完成单事件缓冲最多 262144 个 UTF-16 code unit；event/JSON type 一致，seq 从 1 连续递增，重复 seq 忽略。
- 只有正常完成并获得 COMPLETING 处理权才能保存；MySQL 成功先于 finished，Redis 更新失败不重复保存且不撤销成功。
- 取消前后两阶段同一订阅链；不在客户端内部或第二阶段独立 subscribe，不用每次停止创建 Thread，不在 Netty 线程阻塞发送或检索。
- 不新增数据库表，不升级主技术栈，不实现 MCP 或多工具重构；旧 WebSocket 只允许开发期间暂存，最终删除聊天运行入口。
- 验证日志和测试只能使用专用测试 token；敏感信息不得写入 URL、事件或验收产物。

## Review Focus

1. **R1：取消先到、订阅后登记。** 先到取消必须阻止生成，迟到的 Disposable 必须销毁；测试归属 Task 1/3。
2. **R2：注销、切换之后返回的异步结果。** chunk、finally 和 New-Token 均不能影响新的消息或登录会话；测试归属 Task 4/5。
3. **R3：取消与提交同时发生。** 只有一个状态赢家，COMPLETING 边界可预测，取消路径不保存；测试归属 Task 3。
4. **R4：没有正文的工具阶段或真实下游断线。** 仍有心跳并可取消，取消后不启动第二轮；测试归属 Task 2/3/7。
5. **R5：MySQL 已提交、Redis 失败、完成通知丢失。** 保存不重复，历史可恢复，不把已提交成功错误地重跑；测试归属 Task 2/3。

## 文件与任务边界

| 任务 | 创建 / 修改位置 | 责任 |
| --- | --- | --- |
| 1 | `model/chat/*`、`service/chat/ChatRequestContext.java`、`ChatRequestRegistry.java`、`ChatRequestException.java`、`config/ChatStreamingProperties.java` | 固定模型、状态、去重、取消标记与容量。 |
| 2 | `client/ModelDelta.java`、`client/DeepSeekClient.java`、`service/ChatHandler.java`、`ConversationService.java`、`ConversationMessageService.java`、`pom.xml` | 可取消的模型/RAG 流、显式会话和持久化边界。 |
| 3 | `service/chat/ChatStreamService.java`、`config/ChatStreamingConfig.java`、`controller/ChatController.java`、`ChatStreamingExceptionHandler.java`、`config/SecurityConfig.java`、JWT/TokenCache 相关类 | HTTP SSE、独立取消、流内/流前错误、心跳、刷新撤销校验与清理。 |
| 4 | `frontend/src/utils/sse.ts`、`frontend/src/service/api/chat-stream.ts`、`frontend/package.json` | 字节解析、fetch、响应/认证处理、原生测试入口。 |
| 5 | `frontend/src/store/modules/chat/chat-session.ts`、现有 chat store、chat 页面模块、auth store、`frontend/src/typings/api.d.ts` | 消息归属、UI 状态、取消、切换/注销竞态。 |
| 6 | 旧 WS 文件、前端环境/代理/类型/依赖、两份 HTML、静态 helper、`docs/nginx.conf`、`application.yml`、README | 完整迁移、部署与旧协议清理。 |
| 7 | Java HTTP/负载/代理测试与测试 fixture、`docs/eval/chat_stream/README.md` | 回归、真实网络、资源/性能与证据归档。 |

Java 路径前缀为 `src/main/java/com/yizhaoqi/smartpai/`；测试前缀为 `src/test/java/com/yizhaoqi/smartpai/`。下文 Test 路径均是需新增或更新的准确文件名，不能当作已经存在或已经通过。

实施前记录 `git status` 和已有测试基线；当前已存在未跟踪的 research/MCP 设计材料不是本次代码交付内容。各任务必须保留独立可检查的差异和验证结果。

---

## Task 1：固定接口模型与请求状态管理

**Files:**

- Create: `model/chat/ChatCommand.java`、`model/chat/ChatStreamRequest.java`、`model/chat/ChatEventEnvelope.java`、`model/chat/ChatOutput.java`。
- Create: `service/chat/ChatRequestContext.java`、`service/chat/ChatRequestRegistry.java`、`service/chat/ChatRequestException.java`。
- Create: `config/ChatStreamingProperties.java`。
- Test: `service/chat/ChatRequestRegistryTest.java`、`config/ChatStreamingPropertiesTest.java`。

**Interfaces:**

- `ChatCommand(String username, String conversationId, UUID requestId, String message)`：不可变已认证命令。
- `ChatStreamRequest(String conversationId, UUID requestId, String message)`：HTTP DTO。
- `ChatEventEnvelope(String type, UUID requestId, String conversationId, long seq, Map<String,Object> data)`：SSE JSON。
- `ChatOutput(String type, Map<String,Object> data)`：仅支持 chunk/tool_progress，尚未分配 seq。
- `ChatRequestException` 提供 `getErrorCode(): String`、`getStatus(): HttpStatus` 和安全的 message。
- `ChatRequestContext.State` 为 REGISTERED/RUNNING/COMPLETING/FINISHED/CANCELLED/FAILED/TIMED_OUT，提供原子 `tryTransition(State expected, State next): boolean`。
- Context 提供 `attachUpstream(Disposable): void`、`onCancel(Runnable): void`、幂等资源清理。取消已成功时后续 attach/onCancel 必须立即生效。
- Registry 提供 `register(ChatCommand): ChatRequestContext`、`cancel(String username, UUID requestId): CancelResult`、`finish(ChatRequestContext, State): void`、`purgeExpired(): void`；`CancelResult.requestId()`、`state()`、`changed()`。
- 配置暴露设计第 8 节同名参数。测试可注入时钟和配置，不依赖等待五分钟。

- [x] **1. 写失败测试**：`cancelBeforeRegisterRejectsGeneration`、`lateDisposableIsDisposed`、`sameIdWithDifferentPayloadIsDuplicate`、`singleFlightPerConversation`、`differentConversationCanRun`、`foreignIdCannotCancelOwner`、`oldCleanupCannotReleaseNewLease`、`capacityAndTtlAreBounded`、`invalidConfigurationIsRejected`。

```java
assertEquals(CANCELLED, registry.cancel("alice", requestId).state());
ChatRequestException error = assertThrows(ChatRequestException.class,
        () -> registry.register(command));
assertEquals("REQUEST_CANCELLED", error.getErrorCode());
```

- [x] **2. 验证失败**：运行 `mvn "-Dtest=ChatRequestRegistryTest,ChatStreamingPropertiesTest" test`。失败应来自缺少目标能力，而非缺失外部数据库或网络。
- [x] **3. 实现模型、合法状态转换、原子登记/取消、短效标记与容量**：登记、同 ID 检查、会话租约和容量检查须在同一短临界区完成；每次取消只查当前用户命名空间。COMPLETING 返回实际状态，禁止回退成取消。
- [x] **4. 实现轻量终态保留和清理**：不得保留网络连接/回答全文；活动请求不因 TTL 消失；清理旧租约时比较 context 身份。未知取消同样受到容量限制。
- [x] **5. 重跑上述测试并记录结果**。验收映射：A09–A11、A14–A16、A28–A29 的注册表部分。
- [x] **6. 记录任务差异与提交检查点**。建议提交说明：`feat(chat): add request lifecycle and cancellation registry`。

## Task 2：可取消的模型流、RAG 编排与保存

**Files:**

- Create: `client/ModelDelta.java`。
- Modify: `client/DeepSeekClient.java`、`service/ChatHandler.java`、`service/ConversationService.java`、`service/ConversationMessageService.java`、`pom.xml`。
- Test: `client/DeepSeekClientStreamingTest.java`、`service/ChatHandlerStreamingTest.java`、现有 `ChatHandlerHistoryTest.java`、`ConversationServiceHistoryTest.java`、`ConversationMessageServiceTest.java`。
- Create test support: `support/MockModelSseServer.java`（JDK HttpServer，支持分帧、暂停、错误、断线计数）。

**Interfaces:**

- `ModelDelta(Kind kind, String value)`；Kind 为 CONTENT/TOOL_CALL_ID/TOOL_CALL_ARGUMENTS。
- `DeepSeekClient.streamWithTools(List<Map<String,Object>> messages, List<Map<String,Object>> tools): Flux<ModelDelta>`。
- `DeepSeekClient.streamResponse(List<Map<String,Object>> messages): Flux<String>`。
- `ChatHandler.generateReply(ChatCommand): Flux<ChatOutput>`：冷流，只编排文本/工具进度，不发 HTTP 终态，不持久化。
- `ChatHandler.persistCompletedTurn(ChatCommand, String fullText): void`：MySQL 错误必须传播，缓存更新失败仅降级。
- `ConversationService.requireOwnedConversation(String username, String conversationId): Conversation`、`loadHistoryForChat(String username, String conversationId): List<Map<String,String>>`。
- `ConversationMessageService.appendTurn(...)` 保留现有签名和单事务，设置事务 timeout=10 秒。

- [x] **1. 写失败测试**：`publisherIsCold`、`firstRoundContentArrivesBeforeModelCompletion`、`doneEndsModelStream`、`cancelClosesUpstreamHttp`、`cancelDuringSearchNeverStartsSecondModel`、`cancelDuringSecondModelCancelsCurrentHttp`、`explicitConversationIgnoresCurrentPointer`、`historyReadDoesNotSwitchGlobalConversation`。

```java
StepVerifier.create(reply.take(1))
        .assertNext(output -> assertEquals("chunk", output.type()))
        .verifyComplete();
assertEquals(0, stub.secondModelCalls());
// stub 第一轮仍等待控制信号，证明正文没有等到全轮结束才返回。
```

- [x] **2. 写保存/缓存失败测试**：`mysqlFailurePropagates`、`redisFailureAfterCommitDoesNotAppendAgain`、`historyRedisFailureFallsBackToMergedMysqlHistory`；保留原有预算、summary 信任边界、原始历史顺序与 CAS 工作集测试。
- [x] **3. 运行失败测试**：`mvn "-Dtest=DeepSeekClientStreamingTest,ChatHandlerStreamingTest,ChatHandlerHistoryTest,ConversationServiceHistoryTest,ConversationMessageServiceTest" test`。必要时先添加 BOM 管理的 reactor-test 测试依赖。
- [x] **4. 新增 DeepSeekClient 冷 Flux 重载**：沿用供应商 SSE 解码；[DONE] 正常终止；非法业务响应作为流错误，不再只记录后跳过。统一取消由 Flux 订阅传播。为保证各阶段可编译，旧 callback+void 重载和旧 processMessage 暂时保留，仅供旧 WS 入口调用；新 SSE/RAG 链路禁止调用它们，Task 6 删除。仍有调用者的无关摘要 API 保留。
- [x] **5. 组合两轮 RAG**：第一轮 content 立即输出，tool 参数累积；正常结束后按是否需搜索连接第二轮。阻塞搜索/历史操作放在有界 worker；取消不允许迟到结果触发第二轮。输出工具进度但不暴露工具内部数据。
- [x] **6. 拆开保存与生成**：迁移所有 WebSocket 发送逻辑之前，先使业务返回 ChatOutput；保存由 Task 3 在终止边界调用。loadHistoryForChat 复用遗留与原始消息合并逻辑，不调用 switchConversation；MySQL 保存成功后缓存失败不二次保存。
- [x] **7. 重跑测试并检查新链路无内部独立 subscribe**；旧入口的兼容调用应仍可编译，且不得承担新 SSE 的保存与终态。验收映射：A01–A02、A11–A13、A20、A25–A27、A36 的 RAG/历史部分。
- [x] **8. 记录任务差异与提交检查点**。建议提交说明：`refactor(chat): expose cancellable model and RAG streams`。

## Task 3：SSE HTTP、终态、心跳与认证

**Files:**

- Create: `service/chat/ChatStreamService.java`、`config/ChatStreamingConfig.java`、`controller/ChatStreamingExceptionHandler.java`。
- Modify: `controller/ChatController.java`、`config/SecurityConfig.java`、`config/JwtAuthenticationFilter.java`、`utils/JwtUtils.java`、`service/TokenCacheService.java`。
- Test: `service/chat/ChatStreamServiceTest.java`、`controller/ChatControllerSseTest.java`、`controller/ChatStreamingHttpTest.java`、现有 `utils/JwtUtilsRefreshTest.java`、新增 `service/TokenCacheServiceTest.java`。
- Create test support: `support/ChatStreamingTestApplication.java`（嵌入式 Servlet + 真实安全过滤链，外部 DB/Redis/搜索依赖用测试替身）、`support/RecordingSseEmitter.java`（捕获事件、注入 IOException，提供 awaitTerminal/eventsOfType）。

**Interfaces:**

- `ChatStreamService.open(ChatCommand): SseEmitter`：返回前完成会话校验与原子登记，模型订阅在有界 worker 中执行。
- `ChatStreamService.cancel(String username, UUID requestId): CancelResult`：使用 Task 1 的身份范围和状态语义。
- `ChatController.stream(ChatStreamRequest, Principal): ResponseEntity<SseEmitter>`；`cancel(UUID requestId, Principal): ResponseEntity<Map<String,Object>>`。
- 构造注入 Task 1/2 的服务、配置、有界 Executor、定时调度器以及可测试的 `LongFunction<SseEmitter>` 工厂。生产工厂使用指定 timeout；测试工厂捕获事件/注入 IOException。
- 异常处理返回设计第 4 节 JSON；SSE envelope/错误 code/终止 status 完全使用设计第 5 节。

- [x] **1. 写失败测试**：`metaPrecedesContent`、`mysqlCommitPrecedesFinishedEvent`、`cancelWinsAndNeverPersists`、`completingWinsAndCancelReturnsCompleting`、`terminalRaceHasOneWinner`（200 次）、`repeatedCompletionDoesNotSaveAgain`、`cancelBeforeSubscriptionDisposesLateHandle`。

```java
// cancel 赢家用 latch 控制；释放正常完成回调后仍不能写历史。
assertEquals(CANCELLED, service.cancel("alice", requestId).state());
releaseModelComplete.countDown();
emitter.awaitTerminal();
verify(handler, never()).persistCompletedTurn(any(), anyString());
assertEquals(1, emitter.eventsOfType("completion").size());
```

- [x] **2. 写生命周期测试**：`heartbeatContinuesDuringToolGap`、`heartbeatDoesNotResetGenerationDeadline`、`disconnectCancelsUpstream`、`ioFailureDoesNotWriteAgain`、`overflowCancelsModel`、`cleanupStopsAllResources`、`shutdownCancelsRunningButDoesNotCancelCommittedTurn`。用注入调度器/时钟代替 sleep 五分钟。
- [x] **3. 写 Controller/安全测试**：设计第 4 节每个错误分支、16000/16001 边界、两个 Principal 的隔离、New-Token 保留、SSE 开始后错误事件、非法 JSON、ASYNC 结束时不会变成 401/403。增加 `revokedExpiredTokenCannotRefresh`、`directRefreshRejectsRevokedToken`、`graceWindowCacheLivesUntilExpPlusTenMinutes`、`cacheFailureCannotIssueFreshToken`；JWT 测试补上真实的 TokenCache mock，使用可控签发时间覆盖宽限边界。拒绝场景断言模型与历史调用为 0。
- [x] **4. 运行失败测试**：`mvn "-Dtest=ChatStreamServiceTest,ChatControllerSseTest,ChatStreamingHttpTest,JwtUtilsRefreshTest,TokenCacheServiceTest" test`。
- [x] **5. 实现唯一订阅和串行 sender**：在 subscribe 前注册取消槽；单个请求只有一个有界待发送队列，64 个业务事件为上限；seq 在发送处统一分配。Netty 与心跳线程不执行阻塞 send；已终止请求丢弃迟到内容。
- [x] **6. 实现提交边界与清理**：待发 chunk 写完并获得 COMPLETING 才保存；保存失败发 error/failed，成功后缓存降级允许 finished。所有 callback 使用幂等清理；IOException 后停止写入，按 Spring 错误回调清理连接。正常完成取消生成 timer，emitter timeout 保留作兜底。
- [x] **7. 实现两个 HTTP 入口与认证错误 JSON**：去掉 ChatController 的 WebSocket 继承；使用 Principal；为新入口配置明确的 401 JSON entry point。正常 REQUEST 必须认证；正确处理内部 ASYNC dispatch，不能通过 permitAll 放开外部 SSE 请求。canRefreshExpiredToken 与 refreshToken 必须检查有效 token 缓存/黑名单；TokenCache 的检查异常不能降级成“未撤销”。缓存、用户 token 集合与撤销标记覆盖完整 600000 ms 宽限期，不改变 JWT 有效期数值。
- [x] **8. 执行真实 HTTP 断线测试**：使用嵌入式 Servlet 和 MockModelSseServer，读一个 chunk 后关闭下游；由下一次写/心跳观察取消与上游关闭，不能用 MockMvc 的 response 内容代替此证据。
- [x] **9. 重跑测试并记录结果**。验收映射：A05–A08、A12–A20、A25–A29、A35–A36 的服务/HTTP 部分。
- [x] **10. 记录任务差异与提交检查点**。建议提交说明：`feat(chat): serve SSE with cancellation and durable completion`。

## Task 4：前端 SSE parser 与 fetch 传输

**Files:**

- Create: `frontend/src/utils/sse.ts`、`frontend/src/utils/sse.test.ts`。
- Create: `frontend/src/service/api/chat-stream.ts`、`frontend/src/service/api/chat-stream.test.ts`。
- Modify: `frontend/package.json`（添加 test:chat 命令，不新增运行库）。

**Interfaces:**

- `SseFrame { event: string; id: string; data: string }`。
- `createSseParser(onFrame: (frame:SseFrame)=>void, maxEventChars=262144): { feed(bytes:Uint8Array):void; finish():void }`。
- `ChatStreamInput { conversationId:string; requestId:string; message:string }`。
- `ChatEventEnvelope { type:string; requestId:string; conversationId:string; seq:number; data:Record<string,unknown> }`。
- `ChatTransportOptions` 提供 baseURL、getAuthorization、onNewToken、onEvent、signal 与可注入 fetchImpl；这些函数不直接 import Vue/Pinia store。
- `streamChat(input:ChatStreamInput, options:ChatTransportOptions): Promise<{ status:'finished'|'cancelled'|'failed'|'timed_out' }>`。
- `cancelChatRequest(requestId:string, options:ChatCancelOptions): Promise<{ requestId:string; status:string }>`：使用独立信号，返回设计第 4.2 节实际状态。

- [x] **1. 写失败测试**：`everyByteBoundaryPreservesUnicode`、`multipleEventsInOneRead`、`crlfAndCrAreSupported`、`multilineDataAndComments`、`incompleteEventAtEofIsDiscarded`、`bufferLimitIsEnforced`。将含中文和 emoji 的完整 fixture 在每个字节位置拆分，结果应一致。

```typescript
const bytes = new TextEncoder().encode('event: chunk\ndata: 中文😀\n\n');
for (let cut = 1; cut < bytes.length; cut++) {
  const frames: SseFrame[] = [];
  const parser = createSseParser(frame => frames.push(frame));
  parser.feed(bytes.slice(0, cut)); parser.feed(bytes.slice(cut)); parser.finish();
  assert.equal(frames.length, 1);
  assert.equal(frames[0].data, '中文😀');
}
```

- [x] **2. 写 fetch 失败测试**：`postUsesCurrentAuthorizationAndCorrectBaseUrl`、`newTokenHandledBeforeBody`、`httpJsonErrorDoesNotEnterParser`、`wrongContentTypeIsRejected`、`missingCompletionIsInterrupted`、`knownPayloadAndSequenceAreValidated`、`duplicateSequenceDoesNotAppendAgain`、`unknownTypeAdvancesCursorWithoutUiEffect`、`abortDoesNotReplayPost`、`cancelUsesIndependentSignal`。
- [x] **3. 运行失败测试**：在 frontend 执行 `pnpm exec tsx --test src/utils/sse.test.ts src/service/api/chat-stream.test.ts`。使用已有 Node test/assert 与 fetch/ReadableStream 替身，不安装 Vitest 或浏览器测试框架。
- [x] **4. 实现字节流 decoder/parser 与传输契约**：正确处理分隔符跨 read，EOF 半事件不派发；HTTP 失败不当成流内失败；200 body EOF 只有收到终态才正常结束。生成不自动刷新重放，abort 不作为 finished。
- [x] **5. 添加 script**：`test:chat` 为 `tsx --test src/utils/sse.test.ts src/service/api/chat-stream.test.ts src/store/modules/chat/chat-session.test.ts`；Task 5 新增最后一个文件后使用总入口。
- [x] **6. 重跑测试及 typecheck**。验收映射：A03–A04、A06、A21、A24、A33 的传输部分。
- [x] **7. 记录任务差异与提交检查点**。建议提交说明：`feat(chat): add fetch SSE transport and frame parser`。

## Task 5：Pinia 与页面交互迁移

**Files:**

- Create: `frontend/src/store/modules/chat/chat-session.ts`、`frontend/src/store/modules/chat/chat-session.test.ts`。
- Modify: `frontend/src/store/modules/chat/index.ts`、`frontend/src/store/modules/auth/index.ts`。
- Modify: `frontend/src/views/chat/modules/input-box.vue`、`frontend/src/views/chat/modules/conversation-list.vue`、`frontend/src/views/chat/modules/chat-list.vue`、`frontend/src/views/chat/modules/chat-message.vue`。
- Modify: `frontend/src/typings/api.d.ts`。

**Interfaces:**

- `createChatSession(deps:ChatSessionDependencies): ChatSession`，依赖 Task 4 的可替换 streamChat/cancelChatRequest 与 UI 回调。
- Session 提供 `send(input:ChatStreamInput, assistantMessageId:string):Promise<void>`、`stop():Promise<void>`、`detach(reason:'switch'|'delete'|'logout'|'dispose'):void`。
- 会话控制器持有请求 identity/epoch/seq；依赖回调含 `onEvent`、`onStatus`、`onClearActive`、`onNewToken`，回调仅在 identity 仍有效时触发。
- Pinia 暴露 `sendMessage():Promise<void>`、`stopGeneration():Promise<void>`、`disposeActiveRequest(reason):void`；保持现有会话列表/新建/切换/删除 API。
- Api.Chat.Message 新增本地 messageId、可选 requestId/conversationId、扩展状态及 errorReason；加载历史时生成本地 ID，不改数据库结构。

- [x] **1. 写失败测试**：`fastResponseHasRegisteredAssistant`、`doubleSendCreatesOneRequest`、`lateConversationCreationCannotStartChatAfterSwitch`、`oldEventCannotModifyNewConversation`、`oldFinallyCannotClearNewActive`、`logoutLateNewTokenCannotRestoreSession`、`cancelFailureStillClosesLocalStream`、`completingCancelKeepsReadingOriginalStream`、`errorKeepsPartialTextVisible`。

```typescript
session.detach('switch');
await session.send(newInput, 'assistant-new');
deliverOldChunkAndFinally();
assert.equal(activeRequestId(), newInput.requestId);
assert.equal(messageContent('assistant-new'), '');
```

- [x] **2. 运行失败测试**：frontend 执行 `pnpm exec tsx --test src/store/modules/chat/chat-session.test.ts`。依赖函数全部注入，测试不靠 Vue 自动导入和真实浏览器。
- [x] **3. 实现 session identity 和操作顺序**：send 之前登记 active，detach 先撤销 UI 写入资格再取消/abort；旧 finally、chunk 和 New-Token 均被身份检查拦住。stop 收到 completing 时等待原流，取消失败则关闭本地并展示未确认状态。
- [x] **4. 迁移 Pinia 与组件**：删除 useWebSocket、wsData watcher 与获取内部 token；在 await ensureConversation 前设发送闸门，异步建会话返回后再次检查视图/login epoch。按 messageId 更新消息，删除依赖“最后一条 assistant”的接收逻辑。
- [x] **5. 接入切换/删除/注销清理**：注销前捕获取消请求所需认证，立即 abort 本地，不阻塞退出；切换/新建/删除走统一 disposeActiveRequest。路由离开清理请求，刷新依靠断线生命周期兜底。
- [x] **6. 更新界面**：展示 pending/loading/cancelling/finished/cancelled/error；错误和已停止保留部分正文；工具进度不拼接正文；完成后只刷新会话列表。
- [x] **7. 跑 `pnpm test:chat`、`pnpm typecheck`**，浏览器检查发送、停止、快速切换、删除、注销、认证失效与刷新历史。验收映射：A11、A21–A24、A25 的页面部分、A33。
- [x] **8. 记录任务差异与提交检查点**。建议提交说明：`refactor(chat): migrate UI to request scoped SSE sessions`。

## Task 6：旧协议清理、静态测试页与部署

实施、独立复审及两份实际页面浏览器已完成，见本轮browser-acceptance.md；最终整体验收保持后续门槛。

**Files:**

- Delete: `handler/ChatWebSocketHandler.java`、`config/WebSocketConfig.java`、未引用的 `frontend/src/sockert.js`。
- Modify: `controller/ChatController.java`、`service/ChatHandler.java`、`config/SecurityConfig.java`、`pom.xml`、`src/main/resources/application.yml`。
- Modify: `frontend/.env.test`、`frontend/.env.prod`、`frontend/src/typings/app.d.ts`、`frontend/build/config/proxy.ts`、`frontend/package.json`、`frontend/pnpm-lock.yaml`。
- Modify: `src/main/resources/test.html`、`src/main/resources/static/test.html`、`docs/nginx.conf`、`README.md`。
- Create: `src/main/resources/static/chat-stream.mjs`（两份静态测试页共用，浏览器原生 fetch/SSE helper）。
- Test: Task 3 的旧入口负向检查；新增 `frontend/src/service/api/static-chat-stream.test.ts` 并加入 test:chat，验证静态 helper 的分帧、取消与异常 EOF。

**Interfaces:**

- 静态 helper 导出 `streamChat`、`cancelChatRequest`；使用与 Task 4 相同的 HTTP/事件契约及 parser 行为，测试页提供显式会话 ID 和 Authorization。
- HTTP API 和 envelope 不再变动；部署参数使用设计第 8 节值。开发仍走主 API baseURL，生产走 `/api/v1`。

- [x] **1. 写负向检查**：旧 `/chat/{token}` 无法完成 WebSocket 握手；`/api/v1/chat/websocket-token` 不返回指令 token。配置搜索和测试页检查应首先暴露旧入口/旧调用。
- [x] **2. 更新静态页并写失败测试**：复用 helper，测试页保留问答/停止/历史能力，使用显式当前会话而非 token URL。静态 helper 不允许另立一套错误/取消协议。
- [x] **3. 删除旧实现和专用依赖**：移除旧 processMessage/executeToolAndRespond、callback+void 模型重载、WebSocketSession/import、stopFlags、两秒停止清理线程、内部命令 token、握手与放行规则；确认无其他运行调用后删除 starter-websocket 和 ws/@types/ws。保留 VueUse 的其他功能。
- [x] **4. 清理 env/types/proxy**：删除聊天 ws service key，OtherBaseURLKey 移除 ws；检查生产/开发 HTTP 地址仍正确。移除聊天代理 Upgrade，仅保留需要的 HTTP 代理。
- [x] **5. 配置有界 worker、timer、心跳和超时**：实现自动停止与关闭清理，不使用无限 emitter timeout。加入可覆盖默认配置，并验证非法值启动失败。
- [x] **6. 更新 Nginx 与 README**：独立 stream location 关闭 buffering/cache/gzip，90 秒 idle timeout；说明请求/取消样例、终态、局部文本策略、单实例限制和配套发布/回滚。
- [x] **7. 搜索运行代码**：`rg -n --no-ignore-vcs 'useWebSocket|new WebSocket|WebSocketSession|TextWebSocketHandler|_internal_cmd_token|websocket-token|proxy-ws' src/main/java src/main/resources frontend/src frontend/build frontend/.env.test frontend/.env.prod`。预期聊天运行代码无匹配；测试负向断言和历史文档中协议名称可保留。不手工编辑产物 dist。
- [x] **8. 跑前后端相关测试与构建、浏览器验证两份页面、检查 Nginx 配置**。验收映射：A18、A30、A33–A35、A36 的部署部分。
- [x] **9. 记录任务差异与提交检查点**。建议提交说明：`refactor(chat): remove websocket endpoints and configure SSE deployment`。

## Task 7：完整回归、真实代理与验收证据

自动化、真实代理、实际网页、真实Gemini/Markdown/历史与矩阵已记录；整合生产独立审查APPROVED。步骤9最后安全复扫/临时进程清理已通过；全量九个既有错误不称绿色。步骤 4 勾选表示已执行并如实记录全量九个既有错误，并非全量绿色。

**Files:**

- Create: `controller/ChatStreamingLoadTest.java`、`controller/ChatStreamingProxyTest.java`。
- Update: `controller/ChatStreamingHttpTest.java`、`support/ChatStreamingTestApplication.java`、`support/MockModelSseServer.java`。
- Create: `docs/eval/chat_stream/README.md`，执行后在此目录保存实际验收记录；不要在实施前填写通过。

**Interfaces:**

- 测试 fixture 提供 10 个测试用户/独立会话、合法测试 JWT、可控工具等待、50 条并发回答与资源计数。
- `chat.acceptance.backend-port` 指定嵌入式测试后端端口，默认 0；`chat.acceptance.proxy-url` 指定已配置的真实 Nginx URL。ProxyTest 未提供该参数可跳过，但发布验收必须显式执行它并通过。
- 测试应用只存在 test 源目录，不新增匿名生产测试入口，不连接真实计费模型。

- [x] **1. 写/执行负载与资源测试**：50 并发，各 200 个 chunk、20 ms 间隔；记录首正文 p95 并断言 ≤1000 ms、无意外失败/串流、活跃归零。10 用户合计 1000 次串行短请求，验证 worker/timer/buffer/租约清理，推进测试时钟后保留记录归零。
- [x] **2. 写/执行 ProxyTest**：Nginx 指向指定测试后端；通过代理观察分帧和首正文 p95 ≤1000 ms。默认参数下执行超过 90 秒工具空窗，观察持续心跳与后续正常回答，证明代理不会批量缓存或 idle 断开。
- [x] **3. 执行新后端自动化**：仓库根目录运行以下命令，预期 0 failures / 0 errors；ProxyTest 另行执行。

```powershell
mvn "-Dtest=ChatStreamingPropertiesTest,ChatRequestRegistryTest,DeepSeekClientStreamingTest,ChatHandlerStreamingTest,ChatStreamServiceTest,ChatControllerSseTest,ChatStreamingHttpTest,ChatStreamingLoadTest,TokenCacheServiceTest" test
```

- [x] **4. 执行旧链路回归**：历史/预算/压缩与 JWT 测试须全部通过；外部集成环境具备条件后执行全量 `mvn test`。若存在原有环境故障，分别记录基线与本次结果，不能把未运行的测试写为通过。

```powershell
mvn "-Dtest=ChatHandlerHistoryTest,ConversationServiceHistoryTest,ConversationMessageServiceTest,ConversationCompressionServiceTest,ContextBudgetServiceTest,JwtUtilsRefreshTest" test
```

- [x] **5. 执行前端检查**：在 frontend 分别运行以下命令。lint 使用无 --fix 的检查，避免无关自动改写。

```powershell
pnpm test:chat
pnpm typecheck
pnpm exec eslint src/utils/sse.ts src/utils/sse.test.ts src/service/api/chat-stream.ts src/service/api/chat-stream.test.ts src/service/api/static-chat-stream.test.ts src/store/modules/chat src/store/modules/auth/index.ts src/views/chat src/typings/api.d.ts src/typings/app.d.ts
pnpm build
```

- [x] **6. 通过真实 Nginx 验收**：先让本地 Nginx 使用方案中的 stream 配置，并指向测试后端 8081；运行下列专项。要求实际通过，跳过不算完成 A30。

```powershell
mvn "-Dtest=ChatStreamingProxyTest" "-Dchat.acceptance.backend-port=8081" "-Dchat.acceptance.proxy-url=http://localhost:8080" test
```

- [x] **7. 浏览器端到端验收**：按 A01/A02/A12/A13/A22/A23/A33/A34 操作，保留网络事件、历史结果和必要的画面记录。验证刷新后成功历史、局部回答政策、取消失败文案和快速操作无串流。
- [x] **8. 填验收矩阵**：为 A01–A36 每行给出实际状态、测试名/记录链接、版本和环境。记录 TTFT p95、取消传播、断线检测时点、资源计数、应用关闭结果；故障要可复现。
- [x] **9. 最终检查**：没有聊天 WS 运行入口、没有两个协议混用、没有未验证的必验项、没有凭据泄漏、没有把研究文件或构建产物混入交付。
- [x] **10. 记录最终验证结果与提交检查点**。建议提交说明：`test(chat): verify SSE cancellation isolation and proxy streaming`。

## 交付完成条件

下列实现/证据条件及Task7步骤9最后安全复扫/临时进程清理均已完成；未提交/推送/线上发布，真实DB/Redis/ES与原全量九个错误仍明确保留。

- [x] 设计 D01–D12 均有对应实现与证据，A01–A36 全部通过。
- [x] 所有任务复选框根据实际完成情况更新，自动化命令记录失败数；真实 Nginx 与浏览器验证没有被单元测试替代。
- [x] README、环境示例、两个静态页和部署/回滚说明与代码一致；没有新增 DB schema。
- [x] 前端、后端、代理可作为一个版本配套发布；单实例运行限制明确。
- [x] 交付记录说明实际变更、验证结果与仍然成立的边界：第三方计费行为、断线检测延迟、提交中丢失完成通知、保留窗口之外的去重。

本计划编写时只产出文档；当前实施与运行记录见上方进度及验收矩阵。任务1–7/A01–A36按记录的受控验收边界完成。尚未提交/推送/线上部署；既有全量九个错误、未运行真实基础设施及供应商计费/多实例/窗口外去重限制不因勾选而消失。
