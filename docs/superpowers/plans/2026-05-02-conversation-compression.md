# Long Memory Async Compression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement async compression of early conversation history into LLM-generated summaries, preserving recent N rounds verbatim, with soft/hard dual thresholds and Redis-atomic Lua scripts.

**Architecture:** New `ConversationCompressionService` triggered after each chat exchange. ConcurrentHashMap dedup + custom thread pool (DiscardPolicy with logging) for async LLM summarization. Lua scripts for atomic Redis head replacement. `DeepSeekClient.callSync()` for synchronous LLM calls from the compression thread pool.

**Tech Stack:** Spring Boot, Spring Data Redis (StringRedisTemplate + RedisScript), Lua, WebClient (blocking), Jackson ObjectMapper

**Spec:** `docs/superpowers/specs/2026-05-02-conversation-compression-design.md`

**Note on tests:** Unit tests are deferred to a follow-up task. The `estimateTokens` and threshold logic are straightforward and will be validated via end-to-end smoke test in Task 7.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `src/main/resources/application.yml` | Modify | Add `compression:` block under existing `ai:` |
| `src/main/java/.../config/CompressionProperties.java` | Create | `@Component` + `@ConfigurationProperties(prefix="ai.compression")` |
| `src/main/java/.../config/CompressionConfig.java` | Create | `@Configuration` with `compressionExecutor` + Lua `RedisScript` beans |
| `src/main/resources/scripts/compress_and_replace.lua` | Create | Atomic head-replacement Lua script |
| `src/main/resources/scripts/sync_truncate.lua` | Create | Emergency truncation Lua script |
| `src/main/java/.../client/DeepSeekClient.java` | Modify | Add `callSync(String, Duration)` method |
| `src/main/java/.../config/RedisConfig.java` | Modify | Keep existing bean — Spring Boot auto-configures `StringRedisTemplate` separately |
| `src/main/java/.../service/ConversationService.java` | Modify | Switch `@Autowired RedisTemplate<String, String>` → `@Autowired StringRedisTemplate` |
| `src/main/java/.../service/ChatHandler.java` | Modify | Switch to `StringRedisTemplate`, inject compression service, restructure `updateConversationHistory()` |
| `src/main/java/.../service/ConversationCompressionService.java` | Create | Core compression logic |

**Bean injection note:** The existing `RedisConfig.java` defines a `RedisTemplate<String, Object>` bean. Spring Boot also auto-configures a `StringRedisTemplate` bean (from `StringRedisTemplateAutoConfiguration`). These are different types and do NOT conflict — `StringRedisTemplate` and `RedisTemplate<String, Object>` can coexist. ChatHandler and ConversationService will switch to inject `StringRedisTemplate` by type.

**Config namespace note:** `CompressionProperties` uses prefix `"ai.compression"`. The existing `AiProperties` uses prefix `"ai"`. Spring Boot handles nested prefixes correctly — properties under `ai.compression` that don't map to `AiProperties` fields are silently ignored (default behavior). No conflict.

---

### Task 1: Add compression config to application.yml

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Append compression config under existing `ai:` block**

The `ai:` key starts at line 130. Add `compression:` as a sibling to the existing `prompt:` and `generation:` keys (same indentation level). Insert after line 146 (`top-p: 0.9`):

```yaml
  compression:
    soft-threshold: 30
    hard-threshold-token: 50000
    keep-rounds: 6
    retry-max: 3
    llm-timeout-seconds: 30
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

Note: Indent `compression:` with 2 spaces (same as `prompt:` and `generation:` under `ai:`).

- [ ] **Step 2: Verify the app starts**

Run: `cd E:/Curzsu/ShadowRAG && mvn spring-boot:run`
Expected: App starts without errors. (Ctrl+C to stop — we just need it to parse config successfully.)

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/application.yml
git commit -m "feat: add ai.compression config block to application.yml"
```

---

### Task 2: Create CompressionProperties + CompressionConfig (with Lua script beans)

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/config/CompressionProperties.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/config/CompressionConfig.java`

- [ ] **Step 1: Create CompressionProperties.java**

Create `src/main/java/com/yizhaoqi/smartpai/config/CompressionProperties.java`:

```java
package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.compression")
@Data
public class CompressionProperties {

