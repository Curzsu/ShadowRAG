# ShadowRAG 多工具 Agent 与 Intent RAG 设计

> 日期：2026-08-20
>
> 状态：待书面评审
>
> 目标岗位：AI Agent 应用工程师
>
> 实施窗口：10 天
> 设计范围：3 个只读工具、通用有界 Agent Loop、评测驱动的 Intent RAG（方案 C）

## 1. 背景

ShadowRAG 当前已经具备一个最小可用的 Agentic RAG 链路：第一次 LLM 调用携带
`search_knowledge_base` 工具；模型可以直接回答，也可以调用该工具。工具执行后，系统把检索结果作为
`tool` 消息追加到上下文，再进行一次不携带工具的 LLM 调用。

该实现能够避免闲聊、常识问题无条件检索，但仍有以下限制：

1. 只有 `search_knowledge_base` 一个工具，工具选择尚未形成真实路由问题。
2. 工具定义、工具执行和消息编排硬编码在 `ChatHandler` 中。
3. 流式解析只处理 `tool_calls[0]`，没有按 `index` 聚合多个 Tool Call，也没有形成完整的
   `id/name/arguments` 对象。
4. 工具执行后的第二次 LLM 调用不再携带工具，因此模型不能观察结果后继续调用其他工具。
5. 缺少迭代上限、重复调用检测、统一参数校验、工具超时和结构化错误。
6. 尚无工具路由与参数抽取评测，无法证明 Prompt、Tool Description 或 Intent RAG 的收益。

本设计将现有链路升级为一个权限感知、可观测、可评测的多工具 Agent Runtime；在多工具基线稳定后，
再根据评测 Bad Case 引入阿里云文章方案 C 所描述的“前置意图案例 RAG”。

## 2. 目标与非目标

### 2.1 目标

1. 把工具抽象成可注册、可校验、可独立测试的组件。
2. 支持模型执行 `工具 A → 观察结果 → 工具 B → 最终回答` 的多步循环。
3. 首期提供三个只读工具：
   - `search_knowledge_base`
   - `list_documents`
   - `get_document_status`
4. 所有权限信息由服务端注入，模型不能提交或覆盖 `userId`、`orgTags`、角色等安全上下文。
5. 为工具调用设置迭代、数量、超时、token 和结果长度边界。
6. 建立固定离线评测集，测量工具选择、参数抽取、多步编排、安全和延迟。
7. 仅在评测证明存在误路由且方案 C 有收益时，启用 Intent RAG。
8. 保留现有 MySQL 原始消息、Redis 工作集、上下文压缩和知识库混合检索能力。

### 2.2 非目标

首期明确不实现：

- 删除文档、删除会话、上传文件等写工具。
- `get_document_info` 和 `get_document_preview`；它们作为后续扩展，不影响核心 Agent 能力。
- 无限制自治、长期后台任务和人工审批工作流。
- MCP Server、Spring AI 或 LangChain4j 迁移。
- 阿里云文章方案 D 的完整 `activeIntent`、槽位状态机、直接回答和意图历史切断。
- Intent Case 管理后台；首期通过版本化 JSON 数据和离线导入命令管理。
- 将中间 Tool Call 全量写入对话原始消息；首期只持久化最终 user/assistant 对话，工具轨迹进入结构化日志。

## 3. 核心设计决策

### 3.1 先建立多工具基线，再引入 Intent RAG

Intent RAG 解决的是多工具场景中的误路由和参数抽取 Bad Case。如果先做案例检索、后做工具基线，
将无法判断问题来自工具定义、Agent Loop、案例召回还是 Prompt 注入。

实施顺序固定为：

```text
现有单工具 Agentic RAG
        ↓
通用 Tool Registry + 有界 Agent Loop
        ↓
3 个只读工具 + 路由评测基线
        ↓
分析 Bad Case
        ↓
可选 Intent RAG（方案 C）
```

### 3.2 Tool 名称就是意图，Tool arguments 就是槽位

不增加独立的实时“意图分类 LLM 调用”。工具 JSON Schema 是参数定义的唯一事实源：

```text
意图          → toolName
槽位定义      → Tool JSON Schema
槽位抽取结果  → toolCall.arguments
意图执行      → AgentTool.execute()
```

这保留了文章中“意图与槽位职责清晰”的思想，但避免额外的分类模型调用和 Schema 漂移。

