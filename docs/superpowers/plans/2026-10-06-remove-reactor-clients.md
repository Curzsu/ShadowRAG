# ShadowRAG 其他 HTTP 客户端与 Reactor 依赖清理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在聊天改造验收后，迁移向量、重排、文档解析的 WebClient 调用，并删除不再使用的直接依赖。

**Architecture:** 每个客户端保留原业务入口，替换内部 HTTP 请求，分别验收。所有源码和测试迁移完之后才删除 WebFlux starter 与 reactor-test，并检查传递依赖。

**Tech Stack:** Java 17、JDK HttpClient、Spring Web RestClient/multipart 转换器、Jackson、JUnit 5。

**Spec:** [去除 Flux 改造设计](../specs/2026-10-05-remove-flux-design.md) 第 10.2、13.2 节；前置为 [聊天改造计划](2026-10-06-remove-flux-chat.md) 阶段 3 通过。

日期：2026-10-06。状态：暂缓，用户另行安排。本计划编号延续聊天计划，对应阶段 4～6；本次没有执行这些阶段。当前优先完成聊天改造后的知识库ReAct，MCP不在范围内。本计划不是ReAct的前置条件。

用户每次指定阶段后再执行，不默认继续到下一阶段，不自动启用子代理，不自动提交或推送。

## Global Constraints

- Java 17、Spring Boot 3.4.2 保持不变；不改变文件上传、向量、重排、知识库权限的业务接口。
- Embedding 单批整体调用预算30秒，最多3次HTTP响应异常重试、间隔1秒；不把3次重试解释成3次总请求。
- Reranker 整体调用预算10秒，关闭/失败返回null，仍回退RRF原排序，不新增自动重试。
- MinerU 解析整体预算使用当前 `mineru.api.timeout`，HTTP响应异常最多重试2次、间隔5秒；健康检查5秒。
- 重试只针对当前允许的HTTP响应异常，剩余时间不足或线程中断就停止；聊天模型生成不重试。
- 响应上限：Embedding `16777216` 字节、Reranker `16777216` 字节、MinerU解析 `52428800` 字节、MinerU健康检查 `1048576` 字节。
- 不只检查 Content-Length，也限制实际输入读取；不在先转换完整字符串后才检查大小。
- 保持现有认证和安全日志，模型输入、文档正文、候选文本和令牌不进入异常输出。
- 实施阶段4～5期间保留WebFlux依赖，直到全部调用者和测试迁移完毕。
- 不默认安装、启动外部基础设施或调用付费服务；使用本地HTTP模拟服务。
- 执行前检查R阶段是否已经实施；如已实施，保留其接口与回合SSE事件，并将ReAct验收加入本计划的回归，不能按旧单搜索基线回退它。

## Review Focus

1. HTTP重试次数与整体30秒/解析预算的边界：阶段4.1、阶段5覆盖。
2. 没有Content-Length且正文持续传输时仍限额、超时：三个客户端都覆盖。
3. 非法重排JSON及供应商错误不回显query/候选内容，仍回退排序：阶段4.2覆盖。
4. 中文文件名和二进制内容的multipart必须不变：阶段5覆盖。
5. 删除依赖后main源码虽能编译，测试夹具或Spring配置仍引用WebClient：阶段6扫描和全部测试覆盖。

---

## 1. 文件与接口边界

以下路径相对仓库根目录 `E:/Curzsu/ShadowRAG`。

保留业务入口：

```java
// EmbeddingClient
public List<float[]> embed(List<String> texts);

// RerankerClient
public List<RerankResult> rerank(String query, List<String> documents, int topN);
public boolean isEnabled();

// MinerUClient
public String parseToMarkdown(InputStream fileStream, String fileName);
public boolean isAvailable();
```

仅为多个实际客户端共享的有限JSON读取新增 `client/BoundedJsonHttpClient.java`：

```java
public String postJson(
        URI uri, Map<String, String> headers, Object body,
        Duration timeout, int maximumBytes)
        throws IOException, InterruptedException;
```

该组件复用JDK HttpClient，内部管理覆盖整个响应体的截止时间和流关闭，返回有限字符串。它不决定业务重试、模型参数或供应商响应结构，不封装通用Agent工具。非2xx用携带状态码但不携带原始正文的内部HttpStatusException反馈，由客户端决定重试。

阶段4配置 `embedding.api.max-response-bytes` 与 `reranker.api.max-response-bytes`；阶段5配置 `mineru.api.max-response-bytes`。配置HTTP客户端使用阶段4新增的 `config/HttpClientConfig.java`，不能改JVM全局代理，也不能让内部HTTP任务使用聊天阻塞生成池。

## 2. 阶段4：向量与重排

阶段4分两个可分别验收的任务；用户只指定4.1时，不自动执行4.2。

