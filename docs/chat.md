# 聊天指南

> 与本分支代码同步。实现入口：[ChatController](../src/main/java/com/yizhaoqi/smartpai/controller/ChatController.java)、[ChatStreamService](../src/main/java/com/yizhaoqi/smartpai/service/chat/ChatStreamService.java)、[AgentLoopService](../src/main/java/com/yizhaoqi/smartpai/service/AgentLoopService.java)、[前端客户端](../frontend/src/service/api/chat-stream.ts)。

## 请求与认证

当前聊天使用 HTTP POST + SSE，通过 `Authorization: Bearer <token>` 认证。先通过会话接口创建或选择属于当前用户的会话；流式接口不会自动创建会话。

`POST /api/v1/chat/stream`，请求类型为 `application/json`，成功响应为 `text/event-stream`：

```json
{
  "conversationId": "已有会话的 ID",
  "requestId": "d9a371af-9f10-4302-bd90-937546643f44",
  "message": "请总结知识库中的部署要求"
}
```

`requestId` 必须是标准 UUID，每次新生成使用新 ID；消息不得为空，长度最多 16000 个 Java UTF-16 单元。同一用户、同一会话只允许一个活跃生成；保留窗口内重复请求 ID 会被拒绝，不会重放结果。

建立流之前的参数、认证、归属或容量错误使用 JSON 和 HTTP 错误状态，前端不能把它们当成 SSE。典型情况包括 400 参数错误、401 未认证、403/404 会话访问错误、409 会话忙或请求重复、429 容量已满。错误映射见 [异常处理](../src/main/java/com/yizhaoqi/smartpai/controller/ChatStreamingExceptionHandler.java)。

## 流式事件

每个业务事件的 SSE `event` 对应 JSON `type`，`id` 对应递增的 `seq`，正文共同包含 `requestId`、`conversationId`、`seq` 和 `data`。

| 事件 | 含义与主要数据 |
| --- | --- |
| `meta` | 流已建立，`data` 为空 |
| `chunk` | 一轮模型的正文，包含 `roundId`、`chunk` |
| `round_end` | 该轮结束，`kind` 为 `intermediate` 或 `final` |
| `tool_progress` | 知识检索工具进度，包含 `roundId`、`callId`、`tool`、`status`（started/finished） |
| `error` | 流内错误的安全错误码和提示，不包含供应商原始异常 |
| `completion` | 终态：`finished`、`cancelled`、`failed` 或 `timed_out` |

心跳为 SSE 注释，不是正文事件，不增加业务序号。前端检查请求身份、序号、轮次及终态；读到 EOF 但没有 `completion` 时按中断处理，不自动再次提交生成。当前没有断点续传或事件重放协议，SSE `id` 不代表可以重连恢复。

当前工具为 `search_knowledge_base`。事实性问答优先检索，寒暄、纯计算和输入文本改写等可直接回答；最终是否调用由模型决定，不保证每次命中。多轮模型与工具顺序执行，默认最多 3 轮工具调用轮次、总计 6 次工具调用，必要时另发不带工具的收尾模型请求；“3 轮”不等于总共只有 3 次模型调用。

## 取消与保存

停止请求为 `POST /api/v1/chat/requests/{requestId}/cancel`，使用相同用户的认证。响应中的状态可能是运行终态或 `completing`，取消是对当前状态的确认，不保证回滚已开始的提交。

- 取消或检测到连接断开时释放生成资源；尚未进入提交的取消、失败和超时不保存完整问答轮次。
- 正常生成结束后进入 `COMPLETING`，先提交完整轮次，再发送 `completion: finished`。中间检索进度不作为回答正文保存。
- 已进入提交时，取消或网络断开不能撤销事务；可能已经保存，但浏览器没收到完成通知。此时重新加载历史确认，避免直接重发造成重复问答。
- 请求记录、会话租约与取消状态在当前进程内维护，容量和去重仅是单实例保证，多实例部署前需另行设计协调机制。

保存与轮次汇总入口：[ChatHandler](../src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java)、[ChatRoundAccumulator](../src/main/java/com/yizhaoqi/smartpai/service/chat/ChatRoundAccumulator.java)、[ChatRequestRegistry](../src/main/java/com/yizhaoqi/smartpai/service/chat/ChatRequestRegistry.java)。

## 默认限制与排障

配置前缀为 `chat.streaming`，见 [配置文件](../src/main/resources/application.yml)及[配置校验](../src/main/java/com/yizhaoqi/smartpai/config/ChatStreamingProperties.java)。

| 配置 | 默认值 |
| --- | --- |
| `generation-timeout-ms` | 300000（5 分钟） |
| `emitter-timeout-ms` | 320000，必须至少比生成超时多 10 秒 |
| `heartbeat-interval-ms` | 15000；心跳不延长生成截止时间 |
| `terminal-retention-ms` | 300000；到期后旧 ID 不再受到保留记录的去重保护 |
| `max-active-requests` | 100，单实例活跃生成上限 |
| `max-pending-events` | 64，慢客户端积压上限 |

- 回答最后一次性出现：检查反向代理缓冲、压缩和客户端流式读取，参见[部署指南](deployment.md)。
- 409：确认当前会话没有生成中任务，且新请求使用新 UUID。
- 429：检查当前实例活跃数、保留记录和工作队列；这不是分布式限流网关。
- 超时或中断：检查模型、检索及代理连接，结合[可观测指南](observability.md)定位；恢复后先查看历史。

测试边界见 [CI 说明](ci.md)，各阶段实测数字见[历史验收记录](eval/chat_stream/README.md)，不能把历史测试结果视为本次运行结果。