### 3.3 多步能力来自 Agent Loop，方案 C 只负责校准决策

Agent Loop 每一轮都携带全部已注册工具：

```text
LLM(messages, tools)
  ├─ final content → 结束
  └─ tool_calls
       ↓
     执行工具并追加 tool results
       ↓
     LLM(updated messages, tools)
```

Intent RAG 只在当前用户轮开始时执行一次，把相似的“问法—工具—参数”案例加入初始决策上下文。
后续迭代已经拥有完整工具结果，不重复执行 Intent RAG，避免额外延迟和不稳定性。

### 3.4 只读优先，权限在工具内部强制执行

Tool Schema 中不得出现 `userId`、`orgTags`、`role`。这些字段通过 `ToolExecutionContext`
由后端传入。模型即使在 arguments 中伪造同名字段，也必须在反序列化/校验阶段拒绝或忽略。

### 3.5 有界循环而非无限 ReAct

首期默认边界：

- 最大 Agent 迭代：3
- 最大工具调用总数：6
- 单工具超时：5 秒
- 整轮 Agent 超时：45 秒
- 相同 `toolName + canonicalArguments` 最多执行一次
- 单次搜索结果上限：10 条
- 单个 Tool Result 最大：3000 token

达到任一上限后，停止继续调用工具，使用已有结果生成受限回答，并明确说明未完成部分。

## 4. 总体架构

```text
ChatWebSocketHandler
        |
        v
ChatHandler（会话生命周期、WebSocket 输出）
        |
        v
AgentLoopService
  |     |       |         |
  |     |       |         +--> AgentTrace / Metrics
  |     |       +------------> ContextBudgetService
  |     +--------------------> ToolRegistry
  |                               |-- SearchKnowledgeBaseTool
  |                               |-- ListDocumentsTool
  |                               +-- GetDocumentStatusTool
  |
  +--> IntentCaseRetriever（功能开关，方案 C）
  |
  +--> DeepSeekClient / OpenAI-compatible Chat Completions
```

组件边界：

| 组件 | 职责 | 不负责 |
|---|---|---|
| `ChatHandler` | 会话获取、最终消息持久化、WebSocket 生命周期 | 工具分发、Agent 循环 |
| `AgentLoopService` | 迭代状态机、消息追加、边界、取消、最终结果 | 具体业务查询 |
| `DeepSeekClient` | API 请求与 SSE 协议解析 | 业务工具执行 |
| `ToolRegistry` | 工具注册、名称查找、统一执行入口 | 工具选择 |
| `AgentTool` | Schema、参数校验、权限内业务执行 | 会话循环 |
| `IntentCaseRetriever` | 召回可信 Tool Case | 直接确定工具、执行业务 |
| `ContextBudgetService` | 每轮模型调用前控制上下文预算 | 业务结果正确性 |

## 5. 领域模型与接口

### 5.1 AgentTool

建议接口：

```java
public interface AgentTool {
    String name();
    Map<String, Object> definition();
    ToolResult execute(JsonNode arguments, ToolExecutionContext context);
}
```

约束：

- `name()` 必须与 OpenAI Tool Definition 中的函数名完全一致。
- `definition()` 返回完整 JSON Schema。
- `execute()` 只返回 `ToolResult`，不得直接操作 WebSocket 或对话历史。
- 每个工具自行完成业务参数校验；通用层完成 JSON 语法、未知字段和大小限制校验。

### 5.2 ToolExecutionContext

```java
public record ToolExecutionContext(
        String traceId,
        String conversationId,
        String userId,
        String username,
        Set<String> effectiveOrgTags,
        String role
) {}
```

上下文由服务端根据认证信息与会话构造，不接受模型输入。

### 5.3 ToolCall

```java
public record ToolCall(
        int index,
        String id,
        String name,
        String argumentsJson
) {}
```

流式 SSE 必须按 `tool_calls[index]` 分别累计：

- `id`
- `function.name`
- `function.arguments`

流结束后才能把累积器冻结为 `ToolCall`。缺失 ID 时生成本地唯一 ID，确保 assistant/tool
消息协议合法；缺失工具名或 arguments 无法解析时返回结构化 `invalid_arguments`，不得回退执行默认工具。

### 5.4 ToolResult

