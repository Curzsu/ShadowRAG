# Agentic RAG Upgrade Design

## Problem

The current RAG pipeline executes a hybrid search (KNN vector + BM25 text with RRF fusion) on **every** user message, regardless of whether the message requires knowledge base retrieval. For simple queries like "你好" or general knowledge questions, this wastes:
- 1 embedding API call (bge-m3, 1024-dim)
- 1 Elasticsearch hybrid query
- Input tokens for injecting search results into the system prompt

The system prompt already instructs the LLM to handle general knowledge without references, but the search happens unconditionally before the LLM ever sees the message.

## Solution

Upgrade to **Agentic RAG** using GLM-5's Function Calling (OpenAI-compatible `tools` API). The LLM decides whether to search the knowledge base by choosing to call the `search_knowledge_base` tool. Search becomes a tool the LLM can use, not a hardcoded pipeline step.

### LLM Backend

GLM-5 (Zhipu AI) via `https://open.bigmodel.cn/api/coding/paas/v4`. The API is OpenAI-compatible and supports the `tools` parameter and `tool_calls` streaming response.

### Tool Scope

One tool only: `search_knowledge_base`. No MCP layer.

### Frontend Impact

None. The WebSocket protocol (`{"chunk":"..."}` + `{"type":"completion",...}`) remains unchanged. All tool-calling orchestration happens on the backend.

## Architecture

### Flow

```
User message
  |
  v
[1st LLM call] (streaming, with tools parameter, NO context injection)
  |
  +-- LLM emits delta.content --> forward to frontend immediately (streaming UX preserved)
  |
  +-- LLM emits delta.tool_calls --> accumulate args (nothing sent to frontend yet)
       |
       v
  [Stream ends]
       |
       +-- No tool_calls: response was already streamed. Send completion.
       |
       +-- Has tool_calls: parse query --> execute search --> [2nd LLM call]
                                                          (streaming, with tool result)
                                                               |
                                                               v
                                                         Stream to frontend
                                                         Send completion
```

Key insight: when the LLM decides to call a tool, streaming deltas contain `tool_calls` (function name + arguments fragments), **not** `content`. So we never accidentally forward tool-call fragments to the frontend.

### History Strategy

Only the final user and assistant messages are persisted to conversation history. Tool-call intermediaries (assistant tool_calls message + tool result message) are **not** stored. This keeps the Redis/MySQL history format (`List<Map<String, String>>`) unchanged and avoids downstream changes.

## File Changes

### 1. `DeepSeekClient.java`

**Current**: Pure chat completion client with `streamResponse(userMessage, context, history, ...)`. Injects search context into system prompt via `<<REF>>...<<END>>` markers.

**Changes**:
- Add `streamWithTools(messages, tools, onContentDelta, onToolCallArgs, onToolCallId, onError, onComplete)` method
- Streaming delta parsing splits into two branches:
  - `delta.content` present -> forward via `onContentDelta` callback
  - `delta.tool_calls` present -> accumulate arguments via `onToolCallArgs` callback, capture ID via `onToolCallId`
- `tool_calls` arguments arrive as fragments across multiple SSE deltas; accumulation by `tool_calls[index]` is required
- Add `buildToolsRequest(messages, tools)` method that includes `tools` in the API request body
- Modify `streamResponse` to also accept `List<Map<String, Object>>` messages (for the second call with tool results)
- Keep existing `streamResponse(userMessage, context, history, ...)` signature for backward compatibility if needed, but primary flow will use the new signatures

**Estimated lines**: ~80 new/modified

### 2. `ChatHandler.java`

**Current**: `processMessage()` unconditionally calls `searchService.searchWithPermission()`, then passes context to `deepSeekClient.streamResponse()`.

**Changes to `processMessage()`**:
```
1. Get/create conversation ID (unchanged)
2. Get conversation history (unchanged)
3. Build messages WITHOUT context (system prompt + history + user message)
4. Define search tool
5. First LLM call via deepSeekClient.streamWithTools():
   - onContentDelta: append to responseBuilder, sendResponseChunk()
   - onToolCallArgs: accumulate
   - onToolCallId: capture
   - onComplete:
     - If tool_call detected: call executeToolAndRespond()
     - If no tool_call: sendCompletionNotification(), updateConversationHistory()
```

