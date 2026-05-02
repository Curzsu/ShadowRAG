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
| Redis serialization | Use `StringRedisTemplate` for compression | Bypass `GenericJackson2JsonRedisSerializer` double-encoding |

## Architecture

### Module Structure

```
src/main/java/com/yizhaoqi/smartpai/
├── service/
│   └── ConversationCompressionService.java   # Core compression logic
├── config/
│   ├── CompressionProperties.java            # @ConfigurationProperties holder
│   └── CompressionConfig.java                # @Configuration with @Bean

src/main/resources/scripts/
├── compress_and_replace.lua                  # Async compression: replace head with summary
└── sync_truncate.lua                         # Hard threshold: truncate to tail only
```

No new entities or repositories. Reuses existing data structures.

### Call Flow

```
ChatHandler.updateConversationHistory()
  |
  +-- 1. Append messages, write FULL history to Redis (remove old 20-msg cap)
  +-- 2. conversationService.syncToMySQL() (sync first, before any compression)
  +-- 3. conversationCompressionService.checkAndCompress(conversationId, history)
         |
         +-- Below soft threshold -> return (no-op)
         +-- Above soft, below hard -> submit async compression (deduplicated)
         |     +-- On completion: update Redis via Lua -> syncToMySQL again
         +-- Above hard threshold -> sync truncate (millisecond Redis op)
              +-- No async compression triggered after sync truncate
```

**Key ordering change from initial draft**: `syncToMySQL` is called BEFORE `checkAndCompress`. This ensures:
1. The uncompressed state is always persisted first (data never lost).
2. The async compression task's `syncToMySQL` always writes AFTER ChatHandler's write, guaranteeing the compressed state is the final MySQL state.

### Redis Serialization Strategy

The existing `RedisConfig` configures `RedisTemplate<String, Object>` with `GenericJackson2JsonRedisSerializer` as the value serializer. ChatHandler pre-serializes history via `objectMapper.writeValueAsString()` and then calls `redisTemplate.opsForValue().set(key, json)`, resulting in double-encoded JSON in Redis.

The Lua script must operate on raw Redis bytes. Therefore:

- `ConversationCompressionService` injects Spring Boot's auto-configured `StringRedisTemplate` (which uses `StringRedisSerializer` for both key and value).
- All Lua script operations use `StringRedisTemplate` to read/write raw strings.
- The Lua script reads the raw value which is the JSON string that `objectMapper.writeValueAsString()` produced (possibly wrapped in quotes by `GenericJackson2JsonRedisSerializer`).
- **Verification step required during implementation**: Before writing the Lua script, the implementer must inspect the actual raw Redis value format using `redis-cli GET conversation:{id}` to determine whether the value is plain JSON `[{...}]` or double-encoded `"\"[{...}]"\""`. The Lua script and Java read/write paths must handle whichever format exists.

**Required prerequisite**: ChatHandler and ConversationService must be refactored to inject `StringRedisTemplate` instead of `RedisTemplate<String, Object>` for all conversation history operations. This eliminates the `GenericJackson2JsonRedisSerializer` double-encoding entirely. The Lua scripts assume clean JSON — they will NOT work with the current double-encoded format. This refactor is mandatory, not optional.

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
    soft-threshold: 30            # Message count
    hard-threshold-token: 50000   # Token estimation
    keep-rounds: 6                # Preserve recent 6 rounds (12 messages)
    retry-max: 3                  # Max async compression retries
    llm-timeout-seconds: 30       # Timeout for summary LLM call
    summary-prompt: |
      你是一个对话摘要助手。请将以下对话历史压缩为一段简洁的摘要，
      保留关键事实、决策和上下文信息，使后续对话能无缝衔接。
      用中文输出，不超过 500 字。
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

**Soft threshold (message count)**: Fast check, no computation needed. Triggers async compression.

**Hard threshold (token estimation)**: Conservative estimate using `content.length() / 2` per message. Triggers synchronous truncation as emergency brake.

```java
private int estimateTokens(List<Map<String, String>> history) {
    return history.stream()
        .mapToInt(m -> m.getOrDefault("content", "").length() / 2)
        .sum();
}
```

**Known limitation**: The `length() / 2` estimation is tuned for Chinese text (~1.5-2 chars per token). For English-heavy or code-heavy messages, this can underestimate by 2-4x. This is acceptable for v1 because the hard threshold has a large safety margin (50K tokens vs. typical model limits of 64K-128K). A future iteration could use a proper tokenizer.

### Redis Atomic Replacement via Lua Script

The conversation history is stored as a JSON string in Redis. Async compression cannot simply overwrite the entire string because new messages may have been appended during the LLM call.

**Script 1: `compress_and_replace.lua`** — Replaces head with summary:

