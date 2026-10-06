# ShadowRAG 聊天链路去除 Flux Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 将聊天和 DeepSeek 摘要调用改成普通 Java HTTP 读取与回调，保留现有 SSE、搜索、权限、取消和保存行为。

**Architecture:** 先增加可独立测试的普通 HTTP 读取组件，暂不改变现有生产入口。随后在同一个阶段切换 DeepSeekClient、ChatHandler、请求资源管理和 SSE 任务入口，并分离生成与发送线程池。完整验收后优先实施知识库 ReAct，其他 HTTP 客户端和依赖清理后移。

**Tech Stack:** Java 17、Spring Boot 3.4.2 / Spring MVC、JDK HttpClient、SseEmitter、Jackson、JUnit 5、Mockito。

**Spec:** [去除 Flux 改造设计](../specs/2026-10-05-remove-flux-design.md)。执行者须同时阅读设计与本计划。

日期：2026-10-06。状态：阶段0～3完成，独立审查及修复后的回归通过；完整生产启动仍受外部MySQL不可用限制。执行基线为 `a96b80d`；工作区原有设计改动已保留，当前代码未提交。实际结果见 [阶段3验收](../../eval/chat_stream/remove-flux-phase-3-acceptance.md)。

用户于2026-10-06授权执行阶段0～3。该授权不包含ReAct、其他客户端迁移、提交或推送。完成指定阶段后停止，保留可继续记录；执行技能要求的最终只读独立审查已单独开展。

2026-10-06 优先级更新：用户希望聊天去除 Flux 后尽快实现 ReAct，MCP 暂不处理。新的主顺序是 **0 → 1 → 2 → 3 → R1 → R2 → R3**；客户端清理阶段4～6暂缓，不作为ReAct前置条件。R阶段的设计见 [知识库 ReAct 接续设计](../specs/2026-10-06-knowledge-base-react-design.md)。

## Global Constraints

- Java 17；不升级 Spring Boot，不引入虚拟线程、OkHttp 或新的 Agent 框架。
- 保留 `POST /api/v1/chat/stream`、POST 取消接口和现有鉴权；不恢复 WebSocket。
- 保留 `meta/chunk/tool_progress/error/completion` 和现有错误码、seq、requestId、conversationId。
- 阶段0～3保留目前最多搜索一次的流程；ReAct按独立接续设计进入R阶段，不在去除Flux过程中提前实施；MCP暂不处理。
- 保留带权限的 `searchWithPermission(query, command.username(), 10)`。
- 缺少 `[DONE]` 或模型流损坏时不继续搜索、不保存半截答案；不自动重试模型生成。
- 保留完成提交边界和 MySQL 提交后 Redis 失败不重复追加的行为。
- 总生成超时 `300000ms`、emitter 超时 `320000ms`、心跳 `15000ms`；emitter 至少多 `10000ms`。
- 生成池默认 `16` 线程、队列 `64`；发送池沿用 `16` 线程、队列 `1024`；定时线程 `2`。
- 模型单行和单帧各 `1048576` 字节；当前请求累计正文 `1048576` 字符；工具参数 `262144` 字符；摘要 JSON `1048576` 字节；错误正文 `16384` 字节。
- 阶段 0～3 不删除 WebFlux starter 或 reactor-test，其他客户端还需要它们。
- 使用本地模拟供应商，不默认调用真实模型、修改生产配置、部署或恢复旧文件。
- 每阶段均须保持可编译和可运行，不把“不完整的接口迁移”当作阶段交付。

## Review Focus

1. `/v1` 基础路径、HTTP 代理和代理取消不能因更换 HTTP 客户端失效：由阶段 1 与阶段 2 验证。
2. 请求取消后才返回的响应体必须关闭，不能只 cancel future：由阶段 1 验证资源竞争，阶段 3 验证真实连接。
3. 所有生成线程被静默模型占用时，心跳、取消和终止仍能发送：由阶段 2 验证池隔离，阶段 3 验证真实 SSE。
4. 完成保存不能被正常资源清理误中断；Redis 失败不能重复写入：由阶段 2 与阶段 3 验证。
5. 限额检查要覆盖无换行、无 Content-Length、跨 UTF-8 分片的数据，不能先读完整再检查：由阶段 1 验证。