```java
public record ToolResult(
        String toolName,
        ToolStatus status,
        String message,
        Object data,
        boolean retryable,
        Map<String, Object> metadata
) {}
```

`ToolStatus` 固定为：

```text
SUCCESS
NO_RESULT
INVALID_ARGUMENTS
AMBIGUOUS
FORBIDDEN
TIMEOUT
ERROR
```

工具结果序列化为 JSON 后写入 `role=tool` 消息。异常栈、数据库 ID、内部路径和凭证不得进入模型上下文。

### 5.5 LlmTurn

```java
public record LlmTurn(
        String content,
        List<ToolCall> toolCalls,
        String finishReason,
        TokenUsage usage
) {}
```

一次 LLM Turn 只能按以下优先级解释：

1. 存在合法 Tool Call：作为工具决策轮，`content` 不进入最终 assistant 历史。
2. 不存在 Tool Call 且 content 非空：作为最终回答。
3. 两者都为空：作为协议错误处理。

如果供应商同时返回 content 和 Tool Call，content 只允许作为临时 Agent 状态，不得与最终回答拼接或持久化。

## 6. 三个首期工具

### 6.1 search_knowledge_base

用途：搜索用户有权限访问的知识库内容。

Schema：

```json
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "minLength": 1,
      "maxLength": 500,
      "description": "独立、可检索的知识库查询语句"
    },
    "fileNames": {
      "type": "array",
      "items": {"type": "string"},
      "maxItems": 5,
      "description": "可选，仅在这些有权限访问的文件中搜索"
    },
    "topK": {
      "type": "integer",
      "minimum": 1,
      "maximum": 10,
      "default": 5
    }
  },
  "required": ["query"],
  "additionalProperties": false
}
```

执行规则：

1. 未提供 `fileNames` 时复用现有权限混合检索。
2. 提供 `fileNames` 时，先在用户可访问文档中解析为 `fileMd5` 集合，再将该集合加入 KNN/BM25 过滤。
3. 文件名匹配到多个文档时返回 `AMBIGUOUS` 和最小候选信息，不自行选择第一条。
4. Tool Result 只包含文件名、chunkId、截断片段和相关性分数，不暴露文件存储路径。
5. 对检索片段做总 token 预算和单文档配额，避免一个文档占满上下文。

### 6.2 list_documents

用途：列出用户自己的文档或所有可访问文档。

Schema：

```json
{
  "type": "object",
  "properties": {
    "scope": {
      "type": "string",
      "enum": ["ACCESSIBLE", "OWNED"],
      "default": "ACCESSIBLE"
    },
    "nameContains": {
      "type": "string",
      "maxLength": 100
    },
    "parseStatus": {
      "type": "string",
      "enum": ["PENDING", "PARSING", "READY", "FAILED", "DEAD_LETTER"]
    },
    "limit": {
      "type": "integer",
      "minimum": 1,
      "maximum": 20,
      "default": 10
    }
  },
  "additionalProperties": false
}
```

执行规则：

- `ACCESSIBLE` 复用层级组织权限逻辑；`OWNED` 只返回本人上传文档。
- 结果按更新时间倒序。
- 返回字段限定为 `fileMd5/fileName/size/uploadStatus/parseStatus/orgTagName/isPublic/updatedAt`。
- 结果超过 limit 时返回 `metadata.total` 和 `metadata.truncated=true`。
- 不返回下载 URL、MinIO 路径或文档正文。

### 6.3 get_document_status

用途：查询文件上传、解析和可检索状态。

Schema：

```json
{
  "type": "object",
  "properties": {
    "fileName": {
      "type": "string",
      "maxLength": 255
    },
    "fileMd5": {
      "type": "string",
      "pattern": "^[a-fA-F0-9]{32}$"
    }
  },
  "additionalProperties": false
}
```

校验要求：`fileName` 与 `fileMd5` 至少提供一个；两者同时存在时必须解析到同一可访问文档。

统一状态模型：

| 原始状态 | 工具状态 |
|---|---|
| `status=0` | `UPLOADING` |
| `status=1, parseStatus=null/0` | `PENDING` |
| `parseStatus=1` | `PARSING` |
| `parseStatus=2` | `READY` |
| `parseStatus=3` | `FAILED` |
| `parseStatus=4` | `DEAD_LETTER` |

执行规则：