```lua
-- KEYS[1] = conversation:{conversationId}
-- ARGV[1] = splitIndex (number of head messages to remove)
-- ARGV[2] = summary message JSON string
local key = KEYS[1]
local splitIndex = tonumber(ARGV[1])
local summaryJson = ARGV[2]

-- Validate summary JSON
local ok, summaryMsg = pcall(cjson.decode, summaryJson)
if not ok or type(summaryMsg) ~= "table" then return -1 end

local current = redis.call('GET', key)
if not current then return 0 end

local messages = cjson.decode(current)
if #messages < splitIndex then return 0 end

-- Preserve existing TTL
local ttl = redis.call('TTL', key)

-- Replace head with summary, keep tail intact
local newMessages = { summaryMsg }
for i = splitIndex + 1, #messages do
    newMessages[#newMessages + 1] = messages[i]
end

local encoded = cjson.encode(newMessages)
if ttl > 0 then
    redis.call('SET', key, encoded, 'EX', ttl)
else
    redis.call('SET', key, encoded)
end
return #newMessages
```

Key features:
- `pcall` guard on `cjson.decode(summaryJson)` prevents malformed LLM output from corrupting Redis state. Returns `-1` on parse failure (distinguishable from "key not found" which returns `0`).
- TTL preservation: reads current TTL and re-applies it via `EX` flag. Prevents key expiry reset.
- Atomicity: entire script executes as a single Redis operation.

**Script 2: `sync_truncate.lua`** — Hard threshold emergency truncation:

```lua
-- KEYS[1] = conversation:{conversationId}
-- ARGV[1] = keepCount (number of messages to keep from tail)
local key = KEYS[1]
local keepCount = tonumber(ARGV[1])

local current = redis.call('GET', key)
if not current then return 0 end

local messages = cjson.decode(current)
if #messages <= keepCount then return #messages end

-- Keep only the last keepCount messages
local trimmed = {}
for i = #messages - keepCount + 1, #messages do
    trimmed[#trimmed + 1] = messages[i]
end

local ttl = redis.call('TTL', key)
local encoded = cjson.encode(trimmed)
if ttl > 0 then
    redis.call('SET', key, encoded, 'EX', ttl)
else
    redis.call('SET', key, encoded)
end
return #trimmed
```

Both scripts must handle the actual raw Redis value format (see "Redis Serialization Strategy" section above).

### Async Compression Flow

```
1. Read latest history from Redis (may have grown since submission)
2. Recompute splitIndex based on CURRENT history size:
   - splitIndex = currentHistory.size() - keepRounds * 2
   - If splitIndex <= 0 -> compression no longer needed, exit
3. Split:
   - head = currentHistory[0 .. splitIndex-1]       <- to be compressed
   - tail = currentHistory[splitIndex .. end]         <- preserved verbatim
4. If head is empty or has only 1 message -> no compression needed, exit
5. Call LLM for summary (see LLM Call section below)
6. Construct summary message:
   - {"role": "system", "content": "[历史摘要] xxx", "timestamp": "now"}
7. Execute compress_and_replace.lua: replace head with summary, preserve tail
   - If Lua returns -1 (bad summary JSON) -> log error, do not retry with same input
   - If Lua returns 0 (key missing or too short) -> no-op
8. Call conversationService.syncToMySQL() to persist compressed state
9. Remove from activeTasks — **must execute in a `finally` block** to guarantee cleanup even if step 7 or 8 throws. Failure to remove would permanently block future compression for this conversation.
```

**Key change from initial draft**: `splitIndex` is recomputed from the **current** history size (step 2), not from the stale value captured at submission time. This prevents the "stale split" problem where messages that should have been compressed end up in the unmanaged middle zone between the summary and the preserved tail.

### LLM Call for Summary

A dedicated non-streaming method in `ConversationCompressionService`:

```java
private String callLlmForSummary(String conversationText) {
    // DeepSeekClient creates its own WebClient internally and has no getter.
    // Option A (preferred): Add a public callSync(String prompt) method to DeepSeekClient
    //   that wraps the WebClient POST + .block(Duration.ofSeconds(timeout)).
    //   This keeps API config (URL, key, model) centralized in one place.
    // Option B: Create a separate WebClient bean in CompressionConfig using
    //   the same ai.* properties. Duplicates config but decouples classes.

    // Using Option A:
    //   deepSeekClient.callSync(config.getSummaryPrompt() + "\n\n" + conversationText)
    //
    // DeepSeekClient.callSync implementation:
    //   POST to /chat/completions with:
    //     model: same as main chat (${deepseek.api.model})
    //     messages: [{ role: "user", content: prompt }]
    //     temperature: 0.1
    //     max_tokens: 1024
    //   Uses .block(Duration.ofSeconds(config.getLlmTimeoutSeconds()))
    //   Safe to block because this runs in the compression thread pool
    //   Timeout is configurable via ai.compression.llm-timeout-seconds
}
```