---

## 1. 整体执行顺序

| 阶段 | 本次做什么 | 阶段结束时能交付什么 | 依赖 |
| --- | --- | --- | --- |
| 0 | 建立当前行为基线 | 确定现有成功/失败集合和运行环境，业务不变 | 无 |
| 1 | 普通 HTTP 读取、SSE 解析和取消资源 | 新组件通过本地测试，原聊天仍正常运行 | 0 |
| 2 | 一次完整切换聊天链路 | 生产聊天不再用 Flux/Mono/Disposable/Scheduler | 1 |
| 3 | 验收取消、超时、保存和资源释放 | 聊天链路可作为独立完成节点 | 2 |
| R1 | 工具调用列表与工具消息预算 | 可以正确组装和回填多轮/多调用的模型消息 | 3 |
| R2 | 普通Java循环与回合SSE | 模型能连续搜索，并实时展示回合正文和工具进度 | R1 |
| R3 | ReAct场景验收 | 多次检索、结束条件、权限、取消和最终保存通过 | R2 |
| 4（暂缓） | 迁移 Embedding 与 Reranker | 检索相关客户端改普通调用 | 3；用户另行安排 |
| 5（暂缓） | 迁移 MinerU | 文档解析与健康检查改普通调用 | 4 |
| 6（暂缓） | 删除依赖并做整体验收 | 去掉项目直接使用的 WebFlux/Reactor | 5；同时回归已实施的ReAct |

第3阶段验收后进入R阶段，不要求先完成全项目依赖清理。暂缓部分详见 [其他 HTTP 客户端与依赖清理计划](2026-10-06-remove-reactor-clients.md)。它们仍可用于知识库搜索；ReAct的业务控制流程使用普通Java，不新增Flux编排。

阶段 2 的多个业务文件必须一起切换。内部可以逐个编辑和检查，但对外作为一个阶段验收，不能留下“客户端已经改返回 void，调用方还在 subscribe”的断裂状态。

## 2. 文件结构和接口约定

以下路径均相对 `E:/Curzsu/ShadowRAG`。接口名称在执行本计划时固定；若必要调整，先同步本计划和所有调用者。

### 阶段 1 新增组件

| 文件 | 职责 |
| --- | --- |
| `src/main/java/com/yizhaoqi/smartpai/client/ModelRoundResult.java` | 单次完整模型响应的结果，不保存会话 |
| `src/main/java/com/yizhaoqi/smartpai/client/BlockingModelHttpClient.java` | HTTP 请求、逐帧读取、模型 JSON 解码、同步摘要 HTTP |
| `src/main/java/com/yizhaoqi/smartpai/client/ModelSseReader.java` | 有限额的 UTF-8 / SSE 帧读取，不操作 emitter |
| `src/main/java/com/yizhaoqi/smartpai/config/ModelHttpProperties.java` | `deepseek.api` 下新增限额与连接超时配置 |
| `src/main/java/com/yizhaoqi/smartpai/service/chat/ChatGenerationResources.java` | 本次请求的任务、HTTP future、输入流和截止时间 |

`BlockingModelHttpClient` 是 DeepSeekClient 的内部 HTTP 实现，不是另一个对外模型服务。阶段 1 先在测试中直接构造；阶段 2 由 DeepSeekClient 统一调用，不能永久维护两条生产聊天路径。

固定的普通接口：

