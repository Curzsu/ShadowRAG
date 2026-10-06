# Gemini 3.1 知识库工具调用失败修复

日期：2026-10-06。问题：上传 PDF 后，在问答页面输入“请调用知识库检索工具辅助回答”，返回“模型服务暂时不可用，请稍后重试”。

## 原因和证据

当前本地配置使用 `gemini-3.1-flash-lite` 和 Gemini OpenAI 兼容接口。此前真实联调只覆盖 `gemini-2.5-flash-lite`，未覆盖 Gemini 3 的签名要求。

运行日志显示 PDF 已解析为 10 个块并完成入库；失败请求执行了带权限检索，向量和 BM25 查询均有命中。随后请求以 `MODEL_ERROR` 结束，最终答案未保存。Reranker 服务未启动产生的警告会回退到 RRF 排序，不是此次错误的原因。

针对当前模型和接口，用相同的合成工具调用做对照：

| 请求 | 结果 |
| --- | --- |
| 第一轮模型返回工具调用 | HTTP 200，携带 156 字符 thought_signature |
| 下一轮只回填 id/type/function 和工具结果，丢弃签名 | HTTP 400：Function call is missing a thought_signature |
| 下一轮保留原 extra_content.google.thought_signature | HTTP 200 |

项目解析和回填只保留调用 ID、名称及参数，丢掉了签名。Gemini 3 在处理工具结果时校验该字段，因此第一次检索后不能继续生成答案。[Google 官方说明](https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures)要求同一轮内所有工具调用的签名随原调用回传。

## 修改

- `ModelToolCall` 增加可选原始签名及模型消息转换，保留旧四参数构造方式。
- 流式解析器按调用身份保留签名，支持完整无 index 批次及带 index 的分片；重复同一签名不拼接，不同签名冲突时拒绝。
- `AgentLoopService` 每次回填 assistant/tool 时携带签名，包括后续检索、上下文裁剪后的请求和无工具收尾。
- 签名是不可读的模型协议状态，不显示在 SSE 正文、不进入最终答案保存。新增每轮签名总字符上限 `deepseek.api.max-tool-signature-chars`，默认 1048576；异常格式、空签名、冲突和超限安全失败。

保留当前模型、本地凭据和检索流程；没有修改用户正在进行的前端拖拽上传改动。

## 验证

先增加失败回归：26 项中 2 项失败，确认后续模型请求没有原签名，异常扩展字段也未被校验。修复后协议、模型 HTTP、上下文和循环的 46 项定向回归通过；另补带 index 的交错签名和跨调用签名总量边界用例。

当前模型真实联调 2/2 通过（合成资料，凭据临时加载，不写入测试源码）：

| 场景 | 模型请求 | 检索 | 最终回答 | 单次耗时 |
| --- | --- | --- | --- | --- |
| 普通对话 | 1 | 0 | 联调成功。 | 1228ms |
| 连续查 A、B | 3 | 2 | 收入 100 万 → 120 万，同比增长 20%，引用两份报告 | 4266ms |

无密钥报告：[普通对话](gemini31-live-direct.json)、[连续检索](gemini31-live-two-searches.json)。单次耗时不代表生产性能。

本地失败/通过日志：`.superpowers/sdd/2026-10-06-knowledge-base-react/gemini-signature-red.log`、`gemini-signature-green.log`、`gemini31-live.log`。真实联调使用合成资料，仅验证当前模型与项目循环配合；实际 PDF 问答验证另行记录。

首次完整回归中，旧 `shutdownCancelsRunningButDoesNotCancelCommittedTurn` 测试在线程调用同一个 mock 时继续配置 stub，触发 Mockito 内部断言，并使下一项测试报告未完成配置。调整为先完成所有 stub，再启动请求，同时等待实际生成开始；原关闭、取消、提交及拒绝新请求断言保留，生产关闭逻辑未修改。29 项发送服务回归通过；首次失败日志 `gemini-signature-full.log` 保留。

最终完整后端回归 318 项，314 通过，0 失败/错误，4 项条件跳过（2 项真实 API 已单独通过；2 项 Nginx 未在本次重跑）。打包首次遇到 Windows 运行中的 jar 占用，停止本次启动的指定后端进程后重新打包成功，修复版在 8081 启动；前端 9527 保持运行。最终日志 `gemini-signature-full-final.log`、`gemini-signature-package-final.log`。

通过正常登录和前端 `/proxy-default` 代理，在独立验证会话重试原句：HTTP 200、final、completion=finished、无 error、239 字符最终正文与数据库会话记录完全一致且只保存一次。该新会话没有具体问题，模型本次未调用检索，不能作为 PDF 检索后回答通过的证据。[无正文验收记录](gemini31-pdf-original-runtime.json)。

进一步明确要求概括刚上传 PDF 的真实检索验收，首次被自动审批拒绝：这会向 Gemini 外部 API 发送私有 PDF 片段，需用户明确授权。该被拒请求没有执行；用户随后回复“允许，用这份 PDF 验证”，再执行此项验证。

授权后的当前服务验收通过：使用前端代理、正常管理员登录及真实 Elasticsearch/Embedding 检索；运行日志确认命中刚上传 PDF 的块。1 次检索开始/结束，回合为 intermediate → final，HTTP 200、completion=finished、无 error，903 字符最终答案带来源引用，与读取的会话记录完全相同且只保存一次，正文没有签名字段；单次总耗时 20365ms。测试登录随后正常退出，不输出令牌或 PDF 正文。[无正文验收记录](gemini31-pdf-runtime.json)。

修复版后端继续运行在 8081；前端地址为 `http://127.0.0.1:9527/`。改动尚未提交。