**Timeout**: Explicit `.block(Duration.ofSeconds(30))` with configurable timeout. Prevents indefinite hang if LLM API is unresponsive. On timeout, the retry mechanism catches the exception and retries with backoff.

### Retry Mechanism

```java
int retryCount = 0;
while (retryCount <= config.getRetryMax()) {
    try {
        // Execute compression...
        break;
    } catch (Exception e) {
        retryCount++;
        if (retryCount > config.getRetryMax()) {
            log.error("Compression failed after {} retries, conversationId={}",
                config.getRetryMax(), conversationId, e);
            break;
        }
        Thread.sleep(1000L * retryCount);  // Linear backoff: 1s, 2s, 3s
    }
}
```

After max retries exhausted:
- Remove from `activeTasks` (allows future submission)
- No sync truncation (we're below hard threshold, just compression failed)
- Next message will re-check and resubmit if still above soft threshold

### Sync Truncation (Hard Threshold Fallback)

Uses `sync_truncate.lua` script. Parameters: `conversationId` and `keepCount = keepRounds * 2`.

This is a pure Redis operation. No LLM call. Execution time is in single-digit milliseconds. Safe to run on a Netty thread due to negligible blocking.

### Exception Isolation

`checkAndCompress()` wraps all internal logic in try-catch. Exceptions never propagate to ChatHandler. The `syncToMySQL()` call (now placed before `checkAndCompress`) always executes regardless of compression outcome.

## Redis/MySQL Consistency

The call flow ensures MySQL consistency through ordering:

1. ChatHandler writes full history to Redis.
2. ChatHandler calls `syncToMySQL()` (persists uncompressed state to MySQL).
3. ChatHandler calls `checkAndCompress()` (may submit async task).
4. Async task completes: updates Redis via Lua, then calls `syncToMySQL()` again (overwrites MySQL with compressed state).

Since step 4 always happens after step 2, the final MySQL state is always the compressed version. There is a brief window where MySQL contains uncompressed data, but this is acceptable because:

- The data is not lost, just not yet compressed.
- On application restart or conversation reload, MySQL data is loaded into Redis and the next message check will trigger compression if still above threshold.

## Integration Points

### File Changes Summary

| File | Operation | Estimated Lines |
|---|---|---|
| `application.yml` | Append config section | ~20 |
| `CompressionProperties.java` | New file | ~20 |
| `CompressionConfig.java` | New file | ~20 |
| `ConversationCompressionService.java` | New file | ~200 |
| `DeepSeekClient.java` | Add `callSync()` method | ~25 |
| `ChatHandler.java` | Restructure `updateConversationHistory()`, switch to `StringRedisTemplate` | ~15 |
| `compress_and_replace.lua` | New file | ~20 |
| `sync_truncate.lua` | New file | ~15 |

Total: ~335 lines.

### ChatHandler.java Change

Restructure `updateConversationHistory()` — exact replacement code:

```java
// BEFORE (current code):
// 1. Append userMsg, assistantMsg to history
// 2. if (history.size() > 20) { history = history.subList(...) }
// 3. String json = objectMapper.writeValueAsString(history);
// 4. redisTemplate.opsForValue().set(key, json, Duration.ofDays(7));
// 5. conversationService.syncToMySQL(conversationId, userId);

// AFTER (new code):
// 1. Append userMsg, assistantMsg to history (unchanged)
// 2. REMOVED: if (history.size() > 20) { history = history.subList(...) }
// 3. String json = objectMapper.writeValueAsString(history);  // Full history, no truncation
// 4. redisTemplate.opsForValue().set(key, json, Duration.ofDays(7));
// 5. conversationService.syncToMySQL(conversationId, userId);  // Sync BEFORE compression
// 6. conversationCompressionService.checkAndCompress(conversationId, history);  // NEW
```

### CompressionProperties.java

```java
@Component
@ConfigurationProperties(prefix = "ai.compression")
@Data
public class CompressionProperties {
    private int softThreshold = 30;
    private int hardThresholdToken = 50000;
    private int keepRounds = 6;
    private int retryMax = 3;
    private int llmTimeoutSeconds = 30;
    private String summaryPrompt = "请将以下对话历史压缩为简洁摘要...";
    private ThreadPoolConfig threadPool;

    @Data
    public static class ThreadPoolConfig {
        private int coreSize = 2;
        private int maxSize = 4;
        private int queueCapacity = 50;
        private String threadNamePrefix = "compression-";
    }
}
```

Note: Pure properties holder, matches `AiProperties.java` pattern.

### CompressionConfig.java

```java
@Configuration
public class CompressionConfig {

    @Bean
    public ThreadPoolTaskExecutor compressionExecutor(CompressionProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getThreadPool().getCoreSize());
        executor.setMaxPoolSize(props.getThreadPool().getMaxSize());
        executor.setQueueCapacity(props.getThreadPool().getQueueCapacity());
        executor.setThreadNamePrefix(props.getThreadPool().getThreadNamePrefix());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
```

Note: `@Configuration` with `@Bean` method for proper Spring proxying. Properties and bean definition are split into two classes following Spring best practices.

### DeepSeekClient.java Addition

Add a public synchronous method alongside existing streaming methods:

```java
public String callSync(String prompt, Duration timeout) {
    Map<String, Object> requestBody = new HashMap<>();
    requestBody.put("model", model);
    requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
    requestBody.put("temperature", 0.1);
    requestBody.put("max_tokens", 1024);

    return webClient.post()
        .uri("/chat/completions")
        .bodyValue(requestBody)
        .retrieve()
        .bodyToMono(String.class)
        .timeout(timeout)
        .map(response -> {
            try {
                JsonNode node = objectMapper.readTree(response);
                return node.path("choices").path(0).path("message").path("content").asText();
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse LLM response", e);
            }
        })
        .block(timeout.multipliedBy(2));  // block timeout = 2x mono timeout as safety net
}
```

Note: `timeout` is passed from the caller (`ConversationCompressionService`) using `Duration.ofSeconds(compressionProperties.getLlmTimeoutSeconds())`. This keeps `DeepSeekClient` free from compression-specific config dependencies.

### ConversationCompressionService Method List

```java
@Service
public class ConversationCompressionService {
    // Dependencies
    private final ThreadPoolTaskExecutor compressionExecutor;
    private final ConversationService conversationService;
    private final CompressionProperties config;
    private final StringRedisTemplate stringRedisTemplate;  // NOT RedisTemplate<String, Object>
    private final RedisScript<Long> compressScript;
    private final RedisScript<Long> truncateScript;
    private final DeepSeekClient deepSeekClient;

    // Deduplication
    private final ConcurrentHashMap<String, CompletableFuture<Void>> activeTasks;

    // Public
    public void checkAndCompress(String conversationId, List<Map<String, String>> history);

    // Internal
    private int estimateTokens(List<Map<String, String>> history);
    private void syncTruncate(String conversationId, int keepCount);
    private void submitAsyncCompression(String conversationId);
    private void executeCompression(String conversationId);
    private String callLlmForSummary(String conversationText);
    private void applyCompressionViaLua(String conversationId, int splitIndex, String summary);
}
```

Note: `submitAsyncCompression` no longer takes `splitIndex` parameter. The index is computed inside the async task from the current Redis state.

## Observability

Key log points:

```java
// Soft threshold exceeded, async submitted
log.info("Compression triggered: conversationId={}, messages={}, tokens={}", id, size, tokens);

// Compression completed
log.info("Compression completed: conversationId={}, before={} messages, after={} messages, saved={} tokens",
    id, beforeSize, afterSize, savedTokens);

// Compression failed after retries
log.error("Compression failed after {} retries: conversationId={}", maxRetries, id, e);

// Hard threshold triggered (emergency)
log.warn("Hard threshold reached, sync truncating: conversationId={}, tokens={}", id, tokens);

// DiscardPolicy rejected task
log.warn("Compression pool full, task discarded: conversationId={}", id);

// Lua script returned error
log.error("Lua compression script error (code={}): conversationId={}", returnCode, id);
```

These structured log lines provide visibility into compression frequency, effectiveness, and failure patterns without requiring additional monitoring infrastructure.

## Safety Summary

| Risk | Mitigation |
|---|---|
| Redis concurrent overwrite | Lua script atomic head replacement with pcall validation |
| Netty EventLoop blocking | DiscardPolicy, never runs LLM calls on EventLoop |
| Conversation data loss | Hard threshold sync truncation (millisecond fallback) |
| Malformed LLM summary corrupts Redis | pcall guard in Lua script returns -1, Java side skips write |
| Compression task pile-up | ConcurrentHashMap dedup + DiscardPolicy |
| activeTasks entry leak on exception | `activeTasks.remove()` in `finally` block guarantees cleanup |
| Stale splitIndex after history growth | Recompute splitIndex from current Redis state inside async task |
| Redis/MySQL inconsistency | syncToMySQL before compression; async task re-syncs after |
| Redis key TTL reset | Lua script preserves existing TTL via TTL + EX |
| Compression LLM failure | Retry with linear backoff, configurable timeout |
| Exception propagation | checkAndCompress fully try-caught internally |