    private int softThreshold = 30;
    private int hardThresholdToken = 50000;
    private int keepRounds = 6;
    private int retryMax = 3;
    private int llmTimeoutSeconds = 30;
    private String summaryPrompt = "请将以下对话历史压缩为简洁摘要，保留关键信息。";
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

- [ ] **Step 2: Create CompressionConfig.java (complete — thread pool + Lua scripts)**

Create `src/main/java/com/yizhaoqi/smartpai/config/CompressionConfig.java`:

```java
package com.yizhaoqi.smartpai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class CompressionConfig {

    private static final Logger logger = LoggerFactory.getLogger(CompressionConfig.class);

    @Bean
    public ThreadPoolTaskExecutor compressionExecutor(CompressionProperties props) {
        CompressionProperties.ThreadPoolConfig tp = props.getThreadPool();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(tp.getCoreSize());
        executor.setMaxPoolSize(tp.getMaxSize());
        executor.setQueueCapacity(tp.getQueueCapacity());
        executor.setThreadNamePrefix(tp.getThreadNamePrefix());
        // Custom DiscardPolicy that logs before discarding
        executor.setRejectedExecutionHandler((r, exec) ->
                logger.warn("Compression pool full, task discarded")
        );
        executor.initialize();
        return executor;
    }

    @Bean
    public RedisScript<Long> compressScript() {
        return RedisScript.of(
                new ClassPathResource("scripts/compress_and_replace.lua"), Long.class);
    }

    @Bean
    public RedisScript<Long> truncateScript() {
        return RedisScript.of(
                new ClassPathResource("scripts/sync_truncate.lua"), Long.class);
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd E:/Curzsu/ShadowRAG && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/config/CompressionProperties.java src/main/java/com/yizhaoqi/smartpai/config/CompressionConfig.java
git commit -m "feat: add CompressionProperties and CompressionConfig (thread pool + Lua script beans)"
```

---

### Task 3: Create Lua scripts

**Files:**
- Create: `src/main/resources/scripts/compress_and_replace.lua`
- Create: `src/main/resources/scripts/sync_truncate.lua`

- [ ] **Step 1: Create compress_and_replace.lua**

Create `src/main/resources/scripts/compress_and_replace.lua`:

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

- [ ] **Step 2: Create sync_truncate.lua**

Create `src/main/resources/scripts/sync_truncate.lua`:

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

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/scripts/
git commit -m "feat: add Lua scripts for atomic compress_and_replace and sync_truncate"
```

---

### Task 4: Add callSync to DeepSeekClient

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/client/DeepSeekClient.java`

- [ ] **Step 1: Add imports**

Add to the existing imports in `DeepSeekClient.java`:

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Duration;
```

- [ ] **Step 2: Add callSync method**

Add this method at the end of the class, before the closing `}`:

```java
/**
 * Synchronous LLM call for compression summaries.
 * Blocks the calling thread until response is received.
 * Safe to call from the compression thread pool — NOT from Netty EventLoop.
 */