- 只允许查询当前用户可访问文档。
- 文件名多义时返回 `AMBIGUOUS` 和候选文件，不默认 `findFirst()`。
- `READY` 表示解析与向量写入主流程已经成功结束。现有 `FileProcessingConsumer` 已在 `vectorizationService.vectorize(...)` 成功返回后才写入 `parseStatus=2`，工具直接沿用这一语义；异常或死信不得映射为 `READY`。
- 上传尚未合并时可返回分片进度；否则不执行额外 MinIO 调用。

## 7. Agent Loop 状态机

### 7.1 单轮处理流程

```text
1. 获取/创建 conversationId
2. 读取 Redis 工作集；必要时从 MySQL 原始消息恢复
3. 构造 system + history + current user
4. 若 Intent RAG 已启用：召回案例并注入路由示例
5. 对 messages + tool definitions + 输出预留做上下文预算
6. iteration = 1
7. 调用 LLM（始终携带全部 tools）
8. 若返回 final content：发送、持久化最终 turn、结束
9. 若返回 tool_calls：
   a. 校验迭代/调用数/重复指纹
   b. 追加完整 assistant.tool_calls 消息
   c. 顺序执行每个 Tool Call
   d. 每个结果追加对应 tool 消息
   e. 发送 tool_start/tool_result 状态事件
   f. 再次做上下文预算
   g. iteration++，回到步骤 7
10. 达到边界：发起一次不带 tools 的受限总结调用，基于已有结果回答
```

首期同一 LLM Turn 返回多个 Tool Call 时按 `index` 顺序执行，不并行。跨工具依赖由下一次 LLM
迭代决定，避免模型在尚未看到工具 A 结果时预先构造依赖工具 B 的参数。

### 7.2 重复调用检测

指纹：

```text
SHA-256(toolName + ":" + canonicalJson(arguments))
```

同一用户轮中出现相同指纹时不再次执行，返回：

```json
{
  "status": "INVALID_ARGUMENTS",
  "message": "检测到重复工具调用，请基于已有结果回答或调整参数",
  "retryable": false
}
```

### 7.3 达到上限

达到最大迭代、工具调用数或整轮超时后：

1. 停止注册新的 Tool Call。
2. 在 messages 中加入一条受控提示，说明工具预算已用完。
3. 使用不带 tools 的最终调用生成降级回答。
4. 回答必须区分已确认信息与未完成部分。
5. 记录 `terminationReason`，不得把上限当作成功完成。

### 7.4 取消

`DeepSeekClient` 必须把底层订阅句柄返回给 Agent Loop。每个 WebSocket session 保存当前调用的
`Disposable`/取消句柄。用户停止时：

- 取消上游 HTTP 流；
- 禁止启动下一次 Agent 迭代；
- 中止尚未开始的工具；
- 已执行完成的只读结果可以记录 trace，但不写入最终 assistant 历史；
- 发送一次明确的 cancelled completion 事件。

## 8. 消息协议与前端事件

保留现有 `chunk` 和 `completion`，新增可选状态事件：

```json
{"type":"agent_step","iteration":1,"status":"thinking"}
{"type":"tool_start","iteration":1,"tool":"get_document_status","callId":"call_x"}
{"type":"tool_result","iteration":1,"tool":"get_document_status","callId":"call_x","status":"SUCCESS"}
{"type":"agent_limit","reason":"MAX_ITERATIONS"}
{"type":"completion","status":"finished","traceId":"trace_x"}
```

事件不包含完整 arguments、文档正文或内部异常。前端可以先忽略新事件而不影响最终回答；后续再增加
“正在查询文档状态”等展示。

模型决策轮的 content 必须先暂存在本轮累积器中。流结束后：

- 无 Tool Call：作为最终回答发送并持久化；
- 有 Tool Call：不作为最终 `chunk` 发送，可转换为简短 `agent_step` 状态。

该策略优先保证消息正确性，避免“准备搜索……”与正式回答拼接污染历史。若未来需要完整 token 级直答
流式体验，可在确认具体模型保证 content/tool_calls 互斥后进行供应商级优化。

## 9. 上下文与历史策略

### 9.1 持久化

- MySQL `conversation_messages` 继续作为 user/assistant 原始事实日志。
- Redis 继续保存可丢弃、可重建的工作集。
- 中间 assistant.tool_calls 和 tool results 不写入原始对话历史。
- 每一用户轮只持久化最终 user + assistant；取消或失败按现有错误策略处理。
- 工具轨迹使用结构化日志记录，首期不新增数据库表。

