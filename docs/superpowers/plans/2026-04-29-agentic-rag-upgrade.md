# Plan: Agentic RAG 升级方案

## Background

### 当前架构：Naive RAG（单次检索 + 单次生成）

```
用户发消息 → WebSocket → ChatHandler.processMessage()
  ├─ getOrCreateConversationId()  → Redis
  ├─ getConversationHistory()     → Redis
  ├─ searchWithPermission()       → ES 单次 RRF 混合搜索 (topK=10)
  ├─ buildContext()               → 格式化为 [1] (file) text\n
  ├─ deepSeekClient.streamResponse() → GLM-5 单次流式生成
  └─ updateConversationHistory()  → Redis + MySQL
```

**核心特征**：
- 单次搜索，单次生成，无迭代
- 无 Tool Calling / Function Calling 支持
- 无法分解复杂查询
- 无法判断检索结果是否充分
- 搜索策略固定（RRF 融合 BM25 + KNN），无法根据问题类型调整

### 问题场景

| 场景 | 当前行为 | Agentic RAG 理想行为 |
|------|---------|---------------------|
| "对比 A 文档和 B 文档的差异" | 单次搜索混合 A/B 结果，LLM 被动使用 | 分解为两个子查询分别检索，再综合对比 |
| 搜索结果不相关 | LLM 基于不相关的上下文编造回答 | 检测到不相关后自动改写查询重新检索 |
| 闲聊/通用问题 | 仍然触发 ES 搜索，浪费资源 | Router 直接跳过检索，走纯 LLM 回答 |
| 需要 web 搜索的时事问题 | 无法处理 | 调用 Web Search 工具获取最新信息 |
| 多步骤推理问题 | 单次检索信息不足，回答不完整 | ReAct 循环：检索→推理→再检索→...→回答 |

### LLM 能力确认

- 当前模型：**ZhipuAI GLM-5**（智谱），通过 OpenAI 兼容 API 调用
- API 地址：`https://open.bigmodel.cn/api/coding/paas/v4`
- **Function Calling 支持**：GLM-5 完整支持 OpenAI 兼容的 `tools` 参数，Toolcall-Badcase 得分 95.8
- 流式 Tool Calling 支持：SSE 中返回 `tool_calls` 字段
- **无需更换模型**，现有 GLM-5 即可支持 Agentic RAG 所需的全部能力

---

## Design Decisions

### D1: 不引入 AI 框架（Spring AI / LangChain4j），手写 ReAct 循环

**分析**：
- 当前 `DeepSeekClient` 是纯 WebClient + Jackson 实现，约 166 行，简洁可控
- Spring AI 需要 Spring Boot 3.2+，当前项目版本兼容但引入重依赖
- LangChain4j 是额外依赖，且 Java 生态不如 Python 成熟
- ReAct 循环的核心逻辑不复杂：`while(needTool) { callTool; observe; }`
- 手写可以完全控制 Tool Calling 的请求/响应格式，与现有 WebClient 流式架构一致
- 项目已有完善的 SSE 解析（`processChunk`），扩展 tool_calls 解析成本很低

**决定**：不引入外部 AI 框架。在现有 `DeepSeekClient` 基础上扩展 Tool Calling 支持，手写 ReAct 循环。保持零新依赖。

### D2: 采用 ReAct 模式而非其他 Agentic 模式

**分析**：
Spring AI 总结了 5 种 Agentic 模式：
1. **Chain Workflow**（链式）— 顺序执行多步，但每步是预定义的
2. **Parallelization**（并行）— 多个任务同时执行，汇总结果
3. **Routing**（路由）— 根据输入分类到不同处理路径
4. **Orchestrator-Workers**（编排-执行）— 主 agent 分解任务给子 agent
5. **Evaluator-Optimizer**（评估-优化）— 循环：生成→评估→优化