```java
public record ModelRoundResult(
        String content,
        String toolCallId,
        String toolArgumentsJson,
        String finishReason) {}

// ChatGenerationResources
public ChatGenerationResources(long deadlineNanos);
public void attachGenerationTask(Future<?> task);
public void attachHttpRequest(CompletableFuture<?> request);
public void attachResponseBody(InputStream body);
public void clearHttpRequest(CompletableFuture<?> expected);
public void clearResponseBody(InputStream expected);
public Duration remaining();
public void checkRunning() throws HttpTimeoutException;
public void addContentCharacters(int count, int maximum);
public void stop();
public void finishNormally();
public boolean isStopped();

// ModelSseReader：帧的 data 文本交给回调；调用方处理 DONE 与模型 JSON。
public void read(InputStream body, Consumer<String> onData) throws IOException;

// BlockingModelHttpClient
public BlockingModelHttpClient(
        String apiUrl, String apiKey, String proxyUrl,
        ModelHttpProperties properties, ObjectMapper mapper);
public ModelRoundResult stream(
        Map<String, Object> request, ChatGenerationResources resources,
        Consumer<ModelDelta> onDelta) throws IOException, InterruptedException;
public String postJson(
        Map<String, Object> request, Duration timeout)
        throws IOException, InterruptedException;
```

`checkRunning()` 对停止/中断抛 CancellationException，对截止时间耗尽抛 HttpTimeoutException；上层按已获胜状态分类。`stop()` 关闭当前 HTTP 资源并尝试取消生成任务；`finishNormally()` 只释放已完成资源，不中断生成或持久化线程。`clear*` 按句柄身份移除，不能误清除随后登记的另一轮请求。

`ModelHttpProperties` 采用设计中的六项限额默认值，另固定 `deepseek.api.connect-timeout-ms=10000`；全部验证正数。单次请求等待时间不得超过 `resources.remaining()`。没有完成原因字段但已经收到 DONE 的供应商保持兼容；有明确截断原因则失败。

### 阶段 2 切换接口

```java
// ChatRequestContext 新增普通资源入口，不再 implements Disposable。
public ChatRequestContext(ChatCommand command, long deadlineNanos);
public ChatGenerationResources generationResources();
public void releaseResources();
public boolean resourcesReleased();

// DeepSeekClient：保留模型请求组装和摘要业务配置。
public ModelRoundResult streamWithTools(
        List<Map<String, Object>> messages, List<Map<String, Object>> tools,
        ChatRequestContext context, Consumer<ModelDelta> onDelta);
public ModelRoundResult streamResponse(
        List<Map<String, Object>> messages, ChatRequestContext context,
        Consumer<String> onContent);
public String callSync(String prompt, Duration timeout);

// ChatHandler：只在生成池执行，不负责终止和保存。
public void generateReply(
        ChatCommand command, ChatRequestContext context,
        Consumer<ChatOutput> output);
```

DeepSeekClient 将底层 IOException 转为安全的模型异常；中断保留中断位，取消不伪装成模型错误。callSync 的旧业务签名保留，调用者不用改成异步。

ChatRequestRegistry 在注册时根据 `generationTimeoutMs` 和 System.nanoTime 计算截止时间，传给新构造器，排队期间也计时。保留旧 `ChatRequestContext(ChatCommand)` 构造器供已有简单测试使用，委托默认300000ms；生产注册使用显式截止时间。测试中的固定截止时间必须基于单调时钟，不能把epoch时间直接传入。

## 3. 阶段 0：建立基线

### Task 0：确认现有行为与环境

**Files:**

- Read: 本计划、设计文档、`pom.xml`、`frontend/package.json`。
- Create: `docs/eval/chat_stream/remove-flux-phase-0-baseline.md`。
- Read/Test: 当前模型、聊天、会话历史、压缩、MVC SSE 测试。

**Interfaces:**

- Consumes: 当前代码，不修改生产接口。
- Produces: 测试命令、环境版本、失败集合和当前 Git 基线记录。

- [x] 检查工作区、Java/Maven/Node/pnpm 版本，记录执行时的提交和用户未提交改动；不覆盖改动。
- [x] 运行下方现有定向测试；查看 Surefire 报告，记录总数、失败类和失败用例，不只记录退出码。
- [x] 在 frontend 目录运行 `pnpm test:chat`，记录结果。
- [x] 将结果写入 baseline.md，并确认模型测试使用 loopback 模拟服务器。
- [x] 判断能否进入阶段 1：基础链路测试失败时先定位原因，不自动删除测试或扩展成无关修复。

