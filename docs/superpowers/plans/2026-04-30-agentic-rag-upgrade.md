# Agentic RAG Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hardcoded search-on-every-message RAG pipeline with GLM-5 Function Calling, letting the LLM decide whether to search the knowledge base.

**Architecture:** The backend sends a `tools` parameter to the GLM-5 API on the first streaming call. If the LLM emits `delta.content`, it's forwarded to the frontend immediately (streaming preserved). If the LLM emits `delta.tool_calls`, arguments are accumulated, the search is executed, and a second streaming call generates the final answer with search results. Frontend protocol unchanged.

**Tech Stack:** Spring Boot, WebClient (reactive streaming), GLM-5 API (OpenAI-compatible with `tools`/`tool_calls`), Jackson for JSON parsing.

**Spec:** `docs/superpowers/specs/2026-04-30-agentic-rag-upgrade-design.md`

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/java/com/yizhaoqi/smartpai/client/DeepSeekClient.java` | Modify | Add `streamWithTools()` method + tool-call delta parsing |
| `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java` | Modify | Rewrite `processMessage()` for two-phase tool-calling flow |
| `src/main/java/com/yizhaoqi/smartpai/config/AiProperties.java` | No change | `refStart/refEnd/noResultText` fields become unused but harmless to keep |
| `src/main/resources/application.yml` | Modify | Update system prompt to reference tool usage instead of `<<REF>>` markers |

---

### Task 1: Add `streamWithTools()` to DeepSeekClient

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/client/DeepSeekClient.java`

