# 前端 SSE 重构：任务 4–5 交付与验证记录

日期：2026-10-05。分支：codex/backend-chat-sse。代码基线：6cb1e866a503af568bb58d8913c73dfb8154243a。

状态：任务 4–5 已完成；分任务及整体独立审查通过，整体审查发现的普通接口 401 问题已修复并复审通过。代码尚未提交、推送或部署。

## 范围与结果

执行 [实施计划](../../superpowers/plans/2026-10-03-websocket-to-sse-refactor.md) 的任务 4–5，遵循 [设计与验收标准](../../superpowers/specs/2026-10-03-websocket-to-sse-refactor-design.md)。后端任务 1–3 的已完成结果见 [后端交付记录](backend-tasks-1-3.md)。

- 任务 4：增量 UTF-8/SSE 解析、POST 流式请求、独立取消、HTTP/协议错误和终态校验；不自动重放生成请求。
- 任务 5：聊天状态与页面使用 SSE；按请求、会话、消息 ID 和登录身份隔离迟到回调；切换、新建、删除、注销和离开页面统一清理。
- 页面保留停止或失败前的正文，工具进度单独展示；取消失败显示“连接已关闭，停止结果未确认”。完成后只更新会话列表，成功历史由历史接口恢复。
- 发送前先登记消息与请求身份；异步建会话共享同一创建操作；加载历史期间禁止发送；准备期间停止会使迟到创建失效；等待时编辑的新草稿不会被清空。
- 注销立即关闭本地连接、清空聊天数据和认证；取消/注销使用清空前捕获的认证。停止使用当下有效认证。旧登录的生成、取消、普通 HTTP 和刷新响应不能恢复旧 token 或重置新登录。

为覆盖普通会话接口的迟到认证响应，范围适度扩展到现有 Axios 响应回调、请求层、异步刷新与注销；使用可注入且实际接入生产的 auth-session/chat-view 测试边界。未改变 JWT 生命周期、API 协议或依赖版本。触及的下载请求移除了 token URL 参数，普通请求移除了可能包含认证头的完整响应日志。

任务 6–7 的旧 WebSocket 后端/辅助代码/依赖清理、静态测试页、Nginx、部署说明、真实基础设施和负载资源验收尚未执行。当前阶段不能作为完整生产发布验收。

## 最终自动化检查

使用本机 Node 24.11.1、缓存 pnpm 10.28.0 和仓库现有依赖；没有安装或升级运行栈。

| 检查 | 最终结果 |
| --- | --- |
| pnpm test:chat | **54/54 通过**；0 失败、取消、跳过。解析/传输 31 项，会话/视图/认证 23 项。 |
| 改动文件 ESLint CHECK | **通过**；21 个 TypeScript/Vue 文件，0 错误、0 警告；没有全仓库自动改写。 |
| 生产构建（prod） | **成功**，退出码 0。 |
| 全项目类型检查 | **未通过**，退出码 2；6 个既有第三方诊断。最终原始诊断日志与改动前基线逐字一致，没有新增诊断。 |
| git diff --check | 前端变更通过。 |
| 独立审查 | 任务 4、任务 5 均 PASS / APPROVED；无 Critical / Important 发现。整体审查修复复审 PASS / APPROVED，无未关闭的 Critical / Important。 |

类型诊断来自 node_modules 中 vue-markdown-shiki 的源文件：VueGroupCode.vue 两项 TS2339，VueMarkdownIt.vue 一项 TS7053，markdown.ts 与 plugins/containers.ts 共三项 TS7016。没有修改第三方源码来绕过检查；**A33 的全项目 typecheck 通过条件尚未满足**。

从 frontend 复核：

```powershell
pnpm test:chat
pnpm typecheck
pnpm exec eslint src/utils/sse.ts src/utils/sse.test.ts src/service/api/chat-stream.ts src/service/api/chat-stream.test.ts src/store/modules/chat src/store/modules/auth/auth-session.ts src/store/modules/auth/index.ts src/service/api/auth.ts src/service/request packages/axios/src/index.ts packages/axios/src/type.ts src/typings/api.d.ts src/views/chat/modules
pnpm exec vite build --mode prod
```

本机默认 node 路径不可用于沙箱检查，实际复核使用 E:/nodejs/node.exe 和已有 runner；pnpm 关闭自动添加 packageManager 与网络访问。精确命令和退出码保存在本地 `.superpowers/sdd/2026-10-03-websocket-to-sse-refactor/` 的报告/日志中。最终日志：frontend-final-tests.log、frontend-final-lint.log、frontend-final-typecheck.log、frontend-final-build.log；基线：frontend-baseline-typecheck.log、frontend-baseline-build.log。

## 实际页面检查

使用实际 Vue 页面、Pinia、fetch、既有 Vite HTTP 开发代理与仅本机测试服务器。开发进程临时设置 API 地址，未修改生产环境配置；不访问真实用户数据或计费模型。页面最初处于窄侧栏尺寸，导航控件位于可视区外；功能验证临时使用 1280×900，结束恢复默认尺寸。此记录不宣称移动端响应布局已验收。