### 9.2 每轮预算

每次 LLM 调用都重新执行 `ContextBudgetService.fit()`，预算包含：

- 固定 system rules
- Tool Definitions
- 最终输出预留
- 当前用户消息
- 最近尚在进行的 assistant/tool 事务
- Intent Case 示例（若启用）

保护顺序：当前用户消息 > 当前 Agent 事务 > system/tool definitions > 最近原始历史 > 旧摘要。

Tool Result 进入消息前先做自身截断；不能只依赖全局上下文截断。

## 10. Intent RAG（文章方案 C）

### 10.1 启用条件

Intent RAG 默认关闭。只有完成多工具基线评测并确认以下至少一种问题后才实现/启用：

- 口语化表达导致工具误选。
- `list_documents` 与 `search_knowledge_base` 语义混淆。
- 多轮指代导致文件名或查询参数缺失。
- 必须调用工具的问题被模型直接回答。
- Tool arguments 在常见表达下稳定抽取失败。

### 10.2 独立索引

新建 `intent_cases`，不得与用户上传文档的 `knowledge_base` 混用。

建议字段：

```json
{
  "caseId": "status-001",
  "toolName": "get_document_status",
  "historyText": "",
  "currentQuery": "我刚上传的年报怎么还没好",
  "expectedArguments": {"fileName": "年报"},
  "decisionTags": ["upload", "status", "colloquial"],
  "scopeType": "GLOBAL",
  "scopeId": null,
  "enabled": true,
  "version": 1,
  "vector": []
}
```

索引约束：

- `vector` 使用与项目一致的 bge-m3 1024 维向量。
- `scopeType` 首期支持 `GLOBAL` 和 `ORG`。
- `expectedArguments` 仅作为 Few-shot 数据，不直接执行。
- 不保存或注入详细 Chain-of-Thought，只保存简短 `decisionTags`。
- 只有人工审核并启用的 Case 才能进入在线召回。

### 10.3 召回查询

查询文本只组合最近最多 2 条用户消息和当前问题，不包含 assistant 输出、Tool Result、卡片文本和摘要：

```text
历史用户问题：[...]
当前用户问题：[...]
```

首期召回：

- KNN TopK：3
- 可选 BM25 命中作为补充
- 仅过滤 `GLOBAL` 或当前用户组织可用 Case
- 相似度阈值与 Top1/Top2 margin 通过开发集校准，不硬编码拍脑袋数值

### 10.4 Prompt 注入

案例作为受信任的路由示例加入唯一 system 消息中的独立数据区：

```text
[受信任的工具路由示例，仅用于参考工具选择和参数格式]
案例1：...
案例2：...
[示例结束]
```

规则：

- Case 不能覆盖固定 system rules。
- 最多 3 个案例，总预算不超过 1000 token。
- 召回分数不足、ES/Embedding 失败或预算不足时，完全跳过示例，回退普通 Tool Calling。
- Intent RAG 不直接返回最终答案，也不绕过 Tool Registry 和权限校验。

### 10.5 Case 来源

初始每个工具准备 10～15 个经人工审核的 Case，覆盖：

- 标准问法
- 口语化问法
- 多轮指代
- 参数缺失
- 与其他工具的难负例
- 不应调用任何工具的反例

后续从评测和线上匿名化 Bad Case 中增量维护。可以离线使用 LLM 生成同义变体，但必须人工审核、去重，
并保证训练 Case 与最终评测集隔离。

## 11. 安全设计

1. 工具 Schema 不暴露安全上下文字段。
2. Tool Registry 只注册明确允许的只读工具。
3. 每个工具在执行业务查询前重新进行权限过滤，不能信任上轮 Tool Result。
4. 文件名解析只在可访问文档集合内进行。
5. `FORBIDDEN` 不返回目标文档是否存在、所有者或组织信息。
6. Intent Case 与上传文档分索引；用户文档不能影响控制层路由案例。
7. Tool Result 作为不可信业务数据注入模型，必须使用明确边界，不允许其中内容覆盖 system rules。
8. arguments 设置长度、数组数量和未知字段限制，防止模型或用户制造超大请求。
9. Trace 日志默认脱敏，不记录文档正文、完整查询结果、JWT 或 API Key。

