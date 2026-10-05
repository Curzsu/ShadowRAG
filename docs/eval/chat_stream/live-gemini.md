# Gemini 真实网页联调

2026-10-05，基线 `6cb1e866a503af568bb58d8913c73dfb8154243a` 加当前未提交工作区。最终成功模型：`gemini-3.1-flash-lite`，Google 官方 `https://generativelanguage.googleapis.com/v1beta/openai` 接口。

实际链路是最终 Vue 生产构建 → 真实 Nginx → Spring Servlet/JWT/ChatController/ChatStreamService/ChatHandler/DeepSeekClient → Google → 网页。数据库、Redis、检索仍是 test 源码中的隔离替身，ConversationService 及历史接口使用真实生产实现。成功轮次没有调用搜索工具，也没有调用本地模型 stub；这不是完整真实存储或真实检索集成测试。

完成了两次独立成功轮次：首次assistant257字符，保留在 [首次metrics](live-gemini-first-metrics.json)；随后重启独立临时fixture，以1280×900视口重新验证并保存最终画面，assistant212字符，见 [最终metrics](live-gemini-metrics.json)。每次各保存1轮/2历史消息，不能把两个独立fixture的单次计数合并声称同一进程保存2轮。

最终网页先等待，随后显示标题SSE、中文定义、JavaScript代码块、Copy控件及“已完成”；刷新并选择该会话后恢复相同标题/正文/代码块。最终activeRequests=0、conversationLeases=0、persistedTurns=1、historyMessages=2、searchCalls=0、本地stub调用0；正文SHA256为55A73C8C4B2E8A78E72F1DD7AB0156AB84FC5AE44F92BD735656CF9075903256。见 [完成截图](live-gemini-browser.jpg)、[历史截图](live-gemini-history.jpg)。早期319px截图只显示侧栏，不能作为正文/Markdown视觉证据；最终两张宽视口截图取代它。模型示例代码用于显示验证，没有执行。

首次直连调用在测试入口120秒总期限后显示超时，没有保存。当前电脑访问 Google 需要已配置的本地 HTTP 代理，原模型 WebClient 不会自动读取环境代理；测试入口新增显式 `CHAT_BROWSER_HTTPS_PROXY`，仅在 live mode 选择 HTTP CONNECT 网络连接，保留 TLS 验证及生产请求构造、事件解码和取消实现。此覆盖只在 test 源码，未更改生产代理配置。协议与校验方式参考 [Reactor 官方代理说明](https://projectreactor.io/docs/netty/1.2.13-SNAPSHOT/reference/http-client.html)。新增配置校验及原冒烟共13项通过，不调用计费模型。

使用代理后，`gemini-3.8-flash` 返回模型需求过高的 HTTP503。网页正确显示安全错误并恢复可发送状态，保存数为0，见 [失败轮次统计](live-gemini-unavailable-metrics.json)。随后从该 key 的官方模型列表选择 `gemini-3.1-flash-lite`，完成上述成功轮次。没有把503或超时计作成功，没有据此放宽认证或 TLS。

API key 仅注入临时后端进程的 `GEMINI_API_KEY` 环境变量，未写入项目配置、文件、URL或浏览器；只用于用户授权的官方 API 联调。终态记录、截图和统计不包含 key/JWT。最终扫描及关闭临时进程结果见 [安全检查](security-scan.json)。此记录证明普通真实模型回答与网页/历史链路可用，不保证供应商繁忙时可用、不证明取消后立刻停止计费，也不替代 MySQL/Redis/ES 的实际集成环境验证。
