# Langfuse 阶段 1、2 实施记录

> 阶段记录：以下提交、运行与验收状态仅描述当时环境。后续路由及检索实验已有独立记录，当前使用方法和能力边界以[可观测指南](../observability.md)为准。

状态：2026-10-08 阶段 1、2 已完成并验收。分支：`codex/langfuse-phase-1-2`。代码保存在工作目录，尚未提交、推送或合并；其他既有未提交改动保留。

## 已交付

- 阶段 1：私有配置加载、默认关闭、缺配置降级、Java OTel 1.66.0 HTTP/protobuf 有界异步导出到 JP Cloud。
- 加载器只解析六个允许键，支持引号/BOM、进程环境优先、结束后恢复环境，不执行文件文本。凭据文件仍被 Git 忽略。
- 复用现有 HTTPS_PROXY / HTTP_PROXY，本机测试端点绕过代理；3 秒连接/导出超时，关闭时限时等待 SDK。
- 阶段 2：真实 `chat.request`、`llm.round`、`tool.knowledge_search` 三类节点；显式请求 Context、父子关联、模型首字时间、路由/版本哈希、最终检索 top 5 ID/分数与精排状态。
- 正常、取消、超时、保存失败、提交成功但通知丢失均遵从既有生命周期；并发与线程复用不串链。Cloud 上报返回 401 时，问答仍完成且只保存一次。
- 正文、原始用户名、供应商异常、reasoning 和凭据不上传。CAPTURE_CONTENT 只保留配置项，当前即使设为 true 也不采正文。
- 关机提交超出原有等待或被中断时，结束追踪并记录 `COMPLETING`、`shutdown_unresolved=true`、`durable_commit_outcome=unknown`；不填写提交成败，不改变事务结果。之后不重复结束已关闭的 span。

## 真实 Cloud 核验

真实 Spring HTTP/SSE 请求调用当前配置的模型；搜索场景执行实际权限检索与精排，回答经数据库保存。通过 observations v2 查询核验接收，未把 SDK forceFlush 返回成功当作接收证据。