后端命令在仓库根目录执行，PowerShell 下把 Maven 参数作为完整字符串：

```powershell
mvn '-Dtest=DeepSeekClientStreamingTest,DeepSeekClientProxyTest,ChatHandlerStreamingTest,ChatHandlerHistoryTest,ChatRequestRegistryTest,ChatStreamServiceTest,ChatStreamingPropertiesTest,ChatControllerSseTest,ChatStreamingHeaderOrderTest,ChatStreamingHttpTest,ConversationMessageServiceTest,ConversationServiceHistoryTest,ConversationCompressionServiceTest,ContextBudgetServiceTest' test
```

**退出标准：** 能说明现有行为是否通过；没有把本阶段记成 Flux 已移除。阶段 0 不新增针对未实现接口的测试，也不先实施新客户端。

## 4. 阶段 1：新增普通 HTTP 读取组件

### Task 1.1：可取消的请求资源

**Files:**

- Create: `src/main/java/com/yizhaoqi/smartpai/service/chat/ChatGenerationResources.java`。
- Create/Test: `src/test/java/com/yizhaoqi/smartpai/service/chat/ChatGenerationResourcesTest.java`。

**Interfaces:**

- Consumes: Java Future、CompletableFuture、InputStream、System.nanoTime 单调截止时间。
- Produces: 第 2 节 ChatGenerationResources 接口；暂不改变 ChatRequestContext。

- [x] 先写 `stopClosesCurrentBodyAndCancelsPendingRequest`、`lateBodyAfterStopIsClosed`、`clearingPreviousBodyDoesNotClearNextBody`、`normalFinishDoesNotInterruptWorker` 测试。
- [x] 用 CountDownLatch 控制登记/停止竞争；断言流关闭一次、HTTP 请求取消、任务被停止，而正常结束的 worker 没被 cancel(true)。加入累计正文跨轮次达到 1048576 字符的上限测试。
- [x] 运行 `mvn '-Dtest=ChatGenerationResourcesTest' test`，确认新测试因接口未实现而失败。
- [x] 实现上述普通接口；清理句柄先从短锁内移除，再在锁外关闭；迟到句柄立即释放。
- [x] 再运行同一测试并通过。不得通过只测试 future.isCancelled 来证明关闭了 HTTP。

关键断言示意（句柄与流用可计数测试对象）：

```java
resources.stop();
assertEquals(1, body.closeCount());
assertTrue(request.isCancelled());
resources.attachResponseBody(lateBody);
assertEquals(1, lateBody.closeCount());
// 另一用例：正常完成不能取消工作任务。
normalResources.finishNormally();
assertFalse(workerTask.isCancelled());
```

### Task 1.2：有限额的 SSE 帧读取

**Files:**

- Create: `src/main/java/com/yizhaoqi/smartpai/client/ModelSseReader.java`。
- Create: `src/main/java/com/yizhaoqi/smartpai/config/ModelHttpProperties.java`。
- Create/Test: `src/test/java/com/yizhaoqi/smartpai/client/ModelSseReaderTest.java`。
- Create/Test: `src/test/java/com/yizhaoqi/smartpai/config/ModelHttpPropertiesTest.java`。

**Interfaces:**

- Consumes: InputStream 与第 2 节限额值。
- Produces: `read(InputStream, Consumer<String>)` 和限额配置；不提供前端事件。

- [x] 先写 UTF-8 跨字节、CRLF、注释、多行 data、超长无换行、超大帧、零/负限额测试；输出 data 列表须与输入逻辑帧完全一致。
- [x] 运行 `mvn '-Dtest=ModelSseReaderTest,ModelHttpPropertiesTest' test`，确认未实现时失败。
- [x] 实现增量解码和帧解析；超过字节上限即停止，不能先 readAllBytes 或使用无界行缓冲再检查。
- [x] 回调遇到 DONE 后由调用方终止解析，不继续等 EOF；再运行同一测试并通过。

