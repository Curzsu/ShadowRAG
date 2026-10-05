# 本机 Gemini 配置与验证

验证时间：2026-10-05 16:39（Asia/Shanghai）。此记录补充迁移验收时的存储替身限制，不代表已完成全部真实基础设施回归。

- 使用正式 Spring Boot 后端（8081）、Vite 前端（9527）和现有 Docker MySQL、Redis、Elasticsearch、Kafka、MinIO 服务。
- 本地私有 `application-local.yml` 配置 Google OpenAI 兼容接口、`gemini-3.1-flash-lite` 和 `http://127.0.0.1:7890` HTTP CONNECT 代理。API key 仅保存于这个已被 Git 忽略的文件，保留原 JWT 和 Elasticsearch 配置。
- 正式模型客户端增加可选 `deepseek.api.proxy-url`（环境变量 `LLM_HTTP_PROXY`）。空配置直连；仅模型请求使用代理；HTTPS 保持默认 TLS 验证。无认证 HTTP 代理须明确端口，非法值启动时报安全错误。
- 代理测试先失败：12 项失败，0 执行错误，证明原客户端忽略代理。实现后代理、流式客户端、RAG 流程、SSE 服务和 Spring 装配相关测试共 **77 项通过**。
- 实际网页发送“请用一句中文解释 SSE，不要检索知识库。”；Gemini 返回 58 字符，页面显示“已完成”。服务端 `FINISHED`，`durableCommitted=true`，首个正文约 3388 ms，总耗时约 3646 ms。
- 刷新页面后选择该会话，恢复相同问答。通过消息表与会话表的主键关联查询，确认真实 MySQL `conversation_messages` 2 条。未独立确认 Redis 容器中的工作集，也未进行真实知识库检索或文件上传验收。
- 保留验证会话供用户查看，前后端保持运行。本次没有提交代码。
- 检查 `src/main`、`src/test`、`docs`、`frontend/src` 的代码/配置/文档，以及当前启动日志：Gemini key 匹配为 0；本地私有配置保持忽略。

访问：http://127.0.0.1:9527/#/chat 。本地代理需要保持运行；下次正常启动后端会自动读取本地 Gemini 配置。