**决定**：以 **ReAct（Reasoning + Acting）** 为核心模式。ReAct 是最通用的 Agentic 模式，本质上是 Routing + Evaluator-Optimizer 的结合体。具体来说：
- LLM 自己决定是否需要调用工具（Routing）
- LLM 自己评估结果是否充分，决定是否继续（Evaluator-Optimizer）
- 工具调用是原子的，不需要 Orchestrator-Workers 的复杂编排
- 初期不需要 Parallelization（单用户对话场景，并行收益有限）

### D3: 工具定义策略 — 从 3 个核心工具开始

**决定**：定义以下工具（Tool），优先级排序：

| 工具 | 功能 | 优先级 |
|------|------|--------|
| `knowledge_search` | 调用现有 `HybridSearchService.searchWithPermission()` | **P0** |
| `calculate` | 执行数学计算（eval） | **P1** |
| `web_search` | 调用外部搜索 API（Tavily/Serper） | **P2** |

P0 阶段只实现 `knowledge_search`，这是将现有 RAG 升级为 Agentic 的最小改动。`calculate` 和 `web_search` 是锦上添花，后续按需添加。

### D4: 流式 Tool Calling 的实现方式

**问题**：GLM-5 的 Tool Calling 在流式模式下，`tool_calls` 的 `function.name` 和 `function.arguments` 是分多个 chunk 发送的，需要缓冲拼接。

**决定**：
1. `DeepSeekClient` 新增 `streamResponseWithTools()` 方法，支持 `tools` 参数
2. `processChunk` 扩展：检测 `delta.tool_calls` 字段
3. 当检测到 `finish_reason: "tool_calls"` 时，暂停文本输出，收集完整的 tool_calls
4. 回调 `onToolCall(toolName, arguments)` 让 ChatHandler 执行工具
5. ChatHandler 将工具结果追加到 messages，再次调用 `streamResponseWithTools()`
6. 循环直到 `finish_reason: "stop"`（LLM 认为无需更多工具调用）

### D5: 前端展示工具调用过程

**决定**：在 chat-message.vue 中新增工具调用状态展示：
- 当 LLM 正在调用工具时，显示 "正在搜索知识库..." 的加载状态
- 工具调用完成后，折叠展示调用详情（可展开查看）
- 最终回答仍然流式渲染

这比当前的 "三个点加载动画" 信息量更大，用户能感知到 AI 的推理过程。

### D6: ReAct 最大循环次数限制

**决定**：设置 `MAX_ITERATIONS = 5`。防止 LLM 陷入无限循环（反复调用工具但不生成最终回答）。5 次迭代足以覆盖绝大多数场景（初始搜索 + 4 次修正）。

### D7: 系统提示词改造

**决定**：改造 `ai.prompt.rules` 系统提示词，增加工具使用引导：
- 告知 LLM 有哪些工具可用
- 引导 LLM 判断何时需要使用工具（问题涉及文档知识 → search；通用知识 → 直接回答）
- 移除当前的 "硬塞上下文" 模式（`<<REF>>...<<END>>`），改为 LLM 自主决定是否检索

---

## Implementation Tasks

### Task 0: DeepSeekClient 支持 Tool Calling（核心基础设施）

**文件**: `src/main/java/com/yizhaoqi/smartpai/client/DeepSeekClient.java`

**改动**:

1. 新增 `ToolDefinition` 内部类/record，描述工具的 JSON Schema：
```java
public record ToolDefinition(String name, String description, Map<String, Object> parameters) {
    public Map<String, Object> toOpenAiFormat() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", name,
                "description", description,
                "parameters", parameters
            )
        );
    }
}
```

2. 新增 `streamResponseWithTools()` 方法签名：
```java
public void streamResponseWithTools(
    List<Map<String, String>> messages,  // 完整消息列表（包含历史）
    List<ToolDefinition> tools,           // 可用工具列表
    Consumer<String> onChunk,             // 文本片段
    Consumer<List<ToolCall>> onToolCalls, // LLM 请求的工具调用
    Consumer<Throwable> onError,
    Runnable onComplete
)
```