### Task 1.3：阻塞式模型 HTTP 实现与摘要 HTTP

**Files:**

- Create: `src/main/java/com/yizhaoqi/smartpai/client/BlockingModelHttpClient.java`。
- Create: `src/main/java/com/yizhaoqi/smartpai/client/ModelRoundResult.java`。
- Create/Test: `src/test/java/com/yizhaoqi/smartpai/client/BlockingModelHttpClientTest.java`。
- Create/Test: `src/test/java/com/yizhaoqi/smartpai/client/BlockingModelHttpClientProxyTest.java`。
- Modify/Test support: `src/test/java/com/yizhaoqi/smartpai/support/MockModelSseServer.java`，保留原默认路径，增加可控响应头延迟、普通 JSON 和 `/v1` 路径支持。

**Interfaces:**

- Consumes: Task 1.1 资源句柄、Task 1.2 解析器、现有 ModelDelta。
- Produces: 第 2 节 BlockingModelHttpClient 与 ModelRoundResult；旧 DeepSeekClient 暂不调用它。

- [x] 先写 `contentArrivesBeforeDone`：模拟服务器发送 first 后等待闸门，断言回调已经收到 first，再放行 DONE；不允许通过全量缓存响应伪造流式。
- [x] 先写缺 DONE、length 截断、供应商 error、非 2xx、帧损坏、正常 DONE 后主动关闭、取消前响应头未到及迟到 body 关闭测试。
- [x] 先写 `/v1/chat/completions`、无 API Key、无效代理不回显、代理 CONNECT 和代理连接关闭测试；摘要成功、整体截止时间、无 Content-Length 超额和错误正文上限测试。
- [x] 运行 `mvn '-Dtest=BlockingModelHttpClientTest,BlockingModelHttpClientProxyTest' test` 确认失败后实现普通 HTTP 读取。HTTP client 不使用生成池作为内部 executor，不自动重试。
- [x] 运行所有阶段 1 新测试，再运行阶段 0 基线测试。原聊天必须继续通过，新增组件此时不接管生产。

**阶段 1 退出标准：** 新组件和旧聊天各自通过测试，项目仍能启动；资源清理和 HTTP 读取均不直接依赖 Reactor。此时不宣称生产聊天已经切换。

**阶段记录：** 更新 baseline 文档或增加 `docs/eval/chat_stream/remove-flux-phase-1-result.md`，写清生产入口仍旧、实际测试结果和下一阶段依赖。若用户授权 Git 提交，可将阶段 1 作为一个独立提交；不混入原有用户改动。

## 5. 阶段 2：完整切换聊天链路

### Task 2：客户端、编排、生命周期与线程池一起切换

**Files:**

- Modify: `src/main/java/com/yizhaoqi/smartpai/client/DeepSeekClient.java`。
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`。
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/chat/ChatRequestContext.java`、`ChatRequestRegistry.java`、`ChatStreamService.java`。
- Modify: `src/main/java/com/yizhaoqi/smartpai/config/ChatStreamingConfig.java`、`ChatStreamingProperties.java`。
- Modify: `src/main/resources/application.yml`。
- Modify/Test: `DeepSeekClientStreamingTest`、`DeepSeekClientProxyTest`、`ChatHandlerStreamingTest`、`ChatHandlerHistoryTest`、`ChatRequestRegistryTest`、`ChatStreamServiceTest`、`ChatStreamingPropertiesTest`、MVC SSE 测试。
- Modify/Test support: `ChatStreamingAcceptanceFixture.java`、`ChatStreamingBrowserApplication.java` 以及搜索得到的构造器/方法覆盖点，位于 `src/test/java/com/yizhaoqi/smartpai/support/`。

**Interfaces:**

- Consumes: 阶段 1 的 HTTP 实现、资源和第 2 节固定接口。
- Produces: 普通 generateReply、普通模型回调和两套线程池；ChatController 的对外契约不变。