This task adds the new streaming method that handles both content and tool_call deltas from the GLM-5 API. The existing `streamResponse()` method is preserved for the second LLM call (which doesn't need tools).

- [ ] **Step 1: Add new imports to DeepSeekClient.java**

Add these imports after the existing ones (after line 11):

```java
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashMap;
```

- [ ] **Step 2: Add `streamWithTools()` method**

Add this method after the existing `streamResponse()` method (after line 67):

```java
/**
 * 带工具的流式调用。处理两种 SSE delta：
 * - delta.content → 直接回答，通过 onContentDelta 转发
 * - delta.tool_calls → 工具调用，累积参数通过 onToolCallId 和 onToolCallArgs 回调
 */
public void streamWithTools(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        Consumer<String> onContentDelta,
        Consumer<String> onToolCallId,
        Consumer<String> onToolCallArgs,
        Consumer<Throwable> onError,
        Runnable onComplete) {

    Map<String, Object> request = buildToolsRequest(messages, tools);

    webClient.post()
            .uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(String.class)
            .subscribe(
                chunk -> processToolChunk(chunk, onContentDelta, onToolCallId, onToolCallArgs),
                onError,
                onComplete
            );
}
```

- [ ] **Step 3: Add `streamResponse()` overload that accepts `List<Map<String, Object>>`**

Add this method after `streamWithTools()`:

```java
/**
 * 流式调用（消息列表形式），用于第二次调用（带 tool 结果消息）。
 */
public void streamResponse(List<Map<String, Object>> messages,
                           Consumer<String> onChunk,
                           Consumer<Throwable> onError,
                           Runnable onComplete) {

    Map<String, Object> request = new HashMap<>();
    request.put("model", model);
    request.put("messages", messages);
    request.put("stream", true);
    AiProperties.Generation gen = aiProperties.getGeneration();
    if (gen.getTemperature() != null) request.put("temperature", gen.getTemperature());
    if (gen.getTopP() != null) request.put("top_p", gen.getTopP());
    if (gen.getMaxTokens() != null) request.put("max_tokens", gen.getMaxTokens());

    webClient.post()
            .uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(request)
            .retrieve()
            .bodyToFlux(String.class)
            .subscribe(
                chunk -> processChunk(chunk, onChunk),
                onError,
                onComplete
            );
}
```

- [ ] **Step 4: Add `buildToolsRequest()` method**

Add after `buildRequest()` method (after line 93):

```java
private Map<String, Object> buildToolsRequest(List<Map<String, Object>> messages,
                                               List<Map<String, Object>> tools) {
    logger.info("构建工具请求，消息数: {}, 工具数: {}", messages.size(), tools.size());

    Map<String, Object> request = new HashMap<>();
    request.put("model", model);
    request.put("messages", messages);
    request.put("stream", true);
    request.put("tools", tools);

    AiProperties.Generation gen = aiProperties.getGeneration();
    if (gen.getTemperature() != null) request.put("temperature", gen.getTemperature());
    if (gen.getTopP() != null) request.put("top_p", gen.getTopP());
    if (gen.getMaxTokens() != null) request.put("max_tokens", gen.getMaxTokens());
    return request;
}
```

- [ ] **Step 5: Add `processToolChunk()` method**

Add after `processChunk()` method (after line 165):

```java
/**
 * 处理带工具的流式响应块。
 * SSE delta 可能包含 delta.content（直接回答）或 delta.tool_calls（工具调用参数片段）。
 */
private void processToolChunk(String chunk, Consumer<String> onContentDelta,
                              Consumer<String> onToolCallId, Consumer<String> onToolCallArgs) {
    try {
        if ("[DONE]".equals(chunk)) {
            logger.debug("工具流式对话结束");
            return;
        }

        JsonNode node = objectMapper.readTree(chunk);
        JsonNode delta = node.path("choices").path(0).path("delta");

        // 1. 处理文本内容（LLM 直接回答）
        String content = delta.path("content").asText("");
        if (!content.isEmpty()) {
            onContentDelta.accept(content);
        }

        // 2. 处理工具调用（参数分片到达）
        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray() && !toolCalls.isEmpty()) {
            JsonNode tc = toolCalls.get(0); // 只处理第一个工具调用
            JsonNode function = tc.path("function");

            // 首个 delta 包含 id 和 name
            String id = tc.path("id").asText("");
            if (!id.isEmpty()) {
                onToolCallId.accept(id);
            }

            // 所有 delta 都可能包含 arguments 片段
            String args = function.path("arguments").asText("");
            if (!args.isEmpty()) {
                onToolCallArgs.accept(args);
            }
        }
    } catch (Exception e) {
        logger.error("处理工具数据块时出错: {}", e.getMessage(), e);
    }
}
```

- [ ] **Step 6: Verify compilation**

Run: `mvn -f E:/Curzsu/ShadowRAG/pom.xml compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/client/DeepSeekClient.java
git commit -m "feat: DeepSeekClient 增加 streamWithTools 支持 Function Calling"
```

---

### Task 2: Rewrite ChatHandler for Agentic RAG

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`

This is the core change. Replace the unconditional search + single LLM call with the two-phase tool-calling flow.

- [ ] **Step 1: Add new imports to ChatHandler.java**

Add after line 21 (`import java.util.concurrent.ConcurrentHashMap;`):

```java
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashMap;
```

- [ ] **Step 2: Add `SEARCH_TOOL` constant**

Add this constant field after the `stopFlags` field (after line 40):

```java
/**
 * 搜索知识库工具定义（OpenAI Function Calling 格式）
 */
private static final List<Map<String, Object>> SEARCH_TOOL = List.of(
    Map.of(
        "type", "function",
        "function", Map.of(
            "name", "search_knowledge_base",
            "description", "搜索知识库文档。当用户问题涉及已上传的文档、文件、知识库内容时调用此工具获取相关信息。对于通用知识、闲聊、数学计算等不需要搜索。",
            "parameters", Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of(
                        "type", "string",
                        "description", "搜索查询语句，用于在知识库中检索相关文档内容"
                    )
                ),
                "required", List.of("query")
            )
        )
    )
);
```

- [ ] **Step 3: Rewrite `processMessage()` method**

Replace the entire `processMessage()` method (lines 54-103) with:

```java
public void processMessage(String userId, String userMessage, WebSocketSession session) {
    logger.info("开始处理消息（Agentic RAG），用户ID: {}, 会话ID: {}", userId, session.getId());
    try {
        // 1. 获取或创建会话 ID
        String conversationId = getOrCreateConversationId(userId);

        // 2. 获取对话历史
        List<Map<String, String>> history = getConversationHistory(conversationId);
        logger.debug("获取到 {} 条历史对话", history.size());

        // 3. 构建 messages（不含 context，让 LLM 决定是否搜索）
        List<Map<String, Object>> messages = buildMessagesForAgenticRAG(history, userMessage);

        // 4. 累积器
        StringBuilder responseBuilder = new StringBuilder();
        StringBuilder toolCallArgs = new StringBuilder();
        AtomicReference<String> toolCallId = new AtomicReference<>(null);
        AtomicBoolean toolCallDetected = new AtomicBoolean(false);

        // 5. 第一次 LLM 调用（流式，带 tools）
        logger.info("发起第一次 LLM 调用（带搜索工具）");
        deepSeekClient.streamWithTools(
            messages,
            SEARCH_TOOL,
            // delta.content → 直接转发前端
            chunk -> {
                responseBuilder.append(chunk);
                sendResponseChunk(session, chunk);
            },
            // tool_call.id → 记录
            id -> {
                toolCallId.set(id);
                logger.info("LLM 决定调用搜索工具, toolCallId: {}", id);
            },
            // delta.tool_calls.args → 累积
            args -> {
                toolCallDetected.set(true);
                toolCallArgs.append(args);
            },
            // onError
            error -> {
                logger.error("LLM 流式响应错误: {}", error.getMessage(), error);
                handleError(session, error);
                sendCompletionNotification(session);
            },
            // onComplete → 判断是否需要第二次调用
            () -> {
                if (toolCallDetected.get()) {
                    logger.info("LLM 调用了搜索工具，执行搜索并生成第二次调用");
                    executeToolAndRespond(
                        userId, messages, toolCallId.get(), toolCallArgs.toString(),
                        session, conversationId, userMessage, responseBuilder
                    );
                } else {
                    // LLM 直接回答了（未调用搜索）
                    logger.info("LLM 直接回答，未调用搜索工具");
                    sendCompletionNotification(session);
                    updateConversationHistory(conversationId, userId, userMessage, responseBuilder.toString());
                }
            }
        );

    } catch (Exception e) {
        logger.error("处理消息错误: {}", e.getMessage(), e);
        handleError(session, e);
    }
}
```

- [ ] **Step 4: Add `buildMessagesForAgenticRAG()` method**

Add after `processMessage()`:

```java
/**
 * 构建 Agentic RAG 的消息列表（不含搜索 context，让 LLM 通过工具获取）
 */