3. `buildRequest` 扩展：当 `tools != null` 时添加 `tools` 字段到请求体
4. `processChunk` 扩展：解析 `delta.tool_calls` 和 `finish_reason: "tool_calls"`
5. 新增 `ToolCall` record：
```java
public record ToolCall(String id, String name, String arguments) {}
```

**关键点**：流式模式下 `tool_calls` 分多个 chunk 发送，需要用 `StringBuilder` 缓冲 `arguments`，直到 `finish_reason: "tool_calls"` 时一次性回调。

**改动量**：~120 行新增/修改

---

### Task 1: 定义知识库搜索工具

**文件**: 新建 `src/main/java/com/yizhaoqi/smartpai/tool/KnowledgeSearchTool.java`

**改动**:

```java
@Component
public class KnowledgeSearchTool {

    @Autowired
    private HybridSearchService searchService;

    // 工具定义（OpenAI Function Calling 格式）
    public static final ToolDefinition DEFINITION = new ToolDefinition(
        "knowledge_search",
        "在企业知识库中搜索相关文档。输入搜索查询，返回最相关的文档片段及其来源文件名。",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of(
                    "type", "string",
                    "description", "搜索查询，使用关键词或自然语言描述要查找的信息"
                )
            ),
            "required", List.of("query")
        )
    );

    /**
     * 执行搜索
     * @param argumentsJson JSON 格式的参数 {"query": "..."}
     * @param userId 当前用户 ID（用于权限过滤）
     * @return 格式化的搜索结果文本
     */
    public String execute(String argumentsJson, String userId) {
        // 1. 解析 argumentsJson → query
        // 2. searchService.searchWithPermission(query, userId, 10)
        // 3. 格式化为 "[1] (file.pdf) chunk text\n"
        // 4. 返回文本
    }
}
```

**改动量**：~60 行

---

### Task 2: ChatHandler ReAct 循环

**文件**: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`

**改动**: 这是最大的改动。将 `processMessage()` 从线性流程改为 ReAct 循环。

**新的 processMessage 伪代码**：
```
1. getOrCreateConversationId()
2. getConversationHistory()
3. 构建初始 messages 列表（system + history + user message）
4. 定义可用工具列表 [knowledge_search]
5. ReAct 循环（最多 MAX_ITERATIONS=5 次）:
   a. 调用 deepSeekClient.streamResponseWithTools(messages, tools, ...)
   b. 如果 LLM 返回文本 → 流式发送给前端，等待完成
   c. 如果 LLM 返回 tool_calls:
      - 通知前端 "正在搜索..."
      - 执行每个 tool_call（KnowledgeSearchTool.execute）
      - 将工具结果作为 tool message 追加到 messages
      - 继续循环
   d. 如果 finish_reason="stop" → 退出循环
6. updateConversationHistory()
```

**关键改动**：
- `processMessage()` 方法体重构
- 新增 `executeToolCall()` 辅助方法
- WebSocket 消息协议扩展：新增 `type: "tool_call"` 和 `type: "tool_result"` 消息类型
- 移除旧的 "先搜索再生成" 硬编码流程

**改动量**：~150 行新增/修改（processMessage 完全重写）

---

### Task 3: 系统提示词改造

**文件**: `src/main/resources/application.yml`

**改动**: 重写 `ai.prompt.rules`

当前提示词：
```yaml
rules: |
  你是 Brain.ai 知识助手。
  回答规范：
  1. 用简体中文回答...
  2. <<REF>> 与 <<END>> 之间为检索到的参考信息...
  3. 若用户的问题属于通用知识...