## 12. 配置

建议新增：

```yaml
ai:
  agent:
    enabled: true
    max-iterations: 3
    max-tool-calls: 6
    tool-timeout-seconds: 5
    total-timeout-seconds: 45
    max-tool-result-tokens: 3000
    intent-rag:
      enabled: false
      top-k: 3
      max-case-tokens: 1000
      max-history-user-turns: 2
```

所有配置必须映射到 `AiProperties`，并在启动时校验非负值及上下限关系。

## 13. 可观测性

每个用户请求生成 `traceId`，每次迭代记录：

- conversationId（脱敏或内部 ID）
- iteration
- model
- finishReason
- toolName
- toolStatus
- toolLatencyMs
- llmLatencyMs
- prompt/completion tokens（供应商返回时）
- accumulatedToolCalls
- terminationReason
- intentRagEnabled
- intentCaseCount
- totalLatencyMs

建议 Micrometer 指标：

```text
agent.requests
agent.iterations
agent.tool.calls{tool,status}
agent.tool.latency{tool}
agent.termination{reason}
agent.intent_rag.fallback{reason}
agent.total.latency
```

## 14. 测试策略

### 14.1 协议解析测试

使用固定 SSE fixtures 覆盖：

- content-only
- 单 Tool Call 分片 arguments
- 多 Tool Call 按不同 index 交错分片
- id/name 仅首片出现
- malformed arguments
- 缺失 ID
- content 与 Tool Call 同时出现
- finish_reason 缺失或异常

### 14.2 Tool Registry 测试

- 重名工具启动失败
- 未知工具返回结构化错误
- 未知 arguments 字段被拒绝
- 安全上下文不能由 arguments 覆盖
- 超时映射为 `TIMEOUT`

### 14.3 Agent Loop 测试

- 不调用工具直接回答
- `search → final`
- `status → search → final`
- 一轮返回多个独立 Tool Call
- 工具 `NO_RESULT` 后模型结束
- 工具错误后模型调整参数重试
- 重复指纹被阻断
- 最大迭代与最大调用数
- 上下文预算每轮生效
- 用户取消后不进入下一轮
- 只有最终 assistant 内容进入历史

### 14.4 业务工具测试

- 用户自己的、公开的和组织文档可见
- 无权限文档不可见且不泄漏存在性
- 重名文件返回 `AMBIGUOUS`
- 状态映射覆盖 0～4
- 搜索文件过滤同时作用于 KNN 和 BM25
- Tool Result 长度上限生效

### 14.5 Intent RAG 测试

- 仅召回 GLOBAL/当前组织 Case
- 禁用 Case 不召回
- 失败时回退普通 Tool Calling
- 不将 assistant/tool 内容拼入召回 Query
- Case 总 token 上限
- 评测集不出现在 Case 库中

## 15. 离线评测

建立至少 100 条固定评测数据：

| 类型 | 数量 |
|---|---:|
| `search_knowledge_base` | 25 |
| `list_documents` | 20 |
| `get_document_status` | 20 |
| 不调用工具 | 15 |
| 多步工具链 | 10 |
| 歧义、越权、错误恢复 | 10 |

每条数据包含：

```json
{
  "history": [],
  "query": "...",
  "expectedToolSequence": ["get_document_status", "search_knowledge_base"],
  "expectedArguments": [{}, {}],
  "expectedFinalBehavior": "ANSWER|ASK_CLARIFICATION|FORBIDDEN|NO_RESULT"
}
```

核心指标：

- Tool Selection Accuracy
- Required-tool Recall
- No-tool Precision
- Arguments Exact Match / 字段级 F1
- Tool Sequence Exact Match
- Multi-turn Resolution Accuracy
- Permission Violation Count（必须为 0）
- 平均迭代数与工具调用数
- P50/P95 总延迟
- 单请求 token 使用量

评测顺序：

1. 纯多工具 Tool Calling 基线。
2. 开启 Intent RAG，使用相同 held-out 数据。
3. 对比准确率、参数、序列、延迟和 token。
4. 只有准确率指标有明确改善且无安全回归时，才把 Intent RAG 作为默认配置候选。

## 16. 验收标准

### 16.1 多工具 Agent v1