private List<Map<String, Object>> buildMessagesForAgenticRAG(
        List<Map<String, String>> history, String userMessage) {
    List<Map<String, Object>> messages = new ArrayList<>();

    // 1. System 消息（只含规则，不含 <<REF>> 参考信息）
    AiProperties.Prompt promptCfg = aiProperties.getPrompt();
    String systemContent = promptCfg.getRules() != null ? promptCfg.getRules() : "";
    messages.add(Map.of("role", "system", "content", systemContent));

    // 2. 历史消息
    if (history != null && !history.isEmpty()) {
        // 历史 messages 的 role/content 是 String，需要转为 Object 类型
        List<Map<String, Object>> typedHistory = new ArrayList<>();
        for (Map<String, String> msg : history) {
            Map<String, Object> typedMsg = new LinkedHashMap<>();
            typedMsg.put("role", msg.get("role"));
            typedMsg.put("content", msg.get("content"));
            typedHistory.add(typedMsg);
        }
        messages.addAll(typedHistory);
    }

    // 3. 当前用户问题
    messages.add(Map.of("role", "user", "content", userMessage));

    return messages;
}
```

Note: This method needs `aiProperties` injected. Add this field after the existing `objectMapper` field (after line 37):

```java
private final AiProperties aiProperties;
```

And add `AiProperties aiProperties` parameter to the constructor (after `ObjectMapper objectMapper` parameter on line 46), and add this line in the constructor body:

```java
this.aiProperties = aiProperties;
```

Also add the import at the top:

```java
import com.yizhaoqi.smartpai.config.AiProperties;
```

- [ ] **Step 5: Add `executeToolAndRespond()` method**

```java
/**
 * 执行工具调用（搜索），然后发起第二次 LLM 调用生成最终回答。
 */