### Task 4.1：迁移Embedding并提供有限JSON调用

**Files:**

- Create: `src/main/java/com/yizhaoqi/smartpai/client/BoundedJsonHttpClient.java`。
- Create: `src/main/java/com/yizhaoqi/smartpai/config/HttpClientConfig.java`。
- Modify: `src/main/java/com/yizhaoqi/smartpai/client/EmbeddingClient.java`、`config/WebClientConfig.java`。
- Modify: `src/main/resources/application.yml`。
- Create/Test: `src/test/java/com/yizhaoqi/smartpai/client/BoundedJsonHttpClientTest.java`、`EmbeddingClientHttpTest.java`。
- Modify/Test: `src/test/java/com/yizhaoqi/smartpai/service/HybridSearchServiceLoggingTest.java` 的Embedding夹具。

**Interfaces:**

- Consumes: 原Embedding模型、维度、分批参数与返回解析。
- Produces: 保持 `embed(List<String>)`，新增第1节 `postJson`，删除embeddingWebClient bean。

- [ ] 先写批次请求数量与顺序、`/embeddings`路径、请求字段、认证、向量维度和结果对应关系测试。
- [ ] 先写“连续HTTP500三次后成功，总请求4次”“非HTTP异常不重试”“30秒预算包含等待与重试”测试。计时逻辑用可控时钟/等待器或缩短注入预算测试，验证生产默认仍是30秒。
- [ ] 先写无Content-Length超16MB、正文静默超时关闭流、HTTP错误不回显输入和中断终止重试测试。
- [ ] 运行 `mvn '-Dtest=BoundedJsonHttpClientTest,EmbeddingClientHttpTest,HybridSearchServiceLoggingTest' test`，确认新增行为测试失败后实现普通HTTP调用与有限读取。
- [ ] 移除Embedding里的WebClient、Mono和Retry，不改变分批与解析。删除对应bean并调整日志测试夹具；其余两个WebClient bean保留。
- [ ] 跑同一测试通过，并跑ChatHandlerStreamingTest、HybridSearchServiceLoggingTest验证聊天搜索未回归。

HTTP重试用例的关键断言：

```java
List<float[]> actual = client.embed(List.of("测试文本"));
assertEquals(4, server.requestCount()); // 初次请求 + 3次重试
assertEquals(1, actual.size());
assertArrayEquals(new float[] {0.1f, 0.2f}, actual.get(0), 0.00001f);
```

此例将测试客户端的dimension配置为2，mock最后一次返回对应向量；生产维度配置保持原值。

**退出标准：** 向量生成已不直接使用Reactor；返回与错误行为保持约定，不能声称整个检索链路已全部迁移。

### Task 4.2：迁移Reranker

**Files:**

- Modify: `src/main/java/com/yizhaoqi/smartpai/client/RerankerClient.java`、`config/WebClientConfig.java`、`config/HttpClientConfig.java`。
- Modify: `src/main/resources/application.yml`。
- Create/Test: `src/test/java/com/yizhaoqi/smartpai/client/RerankerClientHttpTest.java`。
- Modify/Test: `src/test/java/com/yizhaoqi/smartpai/client/RerankerClientResponseTest.java`、`service/HybridSearchServiceLoggingTest.java`。

**Interfaces:**

- Consumes: Task4.1的 `BoundedJsonHttpClient.postJson`。
- Produces: 现有 `rerank(...)` 和 `isEnabled()` 普通调用；删除rerankerWebClient bean。

- [ ] 先写关闭开关零请求、空候选零请求、topN不超过候选数、两种响应结构兼容及索引对应测试。
- [ ] 先写HTTP500、非法JSON、超过16MB、10秒整体超时均返回null；日志不回显query/候选正文；失败路径不自动重试。
- [ ] 运行 `mvn '-Dtest=RerankerClientHttpTest,RerankerClientResponseTest,HybridSearchServiceLoggingTest' test`，确认新HTTP行为测试失败后实现。
- [ ] 改内部HTTP构造与夹具，保留RRF失败回退；删除rerankerWebClient bean，WebClientConfig只留下MinerU。
- [ ] 跑同一测试通过，并验证HybridSearchService的回退结果。

失败回退核心断言：

```java
assertNull(client.rerank("问题", List.of("候选"), 1));
assertEquals(1, server.requestCount()); // HTTP500路径没有自动重试
```

**阶段4退出标准：** Embedding与Reranker均迁移并通过验证；普通知识库检索可用，MinerU仍待迁移。

## 3. 阶段5：文档解析

### Task5：迁移MinerU multipart与健康检查

**Files:**