- 三个工具均通过 Tool Registry 注册，不再由 `ChatHandler` 按名称硬编码分支。
- 模型可以完成 `get_document_status → search_knowledge_base → final` 的真实多步流程。
- 每轮调用继续携带全部工具，且最多 3 轮、6 次工具执行。
- 完整支持多个流式 Tool Call 的 `id/name/arguments` 聚合。
- 未知工具、非法参数、重复调用、超时和权限拒绝均返回结构化 Tool Result。
- 用户取消会真实取消上游模型流，且不会启动下一次迭代。
- 中间思考文本和 Tool Result 不污染最终 assistant 历史。
- 所有新增单元测试通过；项目提供不依赖外部基础设施的绿色单元测试命令。
- 完成 100 条基线评测并输出机器可读结果和 Markdown 汇总。

### 16.2 Intent RAG v1.1

- 使用独立 `intent_cases` 索引，并与 `knowledge_base` 权限及 Schema 隔离。
- 每轮最多注入 3 个案例且不超过 1000 token。
- 失败或低置信时无损回退普通 Tool Calling。
- Case 库与 held-out 评测集隔离。
- 输出开启/关闭 Intent RAG 的同集 A/B 报告。
- 不降低 Required-tool Recall，不产生任何权限回归，并清晰报告准确率收益与 P95 延迟代价。

## 17. 实施阶段与 10 天边界

### 阶段 0：基线收口（第 1～2 天）

- 收口并提交当前上下文压缩改动。
- 修复测试 Mock 与测试环境分层，使单元测试命令全绿。
- 保存单工具 Agentic RAG 的行为基线。

### 阶段 1：多工具 Runtime（第 3～5 天）

- 新增领域模型、Tool Registry 和三个工具。
- 重构 SSE Tool Call 聚合。
- 实现有界 Agent Loop、取消和上下文预算。

### 阶段 2：测试与评测（第 6～8 天）

- 完成协议、循环、权限和工具单测。
- 建立 100 条评测集并运行基线。
- 仅当 Bad Case 支持时实现 Intent RAG 最小版本并 A/B。

### 阶段 3：交付证据（第 9～10 天）

- README 架构与数据流。
- 评测结果表与典型 Bad Case。
- 2～3 分钟演示。
- 简历描述、面试问答与版本标签。

如果阶段 0～2 延误，优先放弃 Intent RAG，不牺牲多工具 Agent、测试全绿和评测基线。

## 18. 预计文件边界

建议新增：

```text
src/main/java/com/yizhaoqi/smartpai/agent/
  AgentLoopService.java
  AgentTrace.java
  LlmTurn.java
  TokenUsage.java
  ToolCall.java
  ToolExecutionContext.java
  ToolResult.java
  ToolStatus.java

src/main/java/com/yizhaoqi/smartpai/tool/
  AgentTool.java
  ToolRegistry.java
  SearchKnowledgeBaseTool.java
  ListDocumentsTool.java
  GetDocumentStatusTool.java

src/main/java/com/yizhaoqi/smartpai/intent/
  IntentCase.java
  IntentCaseRetriever.java
  IntentCaseIndexer.java

src/main/resources/es-mappings/
  intent_cases.json

src/test/java/com/yizhaoqi/smartpai/agent/
src/test/java/com/yizhaoqi/smartpai/tool/
src/test/java/com/yizhaoqi/smartpai/intent/

docs/eval/agent-tool-dataset.json
docs/eval/eval_agent_tools.py
docs/eval/agent-tool-report.md
```

建议修改：

```text
ChatHandler.java
DeepSeekClient.java
HybridSearchService.java
DocumentService.java
FileUploadRepository.java
AiProperties.java
application.yml
README.md
```

`ChatHandler` 重构时应减少职责，不在其中继续增加工具 `switch` 或 `if/else`。

## 19. 面试叙事边界

完成 v1 后可以准确描述为：

> 将单工具、单步 Agentic RAG 重构为权限感知的多工具 Agent Runtime，支持标准化工具注册、流式
> Tool Call 状态机、JSON Schema 校验和有界多轮执行。

只有完成 Intent RAG 及 A/B 报告后才可以描述为：

> 基于多工具评测 Bad Case 建立独立 Intent Case 索引，通过检索动态 Few-shot 改善工具路由和参数抽取，
> 并量化准确率提升与延迟代价。

完整方案 D 未实现时，只能作为 Roadmap，不得宣称已经具备通用多轮槽位状态机。