**New method `executeToolAndRespond()`**:
```
1. Parse search query from accumulated tool_call arguments JSON
2. Execute searchService.searchWithPermission(query, userId, 10)
3. Build context string from results
4. Construct messages with tool result:
   - Original messages (system + history + user)
   - Assistant message with tool_calls
   - Tool message with search results
5. Second LLM call via deepSeekClient.streamResponse(messages, ...)
   - Stream chunks to frontend
   - On complete: sendCompletionNotification(), updateConversationHistory()
```

**New method `buildSearchTool()`**: Returns the tool definition as a `List<Map<String, Object>>`:
```json
{
  "type": "function",
  "function": {
    "name": "search_knowledge_base",
    "description": "Search knowledge base documents. Call this tool when the user's question involves uploaded documents, files, or knowledge base content. Not needed for general knowledge, casual chat, or math calculations.",
    "parameters": {
      "type": "object",
      "properties": {
        "query": {
          "type": "string",
          "description": "Search query for retrieving relevant document content from the knowledge base"
        }
      },
      "required": ["query"]
    }
  }
}
```

**New method `buildMessagesForTools(history, userMessage)`**: Builds the message list for the first LLM call (system prompt without `<<REF>>` markers + history + user message).

**Estimated lines**: ~100 new/modified

### 3. `application.yml`

**Current system prompt** references `<<REF>>...<<END>>` markers and instructs how to use injected reference information.

**Changes**:
- Remove `ref-start`, `ref-end`, `no-result-text` config keys (no longer needed)
- Update `rules` prompt to reference tool usage instead of injected context:

```yaml
ai:
  prompt:
    rules: |
      You are Brain.ai knowledge assistant.

      You can use the search_knowledge_base tool to search knowledge base documents. When the user's question involves documents or knowledge base content, call this tool to retrieve relevant information.

      Response guidelines:
      1. Respond in Simplified Chinese. State conclusions first, then elaborate.
      2. When you have searched the knowledge base and obtained results, answer strictly based on the search results. Do not fabricate content not mentioned in the results. Cite sources as: (source: filename).
      3. For general knowledge questions (math, common sense, casual chat), do not search. Answer directly from your knowledge.
      4. Only when the question clearly involves document/knowledge base content but search results are insufficient, inform the user that relevant information is not available, and briefly explain what information is missing.
      5. Match response length to question complexity. Brief for simple questions, detailed for complex ones.
  generation:
    temperature: 0.3
    max-tokens: 4096
    top-p: 0.9
```

**Note**: The system prompt is written in English to avoid tokenization overhead in the LLM, but the LLM is instructed to respond in Simplified Chinese. This is a common optimization for bilingual models.

**Estimated lines**: ~15 modified

### Files NOT Changed

- `HybridSearchService.java` - search logic unchanged
- `EmbeddingClient.java` - embedding unchanged
- `ElasticsearchService.java` - indexing unchanged
- `ConversationService.java` - history storage unchanged
- `ChatWebSocketHandler.java` - WebSocket protocol unchanged
- All frontend files - zero changes

## Edge Cases

### LLM calls tool but search returns no results
The tool result message will contain "无搜索结果" or empty context. The LLM's system prompt instructs it to inform the user. Normal flow.

### LLM emits both content and tool_calls
Per OpenAI API spec, a response is either content OR tool_calls, never both. Defensive code should handle this gracefully: if any tool_call delta was seen, treat the entire response as a tool call (ignore any content deltas received before the tool_call).

### Tool call arguments parsing failure
If the accumulated arguments JSON cannot be parsed (malformed LLM output), fall back to using the original user message as the search query. Log a warning.

### Search service failure
If `searchWithPermission()` throws an exception, catch it and send the error as the tool result. The LLM will inform the user. Do not crash the conversation.

### Stop generation during tool execution
The existing `stopFlags` mechanism continues to work. If the user stops during the first LLM call, no second call is initiated. If stopped during the second call, the existing streaming stop logic applies.

## Testing Plan

1. **General knowledge question** (e.g., "1+1等于几"): Verify LLM responds directly without calling search. Confirm single API call, no embedding/ES overhead.
2. **Knowledge base question** (e.g., "东华OJ第100题怎么做"): Verify LLM calls search_knowledge_base, search executes, final response cites sources.
3. **Casual greeting** (e.g., "你好"): Verify direct response, no search.
4. **Ambiguous question**: Verify LLM makes reasonable decision on whether to search.
5. **Multi-turn conversation**: Verify history is preserved correctly across tool-calling turns.
6. **Stop generation**: Verify stop works in both direct-response and tool-call paths.
7. **Error scenarios**: Verify graceful handling when search service is unavailable.