- Modify: `src/main/java/com/yizhaoqi/smartpai/client/MinerUClient.java`。
- Modify: `src/main/java/com/yizhaoqi/smartpai/config/HttpClientConfig.java`、`src/main/resources/application.yml`。
- Delete: `src/main/java/com/yizhaoqi/smartpai/config/WebClientConfig.java`，仅在确认已无bean使用者后删除。
- Create/Test: `src/test/java/com/yizhaoqi/smartpai/client/MinerUClientHttpTest.java`。

**Interfaces:**

- Consumes: Spring Web现有RestClient/multipart转换器与阶段4的HTTP配置。
- Produces: 保持 `parseToMarkdown(InputStream,String)` 和 `isAvailable()`；无MinerU WebClient bean。

- [ ] 先写本地服务捕获multipart：字段名files、原文件名、中文文件名、PNG/PDF二进制内容逐字节一致；解析返回Markdown保持当前提取规则。
- [ ] 先写HTTP响应失败最多重试2次、总请求不超过3次，5秒等待仍计入整体解析预算；非HTTP异常不增加重试。
- [ ] 先写50MB实际读取限制、没有Content-Length的响应、正文中途静默的整体超时、关闭输入/响应和中断测试。
- [ ] 先写健康检查成功true、失败false、5秒超时及1MB健康响应限制。
- [ ] 运行 `mvn '-Dtest=MinerUClientHttpTest' test`，确认新HTTP测试失败后实现RestClient multipart与健康调用；使用普通转换器，不手写multipart边界。
- [ ] HTTP请求工厂设置所需等待超时，响应读取仍有覆盖正文的截止时间和关闭资源。不可仅设置等待响应头的超时后声称覆盖了正文。
- [ ] 删除最后的WebClient bean和WebClientConfig，重新运行上述测试及阶段4测试；确认文档上传调用者仍使用原业务签名。

multipart和结果核心断言：

```java
assertEquals("files", captured.partName());
assertEquals("中文报告.pdf", captured.fileName());
assertArrayEquals(sourceBytes, captured.fileBytes());
assertEquals("# 解析结果", markdown);
```

测试服务解析捕获的multipart边界后再比较文件字节，不用搜索原始请求字符串代替二进制完整性校验。

**退出标准：** 文档解析、健康检查均迁移；源码配置已不使用WebClient，但pom仍待最后清理。

## 4. 阶段6：依赖删除与项目验收

### Task6：扫描全部引用后移除依赖

**Files:**

- Modify: `pom.xml`。
- Modify: 扫描发现的剩余测试/夹具引用；范围包括 `src/test/java/com/yizhaoqi/smartpai/support/`。
- Modify: `README.md`、设计文档状态。
- Create: `docs/eval/chat_stream/remove-flux-phase-6-project-acceptance.md`。

**Interfaces:**

- Consumes: 聊天阶段3与客户端阶段4～5的验收结果。
- Produces: 无业务/测试直接Reactor调用、已移除直接WebFlux依赖的项目。

- [ ] 扫描引用，区分import/实际代码和文档注释。实际引用未清零前不删除依赖。
- [ ] 用原依赖运行迁移后的全部后端测试；查看报告，没有新增失败后再删除starter-webflux、reactor-test及过时注释。
- [ ] 执行下方命令，确认依赖删除后编译与测试仍通过；记录传递依赖来源，不盲目添加exclusion。
- [ ] 在frontend运行test:chat、build、typecheck；前端协议与行为不变。
- [ ] 记录各阶段实际结果和仍存在的限制，更新README与设计状态；只有直接和传递依赖实际检查满足时才宣称“没有Reactor依赖”。
- [ ] 完成后停止；下一步ReAct/MCP需要独立任务，不自动实施。

```powershell
rg --no-ignore -n 'import reactor\.|import org\.springframework\.web\.reactive|\bWebClient\b|\bFlux\b|\bMono\b|\bDisposable\b|\bSchedulers?\b' src/main/java src/test/java
mvn test
mvn -DskipTests package
mvn dependency:tree '-Dincludes=org.springframework:spring-webflux,io.projectreactor:*,io.projectreactor.netty:*'
```

rg无实际引用时正常退出码为1，不是验证脚本故障。mvn test结果要比较原有失败集合；打包跳过测试不能替代测试证据。

**退出标准：** 全项目直接使用的响应式客户端和测试均已移除，相关依赖已清理，现有功能通过回归。其他SDK若仍传递引入Reactor，验收记录必须说明，不能将其隐藏。

## 5. 阶段继续记录

每阶段填写自己的结果记录：执行基线、改动文件、测试命令/数量/失败集合、当前剩余WebClient用途、下一阶段依赖。仅用户授权Git操作时按阶段提交。

用户指定“阶段4”意味着执行4.1和4.2；指定“阶段4.1”只迁移Embedding。阶段5与阶段6都不可提前删除其他任务仍使用的配置/依赖。

本次仅生成计划，没有实施客户端迁移，也没有运行改造测试。
