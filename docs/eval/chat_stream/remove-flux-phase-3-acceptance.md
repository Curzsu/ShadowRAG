# 聊天去除 Flux：阶段 0～3 验收

日期：2026-10-06。实现基线：`a96b80d`，分支 `codex/backend-chat-sse`。当前实现与文档保留在工作区，未提交或推送。

聊天与 DeepSeek 摘要已切换为普通 Java HTTP 读取和回调，前端继续通过 Spring MVC `SseEmitter` 接收 SSE。保留最多一次知识库搜索、权限过滤、取消、总截止时间及完成后保存。独立审查结果在本文末尾记录。

## 1. 交付范围

| 阶段 | 结果 | 记录 |
| --- | --- | --- |
| 0 | 建立原有行为基线，后端 169、前端 79 个测试通过 | [基线](remove-flux-phase-0-baseline.md) |
| 1 | 新增 HTTP 客户端、有限额的 SSE 读取器及可取消资源；新旧测试共 205 个通过 | [组件结果](remove-flux-phase-1-result.md) |
| 2 | 完整切换客户端、聊天编排、请求资源和后台任务入口；定向测试 189 个通过 | [切换结果](remove-flux-phase-2-result.md) |
| 3 | 验证真实 HTTP、Nginx、取消、保存与资源释放；完成前端检查和后端打包 | 本文 |

新增组件为 `BlockingModelHttpClient`、`ModelSseReader`、`ModelRoundResult`、`ModelHttpProperties` 和 `ChatGenerationResources`。主要修改 `DeepSeekClient`、`ChatHandler`、`ChatStreamService`、请求 context/registry、线程池和配置；同步迁移测试与浏览器联调夹具。

生成池默认 16 线程、队列 64；发送池 16 线程、队列 1024；MVC 定时池 2 线程。HTTP 另有共享的 2 线程守护定时池，确保静默正文和同步摘要也受整体截止时间约束。HTTP 客户端的内部执行器不使用生成池。

Embedding、Reranker、MinerU 和它们的 WebClient 配置尚未迁移，WebFlux starter、Reactor 测试依赖保留。ReAct 与 MCP 未实施。不能将本次交付称为“整个项目已移除 Reactor”。

## 2. 实际验证结果

环境：Windows 11，JDK 21.0.7，Maven 3.9.11；项目按 Java 17 编译。Node 使用已安装的 `E:/nodejs/node.exe`。

| 检查 | 实际结果 |
| --- | --- |
| 不过滤的 `mvn test` | 271 个：0 failure、1 error、2 skip；唯一 error 为外部 MySQL 连接失败 |
| 更新浏览器夹具后，`mvn '-Dtest=!SmartPaiApplicationTests' test` | 271 个：269 通过、0 failure/error、2 个条件启用的 Nginx 测试跳过 |
| 审查修复后再次运行上述完整本地命令 | 274 个：272 通过、0 failure/error、2 个条件启用的 Nginx 测试跳过 |
| 单独启用真实 Nginx 的 `ChatStreamingProxyTest` | 2/2 通过，未跳过 |
| 前端 `test:chat` 的完整脚本 | 79/79 通过 |
| 前端生产构建，然后 typecheck | 均退出 0 |
| `mvn -DskipTests package` | BUILD SUCCESS；测试由上面的独立命令证明 |
| 聊天范围的源码扫描 | 无 Flux、Mono、Disposable、Scheduler 或 WebClient 直接引用 |
| 差异格式检查 | `git diff --check` 通过 |

两次后端测试之间增加了一个浏览器普通 HTTP 构造入口测试，因此不能将两个 271 的集合视为完全相同。全量运行发现的 `SmartPaiApplicationTests.contextLoads` 失败没有删除、永久禁用或改写：Hibernate 初始化连接 MySQL 时出现 `Connection refused: getsockopt`。完整应用启动仍须在 MySQL 等依赖可用后验证。此次本地 MVC 测试应用启动成功，但这不等同于验证了生产应用启动。

沙箱内 Maven 曾无法解析已生成的项目类；相同命令在批准的环境执行后通过，未因此改动代码。前端使用已安装的 Node/tsx/Vite/vue-tsc 执行 package.json 对应脚本；pnpm 启动器的用户缓存访问受限，未安装或升级依赖。

