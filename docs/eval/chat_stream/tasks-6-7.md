# 任务 6–7：清理与完整验收

日期2026-10-05；基线 `6cb1e866a503af568bb58d8913c73dfb8154243a`，分支 `codex/backend-chat-sse`，未提交工作区。任务6–7完成：实现、自动化、真实代理/浏览器/Gemini/Markdown/历史、包检查与最后安全复扫/清理均有记录；全量既有九个错误和真实基础设施未验边界保留。[矩阵](README.md)、[JSON](tasks-6-7-test-results.json)、[浏览器](browser-acceptance.md)、[live](live-gemini.md)、[复现](reproduce.md)。原任务1–5报告保持原样。

旧聊天 WebSocket handler/config/命令token/controller入口/匿名规则、旧回调编排和socket helper/专用依赖/env/types/proxy已删除。两份诊断HTML共享浏览器原生 POST SSE helper，提供显式认证/会话、新建、停止、owned历史列表与切换。请求所属节点、登录epoch和操作identity防止迟到chunk/finally/New-Token/创建/历史覆盖新状态；显式凭据刷新不能替换存储登录，历史文本以textContent显示。

有界参数保持设计默认：generation/emitter/heartbeat300000/320000/15000ms、retention300000ms、active100、total10000、per-user200、worker16/queue1024、pending64；环境可覆盖且非法配置拒绝。Nginx stream独立HTTP/1.1、空Connection、buffer/cache/gzip关闭、90秒idle；README记录单实例限制、配套发布/回滚、停止/提交竞态与局部文本策略。复现现有六个第三方typecheck错误后，使用锁文件登记的2.0.0 Markdown包源补丁和准确插件声明修复，没有升级依赖。

50并发测试暴露Spring Security延迟安全头写入与异步emitter的Tomcat MimeHeaders竞态。确定性 `ChatStreamingHeaderOrderTest` 先1失败，再在SecurityConfig提前写入既有安全头后1通过，50/1000复跑通过。保留安全链和响应头，未用延时或放宽认证绕过问题。

Task6独立审查提出三个Important：显式凭据刷新覆盖登录、迟到创建覆盖选择、历史错误路由；作者用两份实际HTML源码VM测试先12失败/9通过，再21全通过修复。独立跨任务复审确认三项关闭，无新Critical/Important，剩余无调用旧解析方法为非阻塞Minor。审查人是Task7测试作者，未写Task6源代码，有继承上下文，不能称fresh-context审查。浏览器验收不由源码审查替代。

自动化fixture只在test源码：嵌入式真实Servlet与生产安全链、真实stream/registry/RAG/模型HTTP客户端；DB/Redis/检索替身；独立本地HTTP模型supplier。50个consumer同时起跑，断言实际50连接重叠、每路200帧20ms及至少3秒输出跨度、身份/连续seq/唯一终态/保存正文完全相同。1000请求专项逐次检查资源并推进registry时钟到TTL；真实代理95秒间隙是实际阻塞检索，不缩短生产心跳/超时。后续独立发现合法大写UUID与后端小写身份不一致，transport/session/helper按完整UUID规范化并保留畸形值的400验证；RED3/79，GREEN79/79，typecheck/lint0，独立源码复审通过。Task6作者独立审查Task7测试/fixture及数字并重算p95，结论PASS，无Critical/Important；本轮报告与public metrics已同步到最新149ms/95ms/123ms。