```

新提示词方向：
```yaml
rules: |
  你是 Brain.ai 知识助手。

  你可以使用以下工具：
  - knowledge_search: 在企业知识库中搜索文档

  回答规范：
  1. 用简体中文回答，先给结论再展开论述。
  2. 判断用户问题是否需要检索知识库：
     - 涉及文档内容、公司政策、项目细节等 → 调用 knowledge_search
     - 通用知识、数学计算、闲聊 → 直接回答
  3. 如果搜索结果不够充分，可以改写查询重新搜索。
  4. 基于搜索结果回答时，标注来源：(来源: 文件名)
  5. 回答长度应与问题复杂度匹配。
```

**注意**：移除 `<<REF>>` / `<<END>>` 机制。LLM 不再被动接收上下文，而是主动通过 tool calling 获取。

同时需要移除 `DeepSeekClient.buildMessages()` 中硬塞 context 到 system message 的逻辑。

**改动量**：~20 行 YAML + ~30 行 Java

---

### Task 4: 前端 WebSocket 消息协议扩展

**文件**:
- `frontend/src/views/chat/modules/chat-list.vue`
- `frontend/src/views/chat/modules/chat-message.vue`
- `frontend/src/typings/api.d.ts`

**改动**:

1. **WebSocket 消息类型扩展**：
   - 现有：`{ chunk: "text" }`、`{ type: "completion" }`、`{ error: "msg" }`
   - 新增：
     ```typescript
     // 工具调用状态通知
     { type: "tool_call", tool: "knowledge_search", args: { query: "..." } }
     { type: "tool_result", tool: "knowledge_search", result: "搜索到 3 条结果..." }
     ```

2. **chat-list.vue**: 处理新的 WebSocket 消息类型，在消息列表中插入工具调用状态

3. **chat-message.vue**: 为工具调用消息添加折叠展示 UI
   ```
   ┌─────────────────────────────────────┐
   │ 🔍 正在搜索知识库... "用户查询内容"    │
   │ ┄┄┄ 点击展开搜索结果 ┄┄┄              │
   │   [1] (file.pdf) 相关内容片段...      │
   └─────────────────────────────────────┘
   ```

4. **api.d.ts**: Message 类型扩展，新增 `toolCalls` 字段

**改动量**：~100 行

---

### Task 5: ChatHandler 兼容性保障（平滑迁移）

**问题**：Agentic 模式下 LLM 自主决定是否调用工具，但 LLM 可能不稳定（有时该搜索却不搜索，有时不该搜索却搜索）。

**决定**：引入 `agent.mode` 配置项控制模式：

```yaml
agent:
  mode: agentic  # agentic | naive