- [x] 先把核心行为测试改成普通回调与可控生成函数，补上 `blockedGenerationDoesNotBlockSseDrain`、`queuedTaskCancelledBeforeStartNeverCallsModel`、`rejectedTaskReleasesConversationLease` 和 `normalCompletionDoesNotCancelPersistenceThread`。
- [x] 使用新签名运行定向测试，确认失败由旧接口或旧行为导致；允许本阶段内部暂时编译失败，但不能交付失败状态。
- [x] 让 DeepSeekClient 委托 BlockingModelHttpClient，保留请求参数、代理校验、配置型构造入口和 callSync 签名；删除旧响应式模型入口，不留永久适配层。
- [x] 按设计将 ChatHandler 改普通顺序方法。先检查状态再开始历史/模型/检索，检索返回后再检查；第一次流完整结束才开始搜索；异常分类沿用当前安全错误。
- [x] ChatRequestContext 删除 Disposable，并持有 ChatGenerationResources。`releaseResources()` 根据状态选择停止或正常清理，保留原取消回调一次性通知、注册前取消和迟到资源释放语义。
- [x] ChatRequestRegistry 改普通清理调用，不在注册表锁内关闭网络。ChatStreamService 用 FutureTask：先把任务登记到资源对象，再交给生成池执行，消除 submit 后才 attach 的启动竞争。
- [x] 生成正常返回后才标记 generationDone；串行 drain 排空输出后原子进入 COMPLETING，保存一次。取消/超时异常不能再覆盖已获胜终止状态。
- [x] 添加 `chatGenerationExecutor`：16/64，现有 `chatStreamingExecutor` 保持16/1024；删除 `chatStreamingScheduler`。定时器不直接写 emitter，正常清理不 cancel(true) 正在提交的线程。
- [x] 更新全部构造器、Mockito stub、测试覆盖方法和浏览器模拟入口，不能只改 main 源码。保留 MVC transportReady 和 New-Token 等响应头时序测试。
- [x] 将项目相关定向测试跑到通过；对阶段 A 指定文件及测试搜索，无 Flux/Mono/Disposable/Scheduler/WebClient 的直接引用，说明剩余引用属于尚未迁移客户端。

验证命令：

```powershell
mvn '-Dtest=ChatGenerationResourcesTest,ModelSseReaderTest,ModelHttpPropertiesTest,BlockingModelHttpClientTest,BlockingModelHttpClientProxyTest,DeepSeekClientStreamingTest,DeepSeekClientProxyTest,ChatHandlerStreamingTest,ChatHandlerHistoryTest,ChatRequestRegistryTest,ChatStreamServiceTest,ChatStreamingPropertiesTest,ChatControllerSseTest,ChatStreamingHeaderOrderTest,ChatStreamingHttpTest,ConversationCompressionServiceTest,ContextBudgetServiceTest' test
mvn -DskipTests package
```

前一命令验证行为，后一命令验证打包，不得用 skipTests 打包替代测试。

**阶段 2 退出标准：** 一般问答、知识库问答、停止和保存均已接到普通 Java 实现；项目可编译与启动，前端不需要更换协议。该阶段失败时先修复完整链路，不进入其他客户端迁移。

## 6. 阶段 3：完整验收与交付聊天阶段

### Task 3：验证真实 HTTP 取消、线程与事务边界

**Files:**

- Modify/Test: `src/test/java/com/yizhaoqi/smartpai/controller/ChatStreamingHttpTest.java`、`ChatStreamingLoadTest.java`、`ChatStreamingProxyTest.java`。
- Modify/Test: `src/test/java/com/yizhaoqi/smartpai/service/chat/ChatStreamServiceTest.java`、`ChatRequestRegistryTest.java`、`ChatStreamingResourceProbe.java`。
- Modify/Test support: `MockModelSseServer.java`、`ChatStreamingAcceptanceFixture.java`。
- Modify: 出现验收失败时只修改阶段 1～2 的对应实现。
- Create: `docs/eval/chat_stream/remove-flux-phase-3-acceptance.md`。

**Interfaces:**