public String callSync(String prompt, Duration timeout) {
    Map<String, Object> requestBody = new HashMap<>();
    requestBody.put("model", model);
    requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
    requestBody.put("temperature", 0.1);
    requestBody.put("max_tokens", 1024);

    return webClient.post()
            .uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
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
            .block(timeout.multipliedBy(2));
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd E:/Curzsu/ShadowRAG && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/client/DeepSeekClient.java
git commit -m "feat: add callSync method to DeepSeekClient for compression summaries"
```

---

### Task 5: Refactor ChatHandler + ConversationService to StringRedisTemplate

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ConversationService.java`

This is a mandatory prerequisite. The Lua scripts assume clean JSON in Redis — `GenericJackson2JsonRedisSerializer` causes double-encoding. The existing `RedisConfig.java` defines `RedisTemplate<String, Object>` — we do NOT remove it. Instead, we switch ChatHandler and ConversationService to inject Spring Boot's auto-configured `StringRedisTemplate` (different type, no conflict).

- [ ] **Step 1: Refactor ChatHandler.java**

Replace import at line 10:

```java
// BEFORE:
import org.springframework.data.redis.core.RedisTemplate;

// AFTER:
import org.springframework.data.redis.core.StringRedisTemplate;
```

Replace field at line 38:

```java
// BEFORE:
private final RedisTemplate<String, String> redisTemplate;

// AFTER:
private final StringRedisTemplate redisTemplate;
```

Update the constructor parameter: change `RedisTemplate<String, String>` to `StringRedisTemplate`.

- [ ] **Step 2: Refactor ConversationService.java**

Replace import at line 14:

```java
// BEFORE:
import org.springframework.data.redis.core.RedisTemplate;

// AFTER:
import org.springframework.data.redis.core.StringRedisTemplate;
```

Replace field at line 37-38:

```java
// BEFORE:
@Autowired
private RedisTemplate<String, String> redisTemplate;

// AFTER:
@Autowired
private StringRedisTemplate redisTemplate;
```

Keep `@Autowired` annotation. `StringRedisTemplate` is auto-configured by Spring Boot and will be injected by type.

- [ ] **Step 3: Verify compilation**

Run: `cd E:/Curzsu/ShadowRAG && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Verify existing chat functionality works**

Run: `cd E:/Curzsu/ShadowRAG && mvn spring-boot:run`
Expected: App starts. Send a test message via the frontend to confirm chat still works (read/write to Redis).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java src/main/java/com/yizhaoqi/smartpai/service/ConversationService.java
git commit -m "refactor: switch ChatHandler/ConversationService to StringRedisTemplate

Eliminates GenericJackson2JsonRedisSerializer double-encoding.
Required prerequisite for Lua scripts that operate on clean JSON."
```

---

### Task 6: Create ConversationCompressionService

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/service/ConversationCompressionService.java`

This is the core implementation. All dependencies from Tasks 1-5 must be in place.

Key design decisions in this implementation:
- `checkAndCompress` takes `userId` parameter for `syncToMySQL` — no null username issue
- `submitAsyncCompression` creates the real `CompletableFuture` first, then uses `putIfAbsent` — clean single-step dedup
- `activeTasks.remove()` in `finally` block — guaranteed cleanup

- [ ] **Step 1: Create ConversationCompressionService.java**

Create `src/main/java/com/yizhaoqi/smartpai/service/ConversationCompressionService.java`:

```java
package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.CompressionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationCompressionService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationCompressionService.class);
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ThreadPoolTaskExecutor compressionExecutor;
    private final ConversationService conversationService;
    private final CompressionProperties config;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> compressScript;
    private final RedisScript<Long> truncateScript;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, CompletableFuture<Void>> activeTasks = new ConcurrentHashMap<>();

    public ConversationCompressionService(
            ThreadPoolTaskExecutor compressionExecutor,
            ConversationService conversationService,
            CompressionProperties config,
            StringRedisTemplate stringRedisTemplate,
            RedisScript<Long> compressScript,
            RedisScript<Long> truncateScript,
            DeepSeekClient deepSeekClient,
            ObjectMapper objectMapper) {
        this.compressionExecutor = compressionExecutor;
        this.conversationService = conversationService;
        this.config = config;
        this.stringRedisTemplate = stringRedisTemplate;
        this.compressScript = compressScript;
        this.truncateScript = truncateScript;
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Check thresholds and trigger compression if needed.
     * Called from ChatHandler after each message exchange.
     * All exceptions are caught internally — never propagates to caller.
     *
     * @param conversationId the conversation UUID
     * @param history        the current full message history
     * @param userId         the username (needed for syncToMySQL after compression)
     */
    public void checkAndCompress(String conversationId, List<Map<String, String>> history, String userId) {
        try {
            if (history == null || history.size() < config.getSoftThreshold()) {
                return;
            }

            int tokens = estimateTokens(history);
            logger.info("Compression check: conversationId={}, messages={}, estimatedTokens={}",
                    conversationId, history.size(), tokens);

            if (tokens >= config.getHardThresholdToken()) {
                logger.warn("Hard threshold reached, sync truncating: conversationId={}, tokens={}",
                        conversationId, tokens);
                syncTruncate(conversationId, config.getKeepRounds() * 2);
                return;
            }

            submitAsyncCompression(conversationId, userId);

        } catch (Exception e) {
            logger.error("checkAndCompress failed for conversationId={}: {}", conversationId, e.getMessage(), e);
        }
    }

    int estimateTokens(List<Map<String, String>> history) {
        return history.stream()
                .mapToInt(m -> m.getOrDefault("content", "").length() / 2)
                .sum();
    }

    private void syncTruncate(String conversationId, int keepCount) {
        String key = "conversation:" + conversationId;
        Long result = stringRedisTemplate.execute(
                truncateScript,
                List.of(key),
                String.valueOf(keepCount)
        );
        logger.info("Sync truncate completed: conversationId={}, remainingMessages={}", conversationId, result);
    }

    private void submitAsyncCompression(String conversationId, String userId) {
        // Create the real future first, then atomically put if absent
        CompletableFuture<Void> future = CompletableFuture.runAsync(
                () -> executeCompression(conversationId, userId),
                compressionExecutor
        );
        CompletableFuture<Void> existing = activeTasks.putIfAbsent(conversationId, future);
        if (existing != null) {
            // Another compression is already running for this conversation — skip
            logger.debug("Compression already in progress for conversationId={}", conversationId);
            future.cancel(false);
        }
    }

    private void executeCompression(String conversationId, String userId) {
        int retryCount = 0;
        try {
            while (retryCount <= config.getRetryMax()) {
                try {
                    doCompress(conversationId, userId);
                    return;
                } catch (Exception e) {
                    retryCount++;
                    if (retryCount > config.getRetryMax()) {
                        logger.error("Compression failed after {} retries: conversationId={}",
                                config.getRetryMax(), conversationId, e);
                        return;
                    }
                    logger.warn("Compression attempt {} failed for conversationId={}, retrying: {}",
                            retryCount, conversationId, e.getMessage());
                    Thread.sleep(1000L * retryCount);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Compression interrupted for conversationId={}", conversationId);
        } finally {
            activeTasks.remove(conversationId);
        }
    }

    private void doCompress(String conversationId, String userId) throws Exception {
        // 1. Read current history from Redis
        String key = "conversation:" + conversationId;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) return;

        List<Map<String, String>> currentHistory = objectMapper.readValue(
                json, new TypeReference<List<Map<String, String>>>() {});

        // 2. Recompute splitIndex from current state
        int keepCount = config.getKeepRounds() * 2;
        int splitIndex = currentHistory.size() - keepCount;
        if (splitIndex <= 0) {
            logger.debug("Compression no longer needed for conversationId={}, size={}",
                    conversationId, currentHistory.size());
            return;
        }

        // 3-4. Extract head for summarization
        List<Map<String, String>> head = currentHistory.subList(0, splitIndex);
        if (head.isEmpty()) return;

        // Build conversation text for LLM
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> msg : head) {
            sb.append(msg.getOrDefault("role", "unknown")).append(": ")
              .append(msg.getOrDefault("content", "")).append("\n\n");
        }

        // 5. Call LLM for summary
        String summary = callLlmForSummary(sb.toString());
        if (summary == null || summary.isBlank()) {
            logger.warn("LLM returned empty summary for conversationId={}", conversationId);
            return;
        }

        // 6. Construct summary message
        String summaryJson = objectMapper.writeValueAsString(Map.of(
                "role", "system",
                "content", "[历史摘要] " + summary,
                "timestamp", LocalDateTime.now().format(TS_FORMAT)
        ));

        // 7. Execute Lua script: atomic head replacement
        Long result = stringRedisTemplate.execute(
                compressScript,
                List.of(key),
                String.valueOf(splitIndex),
                summaryJson
        );

        if (result != null && result == -1) {
            logger.error("Lua script rejected summary JSON (code=-1): conversationId={}", conversationId);
            return;
        }

        logger.info("Compression completed: conversationId={}, before={} messages, after={} messages",
                conversationId, currentHistory.size(), result);

        // 8. Persist compressed state to MySQL
        conversationService.syncToMySQL(conversationId, userId);
    }

    private String callLlmForSummary(String conversationText) {
        String prompt = config.getSummaryPrompt() + "\n\n对话历史：\n" + conversationText;
        Duration timeout = Duration.ofSeconds(config.getLlmTimeoutSeconds());
        return deepSeekClient.callSync(prompt, timeout);
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd E:/Curzsu/ShadowRAG && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ConversationCompressionService.java
git commit -m "feat: add ConversationCompressionService with async compression logic"
```

---

### Task 7: Integrate compression into ChatHandler

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`

- [ ] **Step 1: Add dependency injection**

Add field:

```java
private final ConversationCompressionService compressionService;
```

Add constructor parameter `ConversationCompressionService compressionService` and assign `this.compressionService = compressionService;`.

- [ ] **Step 2: Restructure updateConversationHistory()**

In `ChatHandler.java`, method `updateConversationHistory()` (starting at line 318):

**Remove** the 20-message hard cap (lines 339-342):
```java
// REMOVE THESE 3 LINES:
if (history.size() > 20) {
    history = history.subList(history.size() - 20, history.size());
}
```

**Reorder** the try block — write Redis, sync MySQL, then check compression:

```java
try {
    String json = objectMapper.writeValueAsString(history);
    redisTemplate.opsForValue().set(key, json, Duration.ofDays(7));
    logger.debug("更新会话历史，会话ID: {}, 总消息数: {}", conversationId, history.size());

    // Sync to MySQL BEFORE compression
    conversationService.syncToMySQL(conversationId, userId);

    // Check thresholds and compress if needed
    compressionService.checkAndCompress(conversationId, history, userId);

} catch (JsonProcessingException e) {
    logger.error("序列化对话历史出错: {}, 会话ID: {}", e.getMessage(), conversationId, e);
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd E:/Curzsu/ShadowRAG && mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: End-to-end smoke test**

1. Start the app: `cd E:/Curzsu/ShadowRAG && mvn spring-boot:run`
2. Open the frontend, send several messages in a conversation until it exceeds 30 messages (soft threshold)
3. Check logs for `"Compression check"` and `"Compression completed"` messages
4. Verify conversation history in Redis via `redis-cli GET conversation:{id}` — should contain a `[历史摘要]` system message at the head
5. Verify MySQL `conversations` table has the compressed history

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java
git commit -m "feat: integrate compression into ChatHandler, remove 20-msg hard cap

- syncToMySQL moved before checkAndCompress for data safety
- Full history written to Redis (no truncation)
- Compression triggered after each message exchange"
```

---

### Task 8: Push all commits

- [ ] **Step 1: Push to remote**

```bash
git push
```

Expected: All commits pushed to `origin/master`.
