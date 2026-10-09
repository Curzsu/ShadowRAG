# ShadowRAG Agentic RAG 完善度评估报告

> 历史评估：下文结论对应 2026-08-06 的 WebSocket／两阶段实现，保留当时发现与证据，不代表当前代码。当前聊天协议与保存边界见[聊天指南](chat.md)，其他现行说明见[文档索引](index.md)。

> 评估时间：2026-08-06
> 评估范围：`ChatHandler` / `ChatWebSocketHandler` / `DeepSeekClient` / `HybridSearchService` / `ConversationCompressionService` 及相关配置
> 结论：**当前是一个"两阶段" Agentic RAG，基础可用，但存在若干明确 bug，且 Agentic 能力较浅，有较大扩展空间。**

---

## 一、当前实现概览

### Agentic RAG 主流程

```
用户消息 (WebSocket)
  → ChatWebSocketHandler.handleTextMessage
  → ChatHandler.processMessage(userId, userMessage, session)
      1. 取会话历史 (Redis: conversation:{id})
      2. 构建 messages = [system rules] + history + [user]   ← 不含检索 context，让 LLM 决策
      3. 第一次 LLM 流式调用（带 search_knowledge_base 工具）
           ├─ LLM 直接回答（无 tool_call）→ 流式输出 → 存历史 → 完成
           └─ LLM 调用工具 → executeToolAndRespond
                a. 解析 tool_call.arguments.query（失败回退 userMessage）
                b. HybridSearchService.searchWithPermission(query, userId, 10)
                     KNN + BM25 → Java 端 RRF(K=60) 融合 → Cross-Encoder 精排
                c. 追加 assistant(tool_calls) + tool(result) 到 messages
                d. 第二次 LLM 流式调用（不带 tools）→ 基于结果回答 → 存历史 → 完成
```

### 已具备的能力

| 能力 | 实现位置 | 状态 |
|------|----------|------|
| LLM 自主决策是否检索 | `ChatHandler` + Function Calling | ✅ |
| 两阶段工具调用 | `processMessage` / `executeToolAndRespond` | ✅ |
| 混合检索 (KNN+BM25) | `HybridSearchService` | ✅ |
| RRF 融合 (Java 端, K=60) | `fuseWithRRF` | ✅ |
| Cross-Encoder 精排 | `RerankerClient` + `applyRerank` | ✅ |
| 多租户权限过滤 | `buildPermissionFilter` | ✅ |
| 流式输出 | `streamWithTools` / `streamResponse` | ✅ |
| 长记忆压缩（软/硬阈值+异步+重试） | `ConversationCompressionService` | ✅ |
| 停止响应 | `stopFlags` + `stopResponse` | ⚠️ 有缺陷 |
| 工具参数解析失败回退 | `parseSearchQuery` → userMessage | ✅ |

---

## 二、Bug 清单（按严重度排序）

### 🔴 Bug 1：工具调用场景下，第一次调用的"思考文本"与正式回答重复输出，且污染历史

**位置**：`ChatHandler.processMessage` + `executeToolAndRespond`

**问题**：
- `responseBuilder` 在第一次调用时通过 `onContentDelta` 累积 LLM 文本并发给前端。
- 当 LLM 决定调用工具时，它常会先输出一段过渡文本（如"好的，我来搜索知识库…"），这段文本已被流式发给用户。
- 随后 `executeToolAndRespond` **复用同一个 `responseBuilder`**，第二次调用的正式回答继续 append 进去，继续发给前端。
- 最终 `responseBuilder.toString()` = `第一次思考文本 + 第二次正式回答`，这个拼接体被存入会话历史。

**影响**：
1. 用户看到"好的，我来搜索…[正式回答]"这种割裂的输出。
2. 历史里的 assistant 消息是污染的，后续轮次 LLM 读到的上下文含无用过渡文本。
3. 压缩摘要也会基于这段污染文本生成。

**建议修复**：
- 当检测到 `toolCallDetected` 时，第一次调用累积的 `responseBuilder` 应清空（或单独用于"思考"展示，不并入最终回答）。
- 第二次调用用新的 builder，仅把正式回答存历史。
- 若要保留"思考过程"给前端，用独立的事件类型（如 `type: "thinking"`）发送，不混入 `chunk`。

---

### 🔴 Bug 2：`stopFlags` 无法真正中断流，且存在时序泄漏

**位置**：`ChatHandler.stopResponse` / `sendResponseChunk`