- Consumes: 已切换的真实 Controller、SSE 发送和本地模型服务器。
- Produces: 可独立停止执行的聊天改造验收结果，不承诺生产吞吐量。

- [x] 先增加或保留断言：响应头尚未到达、正文静默、搜索中、第二次模型读取中取消，均不触发后续请求和保存；用服务器 disconnect/activeConnections 及生成任务退出证明资源释放。
- [x] 配置生成池 1 线程/1 等待位置，让模型阻塞；断言已打开 SSE 能发心跳，取消能进入终止；第三个生成任务被拒绝时会话不永久 busy。
- [x] 用闸门控制完成与取消、超时与断开、迟到 body 与停止的竞争，不用固定长 sleep 构造概率测试。
- [x] 验证 meta 首个、seq 连续、completion 唯一、结束后没有事件；队列溢出取消上游且不保存。
- [x] 验证 MySQL 保存失败、Redis 提交后失败、COMPLETING 中取消、提交后 SSE 断开及应用关闭收尾；数据库追加最多一次。
- [x] 跑下方后端测试和前端 test:chat；前端构建后再 typecheck，匹配当前仓库的类型声明生成要求。
- [x] 比较阶段 0 失败集合，填写 acceptance.md；不能用已有失败掩盖新回归，也不能宣称没有实际执行过的检查已通过。
- [x] 更新 README 中聊天实现说明，以及设计文档状态与阶段验收链接。保留“检索依赖客户端仍待迁移”的准确范围。

取消与保存检查使用真实模拟服务器和Mockito调用计数，核心断言包括：

```java
assertTrue(model.awaitDisconnect(Duration.ofSeconds(2)));
assertEquals(0, model.secondModelCalls());
verify(handler, never()).persistCompletedTurn(any(), anyString());
// 正常完成用例使用 times(1)，不与取消用例共享同一次请求。
verify(handler, times(1)).persistCompletedTurn(eq(command), eq("完整回答"));
```

等待上限是测试超时，不能作为资源释放结果本身。响应头前取消的测试需要独立的服务器闸门和迟到资源计数，不能复用只会在发完响应头后探测的断开用例。

```powershell
mvn '-Dtest=ChatStreamingHttpTest,ChatStreamingLoadTest,ChatStreamingProxyTest,ChatStreamingHeaderOrderTest,ChatControllerSseTest,ChatStreamServiceTest,ChatRequestRegistryTest,ChatHandlerStreamingTest,ChatHandlerHistoryTest,DeepSeekClientStreamingTest,DeepSeekClientProxyTest,ConversationMessageServiceTest,ConversationServiceHistoryTest,ConversationCompressionServiceTest,ContextBudgetServiceTest,ChatStreamingBrowserApplicationTest,ChatStreamingBrowserProxyTest' test
```

以下在 frontend 目录执行：

```powershell
pnpm test:chat
pnpm build
pnpm typecheck
```

**阶段 3 退出标准：** 设计第 13.1 节的每项验收均有测试或明确记录的人工证据；取消有真实资源证据，保存有事务边界证据。到此可以暂停并作为面试说明的已完成聊天链路。

## 7. 阶段记录与继续方式

每阶段报告至少写：完成文件、实际测试结果、仍未迁移部分、下一阶段依赖。仅用户授权时按阶段提交；提交或回退均只针对该阶段自己的改动。

阶段0～3交给下一次执行的提示示例（R阶段需先按接续设计补齐实施计划）：

```text
阅读 2026-10-05-remove-flux-design.md 和本计划。
只执行阶段 N，完成其测试和阶段结果记录后停止。
保持其他阶段不变，不实现 ReAct/MCP，不提交或推送。
```

对于阶段 1，必须完成 1.1～1.3，才记成该阶段完成。对于阶段 2，全部入口、资源、线程池和测试必须一起切换，不能把只改 DeepSeekClient 记成完成。

阶段0～3的代码、测试和验收记录已落实；最终状态以阶段3验收文档为准。R阶段和阶段4～6未开始。