| 检查 | 本轮实际结果 |
| --- | --- |
| 后端SSE/旧链路相关回归 | 180项、0failures、0errors、0skipped；BUILD SUCCESS，2026-10-05 11:38:22 +08:00。 |
| 全量 `mvn test` | 205项、0failures、9errors、2skipped；BUILD FAILURE，11:41:01 +08:00。旧阶段198/9，原基线61/14（五个JWT fixture错误已在早阶段解决），保留历史记录。 |
| 全量九项既有错误 | ParseServiceTest6项和UploadServicePerformanceTest/SmartPaiApplicationTests各1项无法建立外部DB应用环境；UserServiceTest.testRegisterUser_Success缺OrganizationTagRepository mock。无新断言失败。 |
| 真实代理+HTTP+安全头 | 12项、0failures/errors/skipped；Proxy2、HTTP9、Header1；实际Nginx1.30.5。全量中proxy未设置参数所以跳过，不能把跳过计为此证据。 |
| 前端 | chat79/79；最新页/helper22/22；typecheck exit0；只检查lint exit0；production build exit0。历史阶段54/54和原6个typecheck错误仍保留。 |
| 最终构建 | UUID修复后frontend production build exit0；backend package BUILD SUCCESS，2026-10-05 15:20:28 +08:00；jar旧WS/testfixture条目0、streamService存在，helper与两HTML3hash匹配源。[检查](backend-package-check.json) |
| 浏览器fixture网络设置 | 额外13/13（原smoke1+新增代理校验12），0failures/errors/skipped；与原180/205快照分别记录。仅test fixture显式HTTP CONNECT代理，生产代码/包没有因此改变。 |
| 最终安全/清理 | live后提供key文本文件0（含生成前端JS/HTML，依赖树除外）；24份运行日志JWT/完整测试prompt/原始工具结果匹配0、旧runtime0；18080/18082/18083/18084/19527均不监听，临时key进程/浏览器tab关闭，视口恢复。[扫描](security-scan.json)、[清理](test-environment-cleanup.json) |
| 实际网页 | production Nginx18084普通/停止/历史/工具/切换/删除；dev Vite19527经真实项目代理→18083普通/停止/生成中注销；两HTML问答/停止/历史和源页大写UUID均成功。数据边界仍为本地supplier及DB/cache/search替身。 |
| 真实Gemini/Markdown | gemini-3.1-flash-lite经实际Vue/Nginx/JWT/Spring/生产模型客户端到Google；两次独立成功（首次257/最终212字符），各1轮/2消息/活跃租约0/stub0。最终1280×900正文和刷新历史可见标题/中文/JS块/Copy/已完成，两个fixture计数不累计混用。 |
| A31最新负载 | p95首正文149ms（03:40:28Z）；50实际同时上游连接；peak pending30、timers101；结束活跃/订阅/连接/缓冲/租约0。共享套件保留53轻量终态，不是资源泄漏。 |
| A32资源 | 10用户1000串行、1000模型请求/保存；retained1000直到TTL−1，TTL变0；workers16、timerthreads2；独立purge timer queue1。 |
| A30代理 | 20并发、200帧/20ms、p95首正文53ms；95000ms检索；六心跳15062/30047/45058/60050/75054/90060ms，后续正文95103ms。 |
| A17最新断线 | downstream关闭后subscription取消95ms、upstream关闭123ms，persist0、active0；50ms测试心跳/10ms写探测，非默认心跳性能承诺。 |

已执行的主要命令如下（本地缓存 Maven 权限环境区别不改变测试范围）：

```powershell
mvn "-Dtest=ChatStreamingLoadTest" test
mvn "-Dtest=ChatStreamingProxyTest,ChatStreamingHttpTest,ChatStreamingHeaderOrderTest" "-Dchat.acceptance.backend-port=18082" "-Dchat.acceptance.proxy-url=http://127.0.0.1:18080" test
mvn test
```

180项相关selector包含DeepSeekClientStreaming/RerankerClientResponse/ChatStreamingProperties/ChatControllerSse/ChatStreamingHeaderOrder/ChatStreamingHttp/ChatStreamingLoad/ChatRequestRegistry/ChatStreamService/ChatHandlerHistory/ChatHandlerStreaming/ContextBudget/ConversationCompression/ConversationMessage/ConversationServiceHistory/HybridSearchServiceLogging/TokenCache/ChatStreamingBrowserApplication/JwtUtilsRefresh测试。代理后端默认随机端口0；仅显式指定proxy-url才启用真实代理专项。

```powershell
# frontend
pnpm test:chat
pnpm typecheck
pnpm exec eslint src/utils/sse.ts src/utils/sse.test.ts src/service/api/chat-stream.ts src/service/api/chat-stream.test.ts src/service/api/static-chat-stream.test.ts src/store/modules/chat src/store/modules/auth/index.ts src/views/chat src/typings/api.d.ts src/typings/app.d.ts
pnpm build
```

可复核数值：[load](task-7-load-metrics.json)、[resources](task-7-resource-metrics.json)、[proxy frames](task-7-proxy-frame-metrics.json)、[default heartbeat](task-7-proxy-heartbeat-metrics.json)、[disconnect](task-7-disconnect-metrics.json)。每份包含实际recordedAt，重复执行会更新数值；上述采用最新负载/断线及已执行的真实proxy运行。结果不包含凭据、完整prompt或工具原始结果。

首次Gemini直连120秒未回答且无保存；隔离test fixture显式HTTP CONNECT代理后，3.8-flash繁忙503、安全错误/无保存；随后3.1-flash-lite两次独立实际回答和Markdown/history成功。代理保留TLS验证、无生产Java修改，13个非计费校验与live结果分别记录。生产整合独立审查APPROVED，无开放P0/P1/P2；独立作者/继承上下文，不称fresh-context。New-Token由真实HTTP及fetch/session断言覆盖；真实MySQL/Redis/ES未有全量联调绿色，原九个错误不改为通过。供应商计费取消、随机多实例、窗口外去重不保证；无schema变化，无提交/推送/线上部署。