```

- `agentic`：ReAct 循环，LLM 自主决定
- `naive`：保持现有流程（每次必搜索 + 硬塞上下文），作为降级开关

ChatHandler 根据 `agent.mode` 选择走哪条路径。这样即使 Agentic 模式出问题，一行配置就能切回。

**改动量**：~20 行

---

## 复杂度评估

### 整体评估：**中等偏低**

| 维度 | 评估 | 理由 |
|------|------|------|
| 代码改动量 | 中 | ~480 行新增/修改（后端 ~380 + 前端 ~100） |
| 架构变动 | 中 | ChatHandler 重构为核心循环，但外部接口不变 |
| 新依赖 | 无 | GLM-5 已支持 Tool Calling，无需新依赖 |
| 数据库/基础设施 | 无 | 不涉及数据库 schema 变更，不涉及新中间件 |
| 回归风险 | 低 | 有 `agent.mode` 配置开关，一行切回 naive |
| 测试成本 | 中 | 需要测试多轮 Tool Calling 场景，但逻辑集中 |

### 各 Task 复杂度

| Task | 改动量 | 复杂度 | 风险 |
|------|--------|--------|------|
| Task 0: DeepSeekClient Tool Calling | ~120 行 | **中** | 流式 tool_calls 拼接需要仔细处理 |
| Task 1: KnowledgeSearchTool | ~60 行 | **低** | 包装现有 searchService，逻辑简单 |
| Task 2: ChatHandler ReAct 循环 | ~150 行 | **中高** | 核心重构，需要处理多种边界情况 |
| Task 3: 系统提示词 | ~50 行 | **低** | 纯配置 |
| Task 4: 前端协议扩展 | ~100 行 | **中** | WebSocket 消息类型处理 |
| Task 5: 兼容性开关 | ~20 行 | **低** | 简单分支 |

### 主要难点

1. **流式 Tool Calling 拼接**（Task 0）：GLM-5 的 SSE 中 `tool_calls` 分多个 chunk 发送，`function.arguments` 是逐步拼接的 JSON。需要正确缓冲和处理不完整的 JSON。

2. **ReAct 循环与流式响应的配合**（Task 2）：
   - 每次 LLM 调用都是异步流式（WebClient + Reactor）
   - Tool Calling 需要在流结束后判断是返回文本还是返回 tool_calls
   - 需要正确管理消息列表的状态（每次循环追加 tool message）

3. **WebSocket 消息协议兼容**（Task 4）：前端需要同时处理文本 chunk 和工具调用状态，不能互相干扰。

---

## 优先级排序

| 优先级 | Task | 说明 |
|--------|------|------|
| **P0** | Task 0: DeepSeekClient Tool Calling | 所有后续 Task 的前置 |
| **P0** | Task 1: KnowledgeSearchTool | 最小可用的工具 |
| **P0** | Task 2: ChatHandler ReAct 循环 | 核心逻辑 |
| **P0** | Task 3: 系统提示词 | 引导 LLM 使用工具 |
| **P1** | Task 4: 前端协议扩展 | 用户体验，不影响核心功能 |
| **P1** | Task 5: 兼容性开关 | 生产安全保障 |

建议先完成 Task 0-3（纯后端），用 WebSocket 客户端（如 wscat）验证 Tool Calling 正常工作后，再做 Task 4 前端适配。

---

## 升级后的架构

```
用户发消息 → WebSocket → ChatHandler.processMessage()
  ├─ getOrCreateConversationId()
  ├─ getConversationHistory()
  ├─ 构建 messages（system prompt with tool guidance + history + user msg）
  │
  ├─ ReAct 循环 (max 5 iterations):
  │   ├─ deepSeekClient.streamResponseWithTools(messages, tools)
  │   ├─ LLM 返回 tool_calls?
  │   │   ├─ YES → 通知前端 "正在搜索..."
  │   │   │       → knowledgeSearchTool.execute(query, userId)
  │   │   │       → 追加 tool message 到 messages
  │   │   │       → 继续循环
  │   │   └─ NO (finish_reason=stop) → 流式发送文本给前端 → 退出循环
  │
  └─ updateConversationHistory()
```

**对比当前架构**：
- 移除了硬编码的 `searchWithPermission()` → `buildContext()` → 塞入 system message
- LLM 自主决定是否搜索、搜索什么、是否需要重新搜索
- 前端能看到 AI 的推理过程（工具调用状态）
- 一行配置可切回 naive 模式

---

## 不改动的部分

- **ES 索引结构**：向量存储不变
- **HybridSearchService**：RRF 搜索逻辑不变，只是调用方从 ChatHandler 变为 KnowledgeSearchTool
- **ConversationService**：会话管理不变
- **MinIO / 文件解析 / 向量化**：文档处理链路不变
- **权限过滤**：searchWithPermission 内置权限过滤，Agentic 模式下同样生效
- **前端路由 / 登录 / 文件管理**：无关模块不变

---

## 未来扩展（本次不实现）

1. **更多工具**：`calculate`（数学计算）、`web_search`（网络搜索）、`document_preview`（文档预览）
2. **Query Decomposition**：复杂问题自动分解为多个子查询并行搜索
3. **Self-RAG**：LLM 自我评估生成质量，判断是否需要重新检索（Evaluator-Optimizer 模式）
4. **Streaming Tool Calls**：前端实时展示工具调用的参数（而非等工具执行完才显示）
5. **工具调用记忆**：将 tool_calls 记录到对话历史中，下次对话能参考之前的搜索结果