private void executeToolAndRespond(
        String userId,
        List<Map<String, Object>> originalMessages,
        String toolCallId,
        String toolCallArgsJson,
        WebSocketSession session,
        String conversationId,
        String userMessage,
        StringBuilder responseBuilder) {

    try {
        // 1. 解析 LLM 构造的搜索 query
        String searchQuery = parseSearchQuery(toolCallArgsJson);
        logger.info("LLM 构造的搜索 query: {}", searchQuery);

        // 2. 执行混合搜索（复用现有 searchService）
        List<SearchResult> searchResults = searchService.searchWithPermission(searchQuery, userId, 10);
        logger.info("搜索完成，结果数: {}", searchResults.size());

        // 3. 构建搜索结果文本
        String searchContext = buildContext(searchResults);
        if (searchContext.isEmpty()) {
            searchContext = "（未找到相关文档）";
        }

        // 4. 构建第二次调用的 messages（追加 tool_call 和 tool result）
        List<Map<String, Object>> messagesWithTool = new ArrayList<>(originalMessages);

        // assistant 消息：记录 LLM 调用了哪个工具
        Map<String, Object> assistantToolCall = new LinkedHashMap<>();
        assistantToolCall.put("role", "assistant");
        assistantToolCall.put("tool_calls", List.of(Map.of(
            "id", toolCallId,
            "type", "function",
            "function", Map.of(
                "name", "search_knowledge_base",
                "arguments", toolCallArgsJson
            )
        )));
        messagesWithTool.add(assistantToolCall);

        // tool 消息：返回搜索结果
        Map<String, Object> toolResult = new LinkedHashMap<>();
        toolResult.put("role", "tool");
        toolResult.put("tool_call_id", toolCallId);
        toolResult.put("content", searchContext);
        messagesWithTool.add(toolResult);

        // 5. 第二次流式调用（不带 tools，LLM 基于搜索结果直接回答）
        logger.info("发起第二次 LLM 调用（带搜索结果）");
        deepSeekClient.streamResponse(
            messagesWithTool,
            // onChunk
            chunk -> {
                responseBuilder.append(chunk);
                sendResponseChunk(session, chunk);
            },
            // onError
            error -> {
                logger.error("第二次 LLM 调用错误: {}", error.getMessage(), error);
                handleError(session, error);
                sendCompletionNotification(session);
            },
            // onComplete
            () -> {
                String completeResponse = responseBuilder.toString();
                logger.info("Agentic RAG 响应完成，长度: {}", completeResponse.length());
                sendCompletionNotification(session);
                updateConversationHistory(conversationId, userId, userMessage, completeResponse);
            }
        );

    } catch (Exception e) {
        logger.error("工具执行错误: {}", e.getMessage(), e);
        handleError(session, e);
        sendCompletionNotification(session);
    }
}
```

- [ ] **Step 6: Add `parseSearchQuery()` method**

```java
/**
 * 从 tool_call arguments JSON 中提取 query 字段。
 * 如果解析失败，回退到使用原始用户消息作为搜索 query。
 */
