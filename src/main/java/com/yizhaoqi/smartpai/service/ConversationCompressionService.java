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
        CompletableFuture<Void> future = CompletableFuture.runAsync(
                () -> executeCompression(conversationId, userId),
                compressionExecutor
        );
        CompletableFuture<Void> existing = activeTasks.putIfAbsent(conversationId, future);
        if (existing != null) {
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
        String key = "conversation:" + conversationId;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) return;

        List<Map<String, String>> currentHistory = objectMapper.readValue(
                json, new TypeReference<List<Map<String, String>>>() {});

        int keepCount = config.getKeepRounds() * 2;
        int splitIndex = currentHistory.size() - keepCount;
        if (splitIndex <= 0) {
            logger.debug("Compression no longer needed for conversationId={}, size={}",
                    conversationId, currentHistory.size());
            return;
        }

        List<Map<String, String>> head = currentHistory.subList(0, splitIndex);
        if (head.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        for (Map<String, String> msg : head) {
            sb.append(msg.getOrDefault("role", "unknown")).append(": ")
              .append(msg.getOrDefault("content", "")).append("\n\n");
        }

        String summary = callLlmForSummary(sb.toString());
        if (summary == null || summary.isBlank()) {
            logger.warn("LLM returned empty summary for conversationId={}", conversationId);
            return;
        }

        String summaryJson = objectMapper.writeValueAsString(Map.of(
                "role", "system",
                "content", "[历史摘要] " + summary,
                "timestamp", LocalDateTime.now().format(TS_FORMAT)
        ));

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

        conversationService.syncToMySQL(conversationId, userId);
    }

    private String callLlmForSummary(String conversationText) {
        String prompt = config.getSummaryPrompt() + "\n\n对话历史：\n" + conversationText;
        Duration timeout = Duration.ofSeconds(config.getLlmTimeoutSeconds());
        return deepSeekClient.callSync(prompt, timeout);
    }
}