**问题**：
- `stopResponse` 仅设置 `stopFlags.put(sessionId, true)`，底层 WebClient 流（`deepSeekClient.streamWithTools` 的 `subscribe`）**仍在继续**，LLM 仍在生成、仍计费。
- `sendResponseChunk` 检查 stopFlags 只是"跳过发送"，不中断数据源。
- 清理逻辑用 `new Thread(() -> { Thread.sleep(2000); stopFlags.remove(sessionId); })`：
  - 2 秒固定等待：若 LLM 还在输出（尤其第二次调用），标志被提前清除，后续 chunk 又能发出。
  - 若用户连点两次停止，会起多个清理线程。
  - `new Thread()` 裸线程无命名、无异常兜底。

**影响**：停止按钮形同虚设（前端看不到新内容，但后端仍在烧 token）；并发停止时状态混乱。

**建议修复**：
- 用 `Flux` 的 `takeUntilOther` / `cancel()` 真正取消上游订阅（保存 `Disposable`，stop 时 `dispose()`）。
- 用 ScheduledExecutor 替代 `new Thread`，或在流 onComplete/`onError` 时清除标志。

---

### 🟠 Bug 3：同一会话并发消息无串行化，历史 read-modify-write 非原子

**位置**：`ChatWebSocketHandler.handleTextMessage` / `ChatHandler.updateConversationHistory`

**问题**：
- WebSocket 允许客户端快速连发多条消息，`processMessage` 会被并发调用（WebClient 是非阻塞的）。
- `updateConversationHistory` = `getConversationHistory`（读）→ `add`（改）→ `set`（写），三步非原子。
- 两个并发流程后写的会覆盖先写的，导致用户消息/回答丢失。

**影响**：快速追问场景下历史丢消息；压缩阈值判断也会基于不一致的快照。

**建议修复**：对每个 `conversationId`（或 sessionId）加 `ReentrantLock` / `synchronized`，或用 Redis Lua 把"追加消息"做成原子操作。

---

### 🟠 Bug 4：`tool_call_id` 可能为 null，第二次调用 messages 不合法

**位置**：`DeepSeekClient.processToolChunk` → `onToolCallId`

**问题**：
- 流式 API 中 `id` 通常只在首个 tool_call chunk 出现。代码用 `if (!id.isEmpty()) onToolCallId.accept(id)`，若首个 chunk 的 id 字段为空（某些模型/代理行为），`toolCallId` 保持 `null`。
- `executeToolAndRespond` 用 `toolCallId` 构造 `assistant.tool_calls[0].id` 和 `tool.tool_call_id`，两者为 null 会被 OpenAI 兼容 API 拒绝（400 错误）。

**建议修复**：id 缺失时生成一个本地占位 id（如 `call_<uuid>`），保证 messages 结构合法。

---

### 🟠 Bug 5：压缩摘要以 `role=system` 存入历史，污染 Agentic 消息序列

**位置**：`ConversationCompressionService.doCompress` / `ChatHandler.buildMessagesForAgenticRAG`

**问题**：
- 摘要存为 `{"role":"system","content":"[历史摘要] ..."}`，插在历史列表中间。
- `buildMessagesForAgenticRAG` 透传所有历史（含这条 system），于是发送给 LLM 的消息变成：`system(rules) → history... → system(摘要) → ... → user`。
- 多个 system 消息夹在中间，语义混乱，可能干扰 LLM 的工具决策与回答风格。

**建议修复**：摘要用 `role=system` 但在构建 messages 时提取到顶部与 rules 合并；或改存为 `role=assistant`/`user` 带 marker；或单独放 `system` 位置但保证只有一个 system 块。

---

### 🟡 Bug 6：`buildContext` 无总长度上限，可能撑爆上下文

**位置**：`ChatHandler.buildContext`

**问题**：固定取 10 条结果，每条 chunk 可达 512 字符 + 文件名，无总 token 预算控制。文档密集时 tool result 可能数千~上万字符，叠加历史与 system，可能超模型窗口或显著抬费。

**建议修复**：按 token 预算截断（如 tool result 上限 3000 token），或按 rerank 分数动态裁剪。

---

### 🟡 Bug 7：第一次 LLM 调用失败时，用户消息不入历史

**位置**：`processMessage` 的 `onError` 回调

**问题**：`onError` 只 `handleError` + `sendCompletionNotification`，不调用 `updateConversationHistory`。用户这条提问在系统中"消失"，下一轮 LLM 看不到。第二次调用失败同理。

**建议修复**：失败时至少把 user message + 一条 error assistant message 存入历史，保持上下文连续。