## 3. 行为覆盖

| 验收项 | 证据 |
| --- | --- |
| 通用问答、单次搜索、权限、请求参数和摘要 | DeepSeekClientStreamingTest、ChatHandlerStreamingTest、ChatHandlerHistoryTest、BlockingModelHttpClientTest |
| first 在 DONE 前到达，DONE 后不等待 EOF | BlockingModelHttpClientTest 的真实 loopback 服务器闸门与断开探测 |
| UTF-8 跨字节、CRLF、注释、多行 data | ModelSseReaderTest；CRLF 原始字节计数先失败后修复 |
| 无换行、超大帧、无 Content-Length 的 JSON、错误正文与工具参数限额 | ModelSseReaderTest、BlockingModelHttpClientTest、ModelHttpPropertiesTest |
| 缺 DONE、残缺 JSON、截断、供应商错误不触发搜索或保存 | HTTP 客户端、ChatHandlerStreamingTest、ChatStreamServiceTest |
| 等待响应头取消、正文取消、迟到资源关闭 | HTTP 客户端/代理测试、ChatGenerationResourcesTest；真实连接与可计数流，而非仅检查 future |
| 搜索和第二次模型调用期间取消，截止时间耗尽 | ChatHandlerStreamingTest、ChatStreamServiceTest、ChatStreamingHttpTest |
| 注册前取消、身份校验、去重与会话互斥 | ChatRequestRegistryTest、MVC 与浏览器测试 |
| 生成池占满时仍发送心跳、排队任务取消、队列拒绝释放租约 | ChatGenerationLifecycleTest：1 个生成线程、1 个等待位置，独立发送池 |
| meta 首个、seq 连续、completion 唯一、终止后无事件 | ChatStreamServiceTest、ChatStreamingHttpTest、ChatStreamingHeaderOrderTest |
| 完成/取消竞争、超时/断开、队列溢出和发送失败 | ChatStreamServiceTest 的受控竞争及原有行为断言 |
| 正常清理不打断持久化、保存失败、Redis 提交后失败不重复追加 | ChatGenerationLifecycleTest、ChatStreamServiceTest、会话与历史服务测试 |
| COMPLETING 取消、提交后 SSE 失败、应用关闭收尾 | ChatStreamServiceTest、ChatRequestRegistryTest |
| 多用户/会话、资源不串用、保留记录 TTL 回收 | MVC 测试、ChatStreamingLoadTest 的 1000 次请求 |
| 代理基础路径、HTTP 转发、HTTPS CONNECT 取消 | BlockingModelHttpClientProxyTest、DeepSeekClientProxyTest、浏览器代理测试 |
| 真实反向代理不缓存逐段输出、长搜索期心跳 | 单独运行的 ChatStreamingProxyTest，真实 Nginx |

这些持久化边界由受控服务测试和调用计数验证；未连接外部 MySQL 进行真实事务故障注入。未调用真实付费模型。

## 4. 真实连接与资源数据

| 场景 | 本次数据 |
| --- | --- |
| 16 路本地流，每路 200 段、间隔 20ms | 峰值 16 个模型连接，首段 p95 62ms |
| 同样的流经过真实 Nginx | 首段 p95 73ms |
| Nginx 后搜索等待 95 秒 | 约第 15/30/45/60/75/90 秒收到 6 次心跳；第 95.080 秒收到后续正文；模型共调用 2 次 |
| 浏览器连接断开 | 104ms 观察到生成取消，134ms 观察到模型连接断开；未保存 |
| 10 个用户、1000 次串行请求 | 1000 次模型调用和保存；TTL 后保留记录从 1000 降为 0 |

每轮结束后 activeRequests、leases、streams、modelConnections、pendingEvents、generationActive、generationQueue 和 workerQueue 均归零。定时队列保留 1 个 registry 周期清理任务，这是预期常驻任务。原始指标中的 `subscriptions` 和 `subscriptionCancelAfterClientCloseMillis` 为沿用的历史字段名，当前记录普通资源/生成取消，不代表仍有 Reactor 订阅。

