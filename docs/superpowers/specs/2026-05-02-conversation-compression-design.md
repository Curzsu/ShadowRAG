# Long Memory Async Compression Design

## Metadata

- Date: 2026-05-02
- Status: Approved
- Approach: Independent CompressionService (Approach A)

## Background

ChatHandler currently uses a hard cap of 20 messages (`history.subList`) to manage conversation context. This is a simple sliding window that discards the oldest messages entirely, losing all information from early conversation turns. There is no token-level estimation, no summarization, and no async processing.

This design introduces an async compression mechanism that compresses early conversation turns into a summary while preserving recent N rounds verbatim, reducing approximately 40% token consumption.

## Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Approach | Independent CompressionService | Single responsibility, decoupled from ChatHandler, easy to test |
| Summary LLM | Reuse existing DeepSeekClient | No extra dependencies, shared API quota |
| Trigger timing | Every message round check + async compress | Real-time, catches growth early |
| Threshold dimension | Hybrid: message count (soft) + token estimation (hard) | Fast check for soft, precise guard for hard |
| Redis atomicity | Lua script with cjson | Atomic head replacement, no concurrent overwrite |
| Thread pool rejection | DiscardPolicy | Never block Netty EventLoop threads |
| Hard threshold fallback | Sync truncation only (millisecond Redis op) | No LLM call on critical path |

## Architecture

### Module Structure

```
src/main/java/com/yizhaoqi/smartpai/
├── service/
│   └── ConversationCompressionService.java   # Core compression logic
├── config/
│   └── CompressionConfig.java                # Thread pool + threshold config

src/main/resources/scripts/
└── compress_and_replace.lua                  # Atomic Redis replacement script
```

No new entities or repositories. Reuses existing data structures.

### Call Flow

```
ChatHandler.updateConversationHistory()
  |
  +-- 1. Append messages to Redis history (existing logic)
  +-- 2. compressionService.checkAndCompress(conversationId, history)
  |      |
  |      +-- Below soft threshold -> return (no-op)
  |      +-- Above soft, below hard -> submit async compression (deduplicated)
  |      |     +-- On completion: update Redis via Lua -> syncToMySQL
  |      +-- Above hard threshold -> sync truncate (millisecond Redis op)
  |           +-- No async compression triggered after sync truncate
  |
  +-- 3. syncToMySQL() (existing, unaffected)
```

ChatHandler changes: one line insertion in `updateConversationHistory()`, remove the existing 20-message hard cap.

## Core Mechanisms

### Deduplication via ConcurrentHashMap

```java
private final ConcurrentHashMap<String, CompletableFuture<Void>> activeTasks = new ConcurrentHashMap<>();
```

- Key = `conversationId`, Value = running compression task Future
- `putIfAbsent` on submit: skip if task already running for this conversation
- `remove` on completion (success or max retries exhausted)
- Failed retries retain the entry until max retries reached, preventing concurrent retry storms

### Thread Pool Configuration

```yaml
ai:
  compression:
    thread-pool:
      core-size: 2
      max-size: 4
      queue-capacity: 50
      thread-name-prefix: "compression-"
```

Rejection policy: **DiscardPolicy**. When the pool is saturated, compression tasks are silently discarded. This is critical because:

1. ChatHandler runs on Netty EventLoop threads. `CallerRunsPolicy` would block an EventLoop, potentially freezing hundreds of connections.
2. Compression is an optimization, not a requirement. The hard threshold sync truncation is the safety net.
3. The next message will re-check thresholds and retry submission.

### Soft/Hard Dual Thresholds

```yaml
ai:
  compression:
    soft-threshold: 30            # Message count
    hard-threshold-token: 50000   # Token estimation
    keep-rounds: 6                # Preserve recent 6 rounds (12 messages)
```

**Soft threshold (message count)**: Fast check, no computation needed. Triggers async compression.

**Hard threshold (token estimation)**: Conservative estimate using `content.length() / 2` per message. Triggers synchronous truncation as emergency brake.

```java
private int estimateTokens(List<Map<String, String>> history) {
    return history.stream()
        .mapToInt(m -> m.getOrDefault("content", "").length() / 2)
        .sum();
}
```

### Redis Atomic Replacement via Lua Script

The conversation history is stored as a JSON string in Redis (`GET/SET` on `conversation:{id}`). Async compression cannot simply overwrite the entire string because new messages may have been appended during the LLM call.

Solution: A Lua script that atomically replaces only the head portion:

```lua
-- KEYS[1] = conversation:{conversationId}
-- ARGV[1] = splitIndex (number of head messages to remove)
-- ARGV[2] = summary message JSON string
local key = KEYS[1]
local splitIndex = tonumber(ARGV[1])
local summaryJson = ARGV[2]

local current = redis.call('GET', key)
if not current then return 0 end

local messages = cjson.decode(current)
if #messages < splitIndex then return 0 end

local newMessages = { cjson.decode(summaryJson) }
for i = splitIndex + 1, #messages do
    newMessages[#newMessages + 1] = messages[i]
end

redis.call('SET', key, cjson.encode(newMessages))
return #newMessages
```

The `splitIndex` is captured at task submission time (`history.size() - keepRounds * 2`). Regardless of how many messages were appended during compression, the script only replaces the head `[0..splitIndex-1]` and preserves everything after.

### Async Compression Flow