| 操作 | 观察结果 |
| --- | --- |
| 正常发送含中文和 emoji 的消息 | 先等待/生成，正文逐步显示“中文😀第一段。第2段。第3段。”，随后已完成，输入恢复可发送。 |
| 输出中停止 | 显示已停止，已输出正文保留；独立取消已到达，测试服务提交数为 0。 |
| 取消接口返回 500 | 正文保留，显示“连接已关闭，停止结果未确认”；本地连接关闭，未误报完成。 |
| 收到正文后 EOF，缺 completion | 显示“连接中断，请重新发送”，保留部分正文，退出生成；未自动重发。 |
| 生成中新建、再生成并切回旧会话 | 旧请求取消；新视图没有串入旧内容；切回只显示成功历史。 |
| 刷新页面后选取成功会话 | 通过历史接口恢复成功的用户/助手消息；停止、取消失败和中断的回答未作为成功历史恢复。 |
| 生成中删除当前测试会话 | 独立取消后列表移除该会话，正文清空、输入可发送，迟到结果没有重建会话。 |
| 生成中退出登录、重新登录 | 跳转登录页，独立取消已到达；重新登录时旧正文、旧选中会话和草稿均已清空。列表来自重新请求。 |
| 流接口或普通会话接口返回 401 | 流接口、首次发送准备、点击新建、历史和列表请求均清理当前登录并跳转；不开始或重放模型流。 |
| 生成中进入知识库、返回聊天 | 离开时取消请求；返回重新加载成功历史，没有恢复取消的回答。当前路由未启用 chat KeepAlive。 |

测试服务器统计保存为 frontend-browser-stats.json，仅包含测试请求/会话 UUID、状态、chunk 数与提交次数；没有 token、问答正文或用户凭据。10 次已登记测试流：2 次成功且各提交一次，8 次取消/中断且提交为 0；其中一次长流在调整验收视口期间自然完成，属于额外正常流，不计为页面离开验收。认证失效请求在流登记前拒绝。测试服务的提交计数证明页面策略，**不能替代真实 MySQL 持久化验证**。

最终重新加载后的页面未出现新的聊天运行错误。开发期间并行构建曾触发路由代码生成的 HMR 临时错误；记录按时间区分，未将历史控制台记录误当为最终版本错误。

## 验收映射与边界

| 验收项 | 本阶段证据及边界 |
| --- | --- |
| A03–A04 | 每个字节边界、Unicode、换行、注释、半帧、缓冲上限解析测试通过。 |
| A06 | 传输 New-Token 顺序与当前登录更新、停止认证、普通响应/异步刷新隔离测试通过；真实认证由后端阶段证据补充。 |
| A11 / A22 / A24 | 请求/消息身份、迟到 finally/chunk、发送闸门、建会话/历史竞态、未知事件序号和浏览器切换检查通过。 |
| A21 | HTTP/协议错误、非法帧、超限、缺终态及网络中断自动化通过；页面 EOF 部分正文与中断提示通过。 |
| A23 | 删除、注销、401、离开页面的本地清理及独立取消通过；真实服务取消/持久化仍见后端证据。 |
| A25 | 正常完成不重载历史、只刷新列表；页面成功历史恢复通过。MySQL 顺序由后端任务 1–3 测试证明。 |
| A33 | 既有开发代理 SSE/取消与 prod 构建通过；改动文件 lint 通过；typecheck 仍为原第三方基线错误，完整 A33 尚未通过。 |

本次未执行 Nginx 超过 90 秒工具空窗、50 并发/1000 请求负载、真实 MySQL/Redis/认证服务联合启动、静态测试 HTML、移动端布局或完整 A01–A36 矩阵。

## 审查观察与实施选择

整体审查发现的 P2 已关闭：普通会话接口 HTTP 401 原来未触发登录清理，已用实际 Axios 错误回调回归和浏览器 RED → GREEN 验证首次发送、新建、历史和列表入口；旧登录迟到的 401 不会清理新登录。修复限于 request/index.ts、auth-session.ts 和 chat-session.test.ts 三个文件，复审无新增问题。记录：final-auth-401-fix-report.md、final-auth-401-fix-review.md。

两个 P3 非阻塞观察保留：历史加载失败后同一选中行不能直接重试；公开的 activeRequest 对象仍带有内部认证/正文等字段，建议以后只暴露必要字段，审查未发现新的日志、持久化或网络泄漏路径。另有未来配置要求：若启用 chat KeepAlive，需要为中断的历史/列表加载增加激活恢复；当前静态路由没有启用该缓存。

继续在现有分支保留未提交的后端成果，以便前端消费真实接口；保留本地验证与审查快照，待任务 6–7 后统一交付。使用 PowerShell 生成报告替代本机不可用的 Bash；适度扩展请求层/auth 测试边界以实现完整登录隔离。这些选择的风险分别是后续提交组织成本、小范围共享刷新兼容性、以及测试边界抽象增加；已有精确范围、兼容性与竞态测试。