private String parseSearchQuery(String argumentsJson) {
    try {
        JsonNode argsNode = objectMapper.readTree(argumentsJson);
        String query = argsNode.path("query").asText("");
        if (!query.isEmpty()) {
            return query;
        }
    } catch (Exception e) {
        logger.warn("解析 tool_call arguments 失败，将使用原始消息: {}", e.getMessage());
    }
    // Fallback: 返回 null，调用方应使用原始用户消息
    return null;
}
```

This method needs the Jackson `JsonNode` import. Add at the top:

```java
import com.fasterxml.jackson.databind.JsonNode;
```

Wait — `parseSearchQuery` is called inside `executeToolAndRespond` which already has `userMessage` available. Update the `executeToolAndRespond` to handle the fallback:

In `executeToolAndRespond`, change the search query logic:

```java
// 1. 解析 LLM 构造的搜索 query
String searchQuery = parseSearchQuery(toolCallArgsJson);
if (searchQuery == null || searchQuery.isEmpty()) {
    searchQuery = userMessage; // 回退到原始用户消息
}
logger.info("搜索 query: {}", searchQuery);
```

- [ ] **Step 7: Verify compilation**

Run: `mvn -f E:/Curzsu/ShadowRAG/pom.xml compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java
git commit -m "feat: ChatHandler 改为 Agentic RAG 两阶段工具调用流程"
```

---

### Task 3: Update system prompt in application.yml

**Files:**
- Modify: `src/main/resources/application.yml`

Remove the `<<REF>>` context injection pattern and update the system prompt to reference tool usage.

- [ ] **Step 1: Replace the `ai.prompt` section**

Replace the entire `ai:` block (lines 125-142) with:

```yaml
ai:
  prompt:
    rules: |
      你是 Brain.ai 知识助手。

      你可以使用 search_knowledge_base 工具搜索知识库文档。当用户问题涉及文档或知识库内容时，请调用该工具获取相关信息。

      回答规范：
      1. 用简体中文回答，先给结论再展开论述。
      2. 当你搜索了知识库并获得结果时，严格基于搜索结果作答，不得编造结果中未提及的内容；引用时标注来源，格式：(来源: 文件名)。
      3. 若用户的问题属于通用知识（如数学计算、常识问答、闲聊等），不需要搜索，直接用你的知识回答即可。
      4. 只有当问题明确涉及文档或知识库内容，但搜索结果不足以回答时，才告知"暂无相关信息"，并简要说明缺少哪方面信息。
      5. 回答长度应与问题复杂度匹配，简单问题简短作答，复杂问题再详细展开。
  generation:
    temperature: 0.3
    max-tokens: 4096
    top-p: 0.9
```

Note: `ref-start`, `ref-end`, `no-result-text` are removed. The `AiProperties.Prompt` class still has these fields but they'll be `null` — no runtime impact since `buildMessagesForAgenticRAG()` doesn't use them.

- [ ] **Step 2: Verify compilation**

Run: `mvn -f E:/Curzsu/ShadowRAG/pom.xml compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "feat: 系统提示词改为 Agentic RAG 模式，引用工具搜索替代硬编码注入"
```

---

### Task 4: Integration test

**Files:** None (manual testing)

Start the backend and frontend, test the full flow.

- [ ] **Step 1: Start backend**

Run: `mvn -f E:/Curzsu/ShadowRAG/pom.xml spring-boot:run`
Expected: Application starts on port 8081 without errors. Watch for `Started SmartPaiApplication` log.

- [ ] **Step 2: Test general knowledge question (no search)**

In the chat UI, send: "1+1等于几"

Expected behavior:
- LLM responds directly with "2" (or similar)
- Backend log shows: `发起第一次 LLM 调用（带搜索工具）` followed by `LLM 直接回答，未调用搜索工具`
- No `executeToolAndRespond` log
- No Elasticsearch/embedding calls logged

- [ ] **Step 3: Test casual greeting (no search)**

Send: "你好"

Expected behavior:
- LLM responds with a greeting
- Same log pattern as Step 2 (no search)

- [ ] **Step 4: Test knowledge base question (with search)**

Send: "东华OJ第100题怎么做"

Expected behavior:
- Backend log shows: `LLM 决定调用搜索工具, toolCallId: call_xxx`
- Backend log shows: `LLM 构造的搜索 query: ...`
- Backend log shows: `搜索完成，结果数: N`
- Backend log shows: `发起第二次 LLM 调用（带搜索结果）`
- Frontend displays answer with source citations like `(来源: 文件名)`

- [ ] **Step 5: Test multi-turn conversation**

After Step 4, send: "还有其他题目吗"

Expected behavior:
- Conversation history preserved
- LLM may or may not search (depending on context)
- Streaming works normally

- [ ] **Step 6: Test stop generation**

During a response, click stop.

Expected behavior:
- Response stops
- No errors in backend log

- [ ] **Step 7: Final commit if any fixes were needed**

```bash
git add -A
git commit -m "fix: Agentic RAG 集成测试修复"
```
