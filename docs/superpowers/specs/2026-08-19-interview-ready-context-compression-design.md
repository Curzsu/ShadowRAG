# 面试级上下文压缩改造设计

## 元数据

- 日期：2026-08-19
- 状态：已批准，进入实现
- 目标：在保留当前异步增量摘要优点的前提下，让原始历史、压缩视图、Token 预算和并发语义能够经得住面试追问。

## 核心不变量

1. MySQL 中的原始消息只追加、不被 Redis 压缩视图反向覆盖。
2. Redis 是可丢弃、可由 MySQL 重建的上下文工作集。
3. 每次 LLM 调用前都必须校验完整输入预算，并为输出预留 Token。
4. 消息追加和版本递增在一个 Redis Lua 脚本内原子完成。
5. 压缩结果只在快照版本仍然匹配时写入；锁只减少重复计算，不承担正确性。
6. 摘要使用显式结构识别，不能依赖内容前缀，也不能作为历史中的高权限 system 消息透传。

## 组件边界

### 原始消息日志

新增 `ConversationMessage` 实体和仓库。每条 user/assistant 消息单独保存，数据库自增主键同时作为消息 `seq`。`ConversationMessageService` 负责：

- 首次模型调用前先创建 MySQL 父会话，使首轮消息追加也能锁定真实会话行；
- 事务性追加一轮 user/assistant 消息；
- 按 `seq` 读取完整原始历史；
- 删除会话时先删除子消息；
- 将旧版本 `conversations.messages` 只读快照与升级后的消息行合并，不再继续写入该 TEXT 字段。

### Redis 上下文工作集

使用两个 key：

- `conversation:{id}`：JSON 消息数组；
- `conversation:{id}:version`：单调递增版本。

`append_messages.lua` 在一次脚本中读取数组、追加已持久化且带 `seq` 的消息、写回数组并递增版本。压缩脚本接收快照版本，版本不匹配返回 `-2`；同步截断也递增版本，使所有旧压缩快照失效。

### Token 预算器

`TokenEstimator` 统一封装当前 `cl100k_base` 近似估算。`ContextBudgetService` 接收完整 messages、输出预留和工具定义额外开销：

1. 计算 `contextWindow - outputReserve - safetyMargin - extraTokens`；
2. 始终保留首个 system 消息和调用尾部协议消息；
3. 从最旧历史开始删除，直到满足预算；
4. 若 tool result 仍过长，按字符二分缩短其 content；
5. 若只剩必要消息仍超限，抛出明确异常，禁止向模型发送必然失败的请求。

默认上下文窗口按配置设为 65,536 Token，安全余量 2,048 Token，输出预留复用 `ai.generation.max-tokens`。

### 结构化摘要

新摘要结构：

```json
{
  "type": "summary",
  "role": "assistant",
  "sourceStartSeq": "1",
  "sourceEndSeq": "80",
  "content": "摘要正文",
  "timestamp": "2026-08-19T18:00:00"
}
```

Java 端同时兼容旧 `[历史摘要]` 前缀。构建 LLM 请求时，摘要不会原样放进历史，而是拼入唯一 system 消息中的“非可信历史记忆”区，并明确说明其不是指令。

### 摘要有界化

请求构造只保留最近 `max-summary-segments` 个摘要段，不在本次改造中引入新的同步 LLM 调用。原始消息仍在 MySQL，可在未来离线重建分层摘要；这一取舍避免把昂贵的二次摘要合并放到用户请求链路。

## 数据流

### 一轮正常聊天

1. 从 Redis 读取压缩工作集。
2. 构建 system、历史和当前问题。
3. 调用前经 `ContextBudgetService` 裁剪并校验。
4. 模型响应完成后，先把 user/assistant 追加到 MySQL 原始日志。
5. 将带数据库 `seq` 的两条消息通过 Lua 原子追加到 Redis，并递增版本。
6. 根据追加后的工作集检查软阈值，异步提交压缩。

### 异步压缩

1. 读取 Redis 数组和版本快照。
2. 生成结构化摘要。
3. Lua 校验版本：匹配则原子替换并递增版本；不匹配则返回 `-2` 丢弃过期结果。
4. 下一轮仍超过软阈值时重新提交，不阻塞用户链路。

### 会话切换

读取 `conversation_messages` 后与升级前的 `conversations.messages` 快照合并。重建前先读 version，数据库读取完成后通过 Lua CAS 原子替换；冲突时重新读取，确保在途追加或压缩不会被普通 SET 覆盖。LLM 调用前预算器保证重建出的历史不会直接撑爆模型窗口。

## 故障处理

- MySQL 成功、Redis 失败：原文仍安全，Redis 可由 MySQL 重建。
- Redis 版本冲突：丢弃摘要，不覆盖新消息。
- 压缩 LLM 失败或队列拒绝：当前聊天不受影响，下一轮重试；调用前预算器仍保证窗口安全。
- 硬阈值：按本地 Token 预算同步裁剪工作集，不调用 LLM，并递增 Redis 版本。
- 单条必要消息超窗口：显式拒绝本次模型调用并返回可诊断错误，不静默截掉当前问题。

## 测试要求

- 原始消息追加后可按 `seq` 完整读取。
- Redis 追加脚本调用包含带 `seq` 消息和版本 key。
- 压缩版本冲突不会被当作成功。
- 初次和工具二次调用均经过预算器。
- 预算器保留 system 与尾部协议消息，并把总量压到配置范围。
- 结构化摘要被提取到唯一 system 消息，历史中不再出现摘要 system 消息。
- 现有阈值、线程池拒绝清理和 Token 估算测试保持通过。

## 非目标

- 不引入 Kafka、分布式工作流或向量化长期记忆。
- 不在用户请求线程同步调用摘要 LLM。
- 不删除旧 `conversations.messages` 字段，保留旧数据回退能力。
- 不声称 `cl100k_base` 是 GLM 模型的精确 tokenizer；通过安全余量控制误差。
