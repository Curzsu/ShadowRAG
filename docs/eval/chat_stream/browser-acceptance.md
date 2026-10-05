# 网页验收记录（2026-10-05）

版本：`codex/backend-chat-sse`，基线 `6cb1e866a503af568bb58d8913c73dfb8154243a` 加当前未提交工作区。真实 Vue 页面、真实 Spring Servlet/JWT/ChatController/ChatStreamService/ChatHandler/DeepSeekClient/ConversationService，通过实际 HTTP 接收事件；数据库、缓存、搜索结果使用测试源码中的隔离替身。以下本地模型不代表真实模型联调，Gemini 记录另列。

生产构建由真实 Nginx 1.30.5 的 `127.0.0.1:18084` 提供，流 location 来自 `docs/nginx.conf`；开发 Vite `127.0.0.1:19527` 经项目 HTTP 代理访问本地后端 `18083`。测试账户只属于本次内存 fixture；所有会话均为可丢弃测试数据。

| 操作 | 实际观察与证据 |
| --- | --- |
| 普通问答 | 先显示等待回答，随后中文正文与“已完成”；18 个供应商正文分片，只保存一组 user/assistant。见 `browser-normal-cancel-metrics.json`。 |
| 停止输出 | 长回答停止后保留局部正文并显示“已停止”；供应商连接关闭，活跃与会话租约归零，保存总数仍为 1。见上述 metrics。 |
| 刷新历史 | 页面刷新后选择原会话，只恢复成功的两条消息；停止轮次不在历史。 |
| 两轮检索 | 显示“正在检索知识库”，随后中文回答与“已完成”；累计供应商请求从 2 增至 4、检索 1 次、持久化从 1 增至 2。见 `browser-tool-metrics.json`。 |
| 输出中切换 | 当前输出期间选择先前会话，恢复其原始问题与正文；新视图可发送，旧输出没有追加到新会话。随后活跃计数归零。 |
| 输出中删除 | 15:01 新建专用会话，生成期间点击删除并确认；列表删除该项，聊天视图为空且可发送。先前在配额暂停数小时后进行的删除因旧登录已失效而退出登录，未计作成功删除证据。 |
| 开发代理 | 登录、普通回答、长回答停止、再次生成中注销并确认，最终回到登录页。累计 3 个供应商请求、1 次保存、2 次连接关闭，活跃/租约归零。见 `browser-dev-proxy-metrics.json`。 |
| 第一份 HTML | `/test-source.html` 由本地Nginx精确alias指向 `src/main/resources/test.html` 提供，不是Java fixture路由。创建会话后手动输入大写 UUID，正文逐步出现，最终“回答完成，已保存”；历史列表选择该会话后显示原始问题和完整正文。长回答停止显示“已停止，局部回答未保存”；该会话历史仍只有成功的两条消息。见 `static-source-metrics.json`。 |
| 第二份 HTML | `/test.html` 由后端实际 static 资源映射提供。创建、问答、停止、历史列表与选择均成功；历史恢复成功问答，停止轮次没有恢复。见 `static-served-metrics.json`。实际 `/static/test.html` 映射另由后端冒烟测试验证 HTTP 200。 |

页面通过CUA实际控件操作，读取页面可访问状态并结合统计；没有通过浏览器内部状态或脚本替代点击。早期窄视口截图只显示侧栏，不能证明正文/停止/历史视觉结果，已移除相关四张图片及引用，行为记录保留实际控件/状态和stats证据。最终真实Gemini另以1280×900记录正文与历史两张宽图，见 [真实联调](live-gemini.md)。辅助统计有生产JWT保护，仅在test源码，不包含token/API key/prompt/原始检索结果。精确工具停止/迟到/取消失败/提交競争由session/RAG/lifecycle断言提供。

测试期间一次在运行中的 classpath 上同时执行 Maven 编译，导致本地预览出现瞬时 ClassNotFoundException。已停止该预览，在构建结束后重新启动并重做开发代理操作；该次失败没有计入通过证据，也没有据此修改生产代码。