```
1. Read latest history from Redis (may have grown since submission)
2. Split:
   - head = history[0 .. splitIndex-1]       <- to be compressed
   - tail = history[splitIndex .. end]        <- preserved verbatim
3. If head is empty or has only 1 message -> no compression needed, exit
4. Call LLM for summary:
   - Reuse DeepSeekClient's WebClient and API config
   - Use WebClient.block() (runs in compression thread pool, NOT Netty EventLoop)
   - Prompt: summary-prompt from config + concatenated head messages
   - Model params: temperature=0.1
5. Construct summary message:
   - {"role": "system", "content": "[历史摘要] xxx", "timestamp": "now"}
6. Execute Lua script: replace head with summary, preserve tail
7. Call conversationService.syncToMySQL() to persist compressed state
8. Remove from activeTasks
```

### LLM Call for Summary

A dedicated non-streaming method in `ConversationCompressionService`:

```java
private String callLlmForSummary(String conversationText) {
    // Reuse DeepSeekClient's WebClient configuration
    // POST to /chat/completions with:
    //   model: same as main chat
    //   messages: [{ role: "user", content: summaryPrompt + conversationText }]
    //   temperature: 0.1
    //   max_tokens: 1024
    // Use .block() - safe because this runs in the compression thread pool
}
```

### Retry Mechanism

```java
int retryCount = 0;
while (retryCount <= retryMax) {
    try {
        // Execute compression...
        break;
    } catch (Exception e) {
        retryCount++;
        if (retryCount > retryMax) {
            log.error("Compression failed after max retries, conversationId={}", conversationId, e);
            break;
        }
        Thread.sleep(1000 * retryCount);  // Linear backoff
    }
}
```

After max retries exhausted:
- Remove from `activeTasks` (allows future submission)
- No sync truncation (we're below hard threshold, just compression failed)
- Next message will re-check and resubmit if still above soft threshold

### Sync Truncation (Hard Threshold Fallback)

```java
private void syncTruncate(String conversationId, List<Map<String, String>> history) {
    // Keep only the last keepRounds * 2 messages
    // Use a Lua script for atomicity (same pattern, simpler - just LTRIM equivalent)
    // Millisecond operation, safe to run on Netty thread
}
```

This is a pure Redis operation. No LLM call. Execution time is in single-digit milliseconds.

### Exception Isolation

`checkAndCompress()` wraps all internal logic in try-catch. Exceptions never propagate to ChatHandler. The `syncToMySQL()` call after `checkAndCompress()` always executes regardless of compression outcome.

## Redis/MySQL Consistency

The current `syncToMySQL()` in ChatHandler writes the uncompressed history to MySQL. The async compression task handles its own persistence:

1. Task completes -> Lua script updates Redis
2. Task calls `conversationService.syncToMySQL()` -> writes compressed state to MySQL

This means MySQL may briefly contain uncompressed data between the ChatHandler sync and the compression task sync. This is acceptable because:

- The data is not lost, just not yet compressed
- The next compression will overwrite with compressed version
- On conversation reload, MySQL data is loaded into Redis, and the next message check will trigger compression if still above threshold

## Integration Points

### File Changes Summary

| File | Operation | Estimated Lines |
|---|---|---|
| `application.yml` | Append config section | ~15 |
| `CompressionConfig.java` | New file | ~60 |
| `ConversationCompressionService.java` | New file | ~180 |
| `ChatHandler.java` | Insert 1 line, remove 2 lines | ~3 |
| `compress_and_replace.lua` | New file | ~15 |

Total: ~270 lines. Core logic concentrated in `ConversationCompressionService`.

### ChatHandler.java Change

In `updateConversationHistory()`, after appending messages, before `syncToMySQL`:

```java
// REMOVE: if (history.size() > 20) { history = history.subList(history.size() - 20, history.size()); }

// ADD:
conversationCompressionService.checkAndCompress(conversationId, history);

// Existing: conversationService.syncToMySQL(conversationId, userId);
```

### CompressionConfig.java

```java
@Configuration
@ConfigurationProperties(prefix = "ai.compression")
public class CompressionConfig {
    private int softThreshold;
    private int hardThresholdToken;
    private int keepRounds;
    private int retryMax;
    private String summaryPrompt;
    private ThreadPoolConfig threadPool;

    @Bean
    public ThreadPoolTaskExecutor compressionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threadPool.getCoreSize());
        executor.setMaxPoolSize(threadPool.getMaxSize());
        executor.setQueueCapacity(threadPool.getQueueCapacity());
        executor.setThreadNamePrefix(threadPool.getThreadNamePrefix());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
```

### ConversationCompressionService Method List

```java
@Service
public class ConversationCompressionService {
    // Dependencies
    private final ThreadPoolTaskExecutor compressionExecutor;
    private final ConversationService conversationService;
    private final CompressionConfig config;
    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> compressScript;

    // Deduplication
    private final ConcurrentHashMap<String, CompletableFuture<Void>> activeTasks;

    // Public
    public void checkAndCompress(String conversationId, List<Map<String, String>> history);

    // Internal
    private int estimateTokens(List<Map<String, String>> history);
    private void syncTruncate(String conversationId, int keepCount);
    private void submitAsyncCompression(String conversationId, int splitIndex);
    private void executeCompression(String conversationId, int splitIndex);
    private String callLlmForSummary(String conversationText);
    private void applyCompressionViaLua(String conversationId, int splitIndex, String summary);
}
```

## Safety Summary

| Risk | Mitigation |
|---|---|
| Redis concurrent overwrite | Lua script atomic head replacement |
| Netty EventLoop blocking | DiscardPolicy, never runs LLM calls on EventLoop |
| Conversation data loss | Hard threshold sync truncation (millisecond fallback) |
| Compression task pile-up | ConcurrentHashMap dedup + DiscardPolicy |
| Redis/MySQL inconsistency | Async task self-persists after compression |
| Compression LLM failure | Retry with linear backoff, graceful degradation |
| Exception propagation | checkAndCompress fully try-caught internally |