| 场景 | 核验结果 | Cloud 记录 |
| --- | --- | --- |
| 连接冒烟 | 非模型连接记录，无 TTFT 或业务评分 | [integration.smoke](https://jp.cloud.langfuse.com/project/cmuyeaxqk001oad0eov2lkv26/traces/ec4e99467cfbf2a41f602501d7a387d5) |
| 直接回答 | 根节点 + 一轮 generation，DIRECT、FINISHED、已保存、完成通知已发送；模型原生 TTFT 10.234 秒 | [直接回答](https://jp.cloud.langfuse.com/project/cmuyeaxqk001oad0eov2lkv26/traces/b6a7895a7009551f0ac4aca2ad279b5e) |
| 检索后回答 | 根节点 + 两轮 generation + 一次搜索；SEARCH、精排 success、FINISHED、已保存；答案轮原生 TTFT 1.111 秒，纯工具轮无答案首字时间 | [检索回答](https://jp.cloud.langfuse.com/project/cmuyeaxqk001oad0eov2lkv26/traces/01f7f83b3030f1fd4c9df301090ff2e1) |

两条真实记录的父子关系、请求关联与无正文上传均已核验。上述耗时只是单次联调，不用于声称性能提升；工具轮耗时和检索耗时不包含在答案轮模型 TTFT 内。

外部验证默认跳过，需显式设置 LANGFUSE_SMOKE_TEST 或 LANGFUSE_CHAT_SMOKE_TEST。聊天联调仅创建并删除测试会话、注销本次 token；审查修复后通过 repository 创建带显式 ID 的会话，不再切换用户当前会话或重建旧工作记忆。

## 验证与审查

- PowerShell 配置加载器测试通过。
- 定向修复验证：32 项，0 失败、0 错误。
- `mvn test`：334 项，0 失败、0 错误，7 项默认跳过的外部检查。
- 补充同时生成的两个聊天请求后，最终 `mvn package`：335 项，0 失败、0 错误，7 项跳过；后端 jar 打包成功。实际编译目标为 Java 17，宿主测试 JDK 为 21。
- 已运行一次独立只读审查，未发现 P0/P1；两个 P2（关机未结束根节点、联调清理重建旧工作记忆）均修复并验证。关机回归先复现“应有 1 个根节点、实际 0 个”，再修复通过。
- 全量测试发现旧 MVC/浏览器夹具未导入新追踪配置，已补实际关闭态 LangfuseConfiguration，未弱化生产依赖。
- SDK 失败日志的远端响应回显已用测试识别并屏蔽；受控错误码和脱敏诊断保持可用。
- 本次代码范围的 diff 检查、凭据形状扫描和私有文件 Git 忽略检查通过。

详细日志及不含凭据的 Cloud 验证结果保存在 Git 忽略的 `target/langfuse-work/`：`package-final.log`、`real-chat-smoke.log`、`cloud-chat-smoke.json`、`progress.md`。构建产物为 `target/SmartPAI-0.0.1-SNAPSHOT.jar`。

## 启动与回退

在项目根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/langfuse/run-with-langfuse.ps1
```

脚本复用根目录已保存的 `.env.langfuse.local`。普通 `mvn spring-boot:run` 不读取该文件，默认关闭采集；回退可设置 LANGFUSE_ENABLED=false 后重启。数据库、Redis、ES、向量与精排服务需可用。

初次验收未留下后端进程；随后按用户要求启动前端（9527）和后端（8081），搜索耗时补充后重启后端使新节点生效。既有 Docker 服务继续运行；未重置用户数据或删除容器。

## 2026-10-08 搜索耗时补充

按用户批准的范围，在实际搜索下新增 `embedding`、`retrieval`、`rerank` 子节点。分别计量查询向量生成、BM25/KNN 与 RRF、Cross-Encoder 调用及结果映射，包含调用与网络等待，不等于远端服务的纯计算时间。禁用精排或无候选时不创建精排节点；混合召回失败后走 BM25 会留下两次 retrieval，以策略属性区分。脱敏、异常降级和默认关闭行为保持一致。

- TDD：先确认缺少子节点导致 6 项断言失败，再实现；定向 38 项通过。新增 10 项搜索追踪测试覆盖父子关系、上下文恢复、跳过、异常和降级。
- 最终 `mvn package`：345 项测试，0 失败、0 错误，7 项外部测试默认跳过，jar 打包成功。回归中修正原关机测试对终态通知必达的竞态假设，保留提交与取消断言，未改生产生命周期。
- 显式开启的真实聊天 Cloud 冒烟：1 项通过，验证直接回答无搜索子节点，以及搜索三段的父节点、请求/会话身份、实际时间与无正文采集。
- [搜索耗时实测记录](https://jp.cloud.langfuse.com/project/cmuyeaxqk001oad0eov2lkv26/traces/82c5225ceb37cc1624018fc2e66468b9)：embedding 10.048 秒，retrieval 0.491 秒，rerank 7.420 秒，搜索总计 18.038 秒。这是一次联调样本，不是性能基准结论。

证据：`target/langfuse-work/search-steps-red.log`、`search-steps-green.log`、`search-steps-package.log`、`search-steps-cloud.log`、`cloud-chat-smoke.json`。历史 trace 不会补出未采集的子步骤。当时阶段 3～5 尚未实现；随后用户要求的阶段 5 已完成，见下文。

## 后续阶段

阶段 3、4 未开始：流程 TTFT 基线对照与公共评测上报、路由准确率。阶段 5 已实现真实 Java 检索结果的 Hit@5/MRR@5 评分并完成 10 组小样本联调，见[检索评测记录](2026-10-08-langfuse-retrieval-evaluation.md)。当前模型原生 TTFT 已可查看，现有后端首段耗时也被记录；简历的 34.3%、100 组 Hit@5=90% 和路由准确率仍未通过本次接入重新评测。

继续实施时复用当前分支、代码、私有配置和[阶段计划](../superpowers/plans/2026-10-08-langfuse-staged-delivery.md)，不要为历史报告补造调用追踪。