---

### 🟡 Bug 8：`rerank` 的 `topN` 等于 `topK`，精排价值被削弱

**位置**：`HybridSearchService.applyRerank` → `rerankerClient.rerank(query, documents, topK)`

**问题**：RRF 已返回 `topK`(=10) 条，rerank 只在这 10 条里重排。正确做法是 RRF 召回更多候选（如 50），rerank 后再取 topK，让精排真正发挥"从大池子里挑最相关"的作用。

**建议修复**：RRF 阶段 `limit(topK * 5)`，rerank `topN = topK`，rerank 后截断到 topK。

---

## 三、可扩展点（Agentic 能力升级方向）

当前实现本质是 **"单次决策 + 单次检索"**，离真正的 Agentic（多步推理、多工具编排、自我反思）还有距离。建议按优先级扩展：

### 1. 多轮工具调用循环（Agentic Loop）⭐ 最高优先
- 当前第二次调用不带 tools，LLM 无法"搜索→不够→换 query 再搜"。
- 改为：循环携带 tools，直到 LLM 不再调用工具或达到 `max_iterations`（如 3~5）。
- 需防止死循环与 token 失控（每轮累计预算）。

### 2. 查询改写 / HyDE
- 用户问题常带指代（"刚才那个怎么用"）、口语化，不适合直接做检索 query。
- 加一步 LLM 把问题改写为独立检索 query；或 HyDE（先让 LLM 假设性回答，用回答做向量检索）。
- 当前 `parseSearchQuery` 回退到 userMessage 是最低保障，不是改写。

### 3. 子问题分解
- 复杂问题拆成 N 个子问题，并行检索，结果合并去重。
- 适合"对比型""多跳推理型"问题。

### 4. Self-Reflection / Self-RAG
- 回答生成后让 LLM 自评：是否基于文档？是否答非所问？不足则重新检索或重写。
- 可显著降低幻觉。

### 5. 多工具编排
- 当前只有 `search_knowledge_base`。可加：
  - `list_documents`（列出用户可见文档）
  - `search_with_filter`（按时间/作者/orgTag 过滤）
  - `calculator`（数学计算）
  - `web_search`（联网，可选）
- 真正的 Agentic 应让 LLM 在多工具间自主编排。

### 6. 引用溯源（Citation）精细化
- `SearchResult` 已有 `fileMd5 + chunkId`，可返回结构化引用，前端高亮原文片段。
- 当前只让 LLM 在文本里写"(来源: 文件名)"，粒度粗且易漏标。

### 7. 流式工具状态反馈
- LLM 决定搜索时，前端只看到输出暂停。应发 `{"type":"tool_start","tool":"search_knowledge_base","query":"..."}` 事件，前端显示"🔍 正在搜索：xxx"，体验更好。

### 8. 检索结果缓存
- 相同/相似 query 的结果缓存到 Redis（带 TTL），减少 ES + Embedding 调用。

### 9. 评估系统接入（TODO 已列）
- RAGAS 离线评估 faithfulness / answer_relevancy / context_precision，形成闭环。

### 10. topK / 召回参数可配置化
- `topK=10`、`recallK=topK*30` 硬编码，应抽到 `AiProperties`，支持按问题类型动态调整。

---

## 四、优先级建议

| 优先级 | 事项 | 类型 |
|--------|------|------|
| P0 | Bug 1 重复输出/历史污染 | bug |
| P0 | Bug 2 停止按钮无法真正中断 | bug |
| P0 | Bug 3 并发历史非原子 | bug |
| P1 | Bug 4 tool_call_id null | bug |
| P1 | Bug 5 摘要 system 污染 | bug |
| P1 | 扩展点 1：多轮工具调用循环 | 能力 |
| P1 | 扩展点 2：查询改写 | 能力 |
| P2 | Bug 6/7/8 | bug |
| P2 | 扩展点 3/4/5：子问题、反思、多工具 | 能力 |
| P3 | 扩展点 6~10：引用、状态、缓存、评估、配置化 | 体验/工程 |

---

## 五、备注（文档与实现不一致）

- `README.md` 写"软阈值 30 条消息触发异步压缩"，但 `CompressionProperties.softThresholdToken=20000`（按 token），且 `checkAndCompress` 已改为纯 token 维度（代码注释明确说"条数维度冗余"）。**README 描述过时**，建议同步更新。
- `application.yml` 中 `ai.compression` 未显式配 `soft-threshold-token`，用的是默认 20000；README 写的 30 条已不适用。
