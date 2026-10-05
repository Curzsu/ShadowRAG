# 聊天 POST SSE 验收记录

更新：2026-10-05。基线 `6cb1e866a503af568bb58d8913c73dfb8154243a`，分支 `codex/backend-chat-sse`，当前为未提交工作区。任务1–7已完成，A01–A36有下表实际证据，真实Nginx、生产/开发/两HTML、真实Gemini/Markdown/历史、安全复扫与临时环境清理完成。全量九个既有错误与真实DB/Redis/ES未验证边界保留，不能称全量或完整生产基础设施绿色。

历史阶段记录保留原测试数量和当时的限制：[后端任务 1–3](backend-tasks-1-3.md)、[前端任务 4–5](frontend-tasks-4-5.md)。本轮见 [任务6–7](tasks-6-7.md)、[机器可读结果](tasks-6-7-test-results.json)、[浏览器](browser-acceptance.md)、[真实Gemini](live-gemini.md)、[复现步骤](reproduce.md)。设计及必验条件见 [A01–A36 原定义](../../superpowers/specs/2026-10-03-websocket-to-sse-refactor-design.md#10-验收标准)。

本机 Windows 11 / Java 21.0.7（源码 release 17）、Spring Boot 3.4.2 / Tomcat 10.1.34、Maven 3.9.11；保持现有 Vue/Vite/依赖版本。自动化通过本地实际 HTTP 模型 SSE stub 和嵌入式 Servlet；生产安全链、流服务、RAG 编排及 DeepSeekClient 均参与，DB/Redis/检索边界使用测试替身。测试不调用计费模型，不新增匿名生产入口。性能数字仅代表受控同机 stub，不能作为供应商 SLA。

本轮结果：后端相关 **180/180**；前端聊天 **79/79**，typecheck/只检查 lint/生产 build 均 exit 0；独立真实 Nginx 专项 **2/2**，与 HTTP/安全头合跑 **12/12**。全量后端 **205 项、0 failures、9 errors、2 skipped**，因此全量构建仍失败：九项为原有 DB 环境/测试配置错误，两个跳过项为未设置 proxy-url 的代理测试，已在独立真实代理运行通过。历史 198/9、前端 54/6 等数字是原阶段快照，不改写为本轮结果。

PASS 表示表中列出的断言/记录已验证。实际网页记录见 [浏览器验收](browser-acceptance.md)：生产 Nginx18084、开发 Vite19527→18083、源HTML测试alias与实际static映射。精确工具阶段停止/迟到结果/提交竞争由确定性自动化提供，未伪称手工点击覆盖所有竞态。真实 DB/Redis/ES 联调不由测试替身建立。

| ID | 状态 | 实际证据与边界 |
| --- | --- | --- |
| A01 | PASS | `DeepSeekClientStreamingTest.publisherIsCold/doneEndsModelStream`；`ChatHandlerStreamingTest.firstRoundContentArrivesBeforeModelCompletion`；`ChatStreamServiceTest.metaPrecedesContent`；实际HTTP分帧及单次finished；真实Gemini经Vue/Nginx/Spring正常回答、保存1轮且无本地stub调用。[live](live-gemini.md) |
| A02 | PASS | `ChatHandlerStreamingTest.searchAndSecondModelStayInSameOrderedSubscription` 保留权限检索与顺序；真实代理两次模型 HTTP、一次保存；实际Vue先“正在检索知识库”再回答/完成，新增1次检索、2轮模型、1次保存。[浏览器](browser-acceptance.md)、[stats](browser-tool-metrics.json) |
| A03 | PASS | `sse.test.ts` 每个字节边界、中文/emoji、同 read 多事件；`DeepSeekClientStreamingTest.fragmentedUnicodeAndToolArgumentsAreDecodedInOrder`；静态 helper 的中文/emoji 测试。 |
| A04 | PASS | parser 的 CRLF/CR、多 data、注释与半帧 EOF 测试；static helper 对应行结束/EOF 断言。 |
| A05 | PASS | `ChatStreamingHttpTest.unauthenticatedAndForgedAndRevokedHave401Json`；`JwtUtilsRefreshTest` 撤销/过期/缓存读写失败及签名校验；`TokenCacheServiceTest` fail-closed。未授权无模型/保存。 |
| A06 | PASS | JWT 合法预刷新/宽限刷新；HTTP `legitimateGraceRefreshPreservesNewTokenHeader`；fetch/普通请求认证 epoch；两份实际 HTML VM 的显式凭据与存储登录刷新隔离。 |
| A07 | PASS | `ChatStreamServiceTest.ownershipFailureRejectsBeforeModelAndHistory`；Controller 错误映射 403/404；不隐式建会话。 |
| A08 | PASS | Controller 空白/缺字段/UUID/16000 和 16001 UTF-16 边界；HTTP 非规范 UUID；Registry 注册前校验，无模型调用。 |
| A09 | PASS | Registry `sameIdWithDifferentPayloadIsDuplicate/registeredCancelledRequestRemainsDuplicate`；原任务不变，保留窗口内不重放。 |
| A10 | PASS | Registry 单会话并发及规范 UUID 租约断言，竞争只受理一个；释放后允许新请求。 |
| A11 | PASS | Registry 不同会话；RAG `explicitConversationIgnoresCurrentPointer`；50 实际并发逐请求身份/序号/正文/保存匹配；session 旧事件与 finally 隔离。 |
| A12 | PASS | `cancelWinsAndNeverPersists`、真实模型 HTTP 取消/连接关闭、RAG 第一轮取消断言；实际Vue/开发代理/两HTML停止保留局部文本，连接关闭/无新增保存，历史无停止轮次。[浏览器](browser-acceptance.md) |
| A13 | PASS | RAG `cancelDuringSearchNeverStartsSecondModel/cancelDuringSecondModelCancelsCurrentHttp`；迟到结果无正文/保存。精准阶段停止由自动化控制时序，手工浏览器记录的是两轮正常进度与输出期间停止，未声称手工检索阶段停止。 |
| A14 | PASS | Registry 先到取消、迟到 Disposable 与 Service `cancelBeforeSubscriptionDisposesLateHandle`；同 ID 标记拒绝后续生成。 |
| A15 | PASS | Service 终态竞争 200 次、COMPLETING 取消结果及重复完成；Registry completion/cancel 单赢家；至多一次 appendTurn。 |
| A16 | PASS | Registry 用户命名空间；HTTP `foreignPrincipalCancelCannotStopOwnersHttpStream`，A 的生成/保存继续。 |
| A17 | PASS | 嵌入式真实 HTTP `realHttpDisconnectCancelsModelSocket`；最新关闭后订阅取消 **95 ms**、上游连接关闭 **123 ms**，零保存/活跃。探测用 50 ms 心跳/10 ms stub 写入，非生产 15 秒断线 SLA。[数字](task-7-disconnect-metrics.json) |
| A18 | PASS | Service 工具等待心跳且不重置 deadline；实际 Nginx 默认 15 秒心跳六次覆盖 95 秒空窗，随后正文；实际Vue工具状态随后正常回答，无工具文本进入保存正文。[数字](task-7-proxy-heartbeat-metrics.json)、[浏览器](browser-acceptance.md) |
| A19 | PASS | `heartbeatContinuesDuringToolGapAndDoesNotResetGenerationDeadline`；可注入时间验证 timed_out、取消、无保存、单终态。 |
| A20 | PASS | 模型 JSON/HTTP/provider、工具/历史和持久化安全错误；Service 类型化错误与释放；HTTP `postCommitErrorStaysSse`。 |
| A21 | PASS | fetch/parser/session EOF 无 completion、坏 JSON/seq、超大帧、错误 MIME/网络读失败；退出 loading、独立尽力取消、reader 释放，无自动重放。 |
| A22 | PASS | session/chat-view 建会话、切换、历史加载、旧 chunk/finally 竞态；两份 HTML 源码VM迟到创建在logout/newer-create/selection/send后不能写入；实际Vue输出中切换恢复旧会话历史、无串流且新视图可发送。[浏览器](browser-acceptance.md) |
| A23 | PASS | session/auth 普通 HTTP401 与迟到刷新保护、路由detach及取消失败也关闭本地；实际Vue输出中删除后列表/聊天清空，开发代理生成中注销后回登录/活跃租约0；历史无取消轮次。精确认证失效浏览器见原任务4–5记录。[本轮](browser-acceptance.md) |
| A24 | PASS | session 事件/request/conversation/message 身份和旧 finally；transport 重复 seq 忽略、未知类型推进游标；不按最后一条消息定位。 |
| A25 | PASS | `mysqlCommitPrecedesFinishedEvent/repeatedCompletionDoesNotSaveAgain`、appendTurn 单事务测试；负载逐请求已发 chunk 拼接等于唯一保存文本。DB 边界为替身，非真实 MySQL 联调。 |
| A26 | PASS | `persistenceFailureSendsSafeError/disconnectDuringCommitPreservesDurableFinishedState`；关闭时提交结果保留；失败无 finished、无重复保存。 |
| A27 | PASS | RAG/History Redis 读取失败、坏缓存、提交后更新失败、下次请求缓存恢复；MySQL 成功不重复 appendTurn。缓存/DB 故障通过可控替身注入。 |
| A28 | PASS | Service 清理、Registry 旧 cleanup 身份；1000 请求活跃/subscription/buffer/lease/模型连接归零，TTL 后终态归零；共享 purge timer 保留 1。[数字](task-7-resource-metrics.json) |
| A29 | PASS | Registry 活跃/保留容量 429；Service pending-event 溢出取消及 sender/worker 拒绝清理；有限线程/队列/每请求 64 事件限制。 |
| A30 | PASS | 官方 Windows **Nginx 1.30.5** 真代理 18080→18082；20 并发、200 chunks/20 ms，p95 **53 ms**；早期帧与末帧时间跨度断言；默认 15 秒心跳覆盖95秒实际检索等待，后续正常正文。[帧](task-7-proxy-frame-metrics.json)、[心跳](task-7-proxy-heartbeat-metrics.json) |
| A31 | PASS | `ChatStreamingLoadTest.fiftyConcurrentRealModelStreamsHaveIsolatedPacedBodiesAndBoundedFirstText`；50 同时模型连接、200 chunks/20 ms，最新 p95 **149 ms**，无串流/重复保存/非预期失败，活跃归零。[数字](task-7-load-metrics.json) |
| A32 | PASS | `tenUsersOneThousandSerialRequestsReleaseEveryResourceAndExpireTerminalRecords`；10 用户/1000串行，1000模型/保存；workers16、timers2，buffer/lease/subscription归零，1000终态到TTL变0。[数字](task-7-resource-metrics.json) |
| A33 | PASS | 前端79测试、typecheck/lint/build exit0；可复现Markdown补丁无版本升级；实际生产Nginx和开发代理问答/取消/注销；HTTP刷新头及fetch/session刷新所有权；真实Gemini标题/中文/JavaScript代码块/Copy控件可见，刷新历史保留同样渲染。[浏览器](browser-acceptance.md)、[live](live-gemini.md) |
| A34 | PASS | Controller旧token负向、真实认证HTTP旧握手/指令token拒绝（401/404、无101、无handler调用）；运行代码旧WS无匹配；helper/两页源码VM22通过；两份实际HTML问答/停止/owned历史成功，源页手输大写UUID可完整回答并恢复历史；最终jar旧WS/testfixture条目0、三静态资源匹配源hash。[浏览器](browser-acceptance.md)、[包检查](backend-package-check.json) |
| A35 | PASS | HTTP `realHttpLogsExcludePromptToolResultAndJwt`；Registry字符串不含正文；HybridSearch日志测试；新URL仅requestId，JWT只在Authorization；最终live后扫描（含生成前端JS/HTML）提供的API key文件0、24份运行日志JWT/完整测试prompt/原始工具结果匹配0、旧runtime0、专用JWT/live日志0。[范围](security-scan.json) |
| A36 | PASS（相关回归） | 本轮180相关测试包含历史/预算/压缩/检索/JWT与关闭；`shutdownCancelsRunningButDoesNotCancelCommittedTurn/shutdownNeverCompletesEmitterAfterIoFailure`。全量205仍有原基线9错误，不能称全量绿色。 |

真实Gemini两次独立成功，首次257/最终212字符，各1轮/2历史消息/活跃租约0，非同进程累计2轮；最终1280×900正文/历史两图可见。直连超时和3.8繁忙503无保存，不算成功。frontend build/backend package exit0，[包内容](backend-package-check.json)通过；[205项全量快照](backend-suite-metadata.json)取实际30类，额外fixture代理13/13单独记录。[最后安全扫描](security-scan.json)和[临时环境清理](test-environment-cleanup.json)通过，五个测试端口已不监听、临时key进程/浏览器tab已关闭、视口恢复。真实MySQL/Redis/ES未联调，供应商计费取消不保证。没有提交、推送或线上部署。