原始数据：

- [本地并发](remove-flux-load-metrics.json)
- [连接断开](remove-flux-disconnect-metrics.json)
- [1000 次资源回收](remove-flux-resource-metrics.json)
- [Nginx 逐段输出](remove-flux-proxy-frame-metrics.json)
- [Nginx 长等待心跳](remove-flux-proxy-heartbeat-metrics.json)

Nginx 使用已有本地测试二进制、仅监听 loopback；本阶段的配置、临时文件和日志独立存放。验收后已退出该测试代理。这些数据只说明本地模拟供应商下的行为，不能推断真实供应商吞吐或生产延迟。

## 5. 执行决策与限制

- 沿用当前功能分支和工作区，保护原有设计改动；未另建 worktree。代价是实现与已有工作共用目录。
- Windows 下使用 PowerShell 和本地执行记录代替技能的 Bash 辅助脚本；保留先失败后通过的测试证据。代价是手工维护记录。
- HTTP 代理采用 JDK 的 HTTP 转发与 HTTPS CONNECT；两种路径均测过。只支持非认证 HTTP 代理；仅支持 CONNECT 到普通 HTTP 的非标准代理需另行适配。
- HTTP 自身增加共享 2 线程截止时间定时池。代价是多 2 个守护线程；完成或取消后移除调用定时任务。
- 并发验收按默认阻塞生成池的 16 路执行，排队和拒绝另测；不沿用原响应式 50 路并发假设。代价是并发上限和排队首段延迟，不能声称移除 Flux 提升了吞吐。
- 外部 MySQL 启动测试仍报告失败，未扩大为基础设施修复。代价是完整应用启动验收仍需补做。
- 保留本阶段忽略目录中的日志和进度记录，供未提交工作继续执行；用户未授权 Git 提交。代价是本地测试产物暂留。
- 搜索依赖客户端可能不立即响应中断；搜索返回后检查请求状态，禁止后续模型、输出与保存。其客户端自身超时仍适用，不能承诺即时停止所有检索工作。
- 独立审查提出发送启动后拒绝的覆盖缺失（Minor）；这属于本计划要求保留的行为断言，已补回，新增测试在现有清理实现上直接通过，未将它记成生产缺陷修复。代价是增加一个回归测试。
- 定向回归发现关闭测试只等待 RUNNING，尚不能证明取消回调已登记；改为等待 handler 入口闸门，生产行为未改。代价是测试夹具需要显式同步，其他取消竞争由独立测试覆盖。
- 真实供应商、部署代理、真实数据库事务故障和完整生产启动不由本地证据推断；分别需要环境联调。代价是这些环境特定风险尚待验证。
- 未安装 Java 17 运行时；现有证据为 release17 编译、JDK21 执行。代价是 Java17 运行时特定差异仍待核验。

详细命令与失败/修复记录位于仓库本地 `.superpowers/sdd/2026-10-06-remove-flux-chat/progress.md` 及对应日志。下一步是单独安排知识库 ReAct 的 R1～R3，尚未开始。

## 6. 独立审查

最终只读审查发现 1 个 Important、1 个 Minor，无 Critical。

Important 为取消的排队 FutureTask 仍占用生成队列位置。先新增两个测试，分别在“运行线程仍阻塞”和“取消发生在 executor 入队之前”复现队列大小仍为 1；再在 FutureTask 完成回调移除取消任务，并在 execute 返回后处理迟到入队竞争，两个测试通过。定向生命周期与 SSE 服务套件 33/33 通过，随后完整本地套件 274 个中 272 个通过、2 个条件启用的 Nginx 用例跳过；Nginx 已单独实际运行通过。

Minor 为原有发送拒绝测试迁移后只覆盖初始拒绝；已补回生成持有 HTTP future/body 后拒绝发送的测试，断言 future 取消、body 关闭、生成退出、无保存和无后续写入。没有剩余延期小项。

审查未判断真实供应商/部署代理兼容、真实 MySQL 事务故障、完整应用启动、Java17 运行时及生产吞吐。这些范围按第5节的决定保留限制，没有用模拟测试代替环境验收。修复后完整本地套件与重新打包结果见第2节。
