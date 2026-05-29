package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
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
    private static final Encoding TOKEN_ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    private final ThreadPoolTaskExecutor compressionExecutor;
    private final CompressionProperties config;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> compressScript;
    private final RedisScript<Long> truncateScript;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, CompletableFuture<Void>> activeTasks = new ConcurrentHashMap<>();

    public ConversationCompressionService(
            ThreadPoolTaskExecutor compressionExecutor,
            CompressionProperties config,
            StringRedisTemplate stringRedisTemplate,
            RedisScript<Long> compressScript,
            RedisScript<Long> truncateScript,
            DeepSeekClient deepSeekClient,
            ObjectMapper objectMapper) {
        this.compressionExecutor = compressionExecutor;
        this.config = config;
        this.stringRedisTemplate = stringRedisTemplate;
        this.compressScript = compressScript;
        this.truncateScript = truncateScript;
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = objectMapper;
    }

    public void checkAndCompress(String conversationId, List<Map<String, String>> history, String userId) {
        try {
            if (history == null || history.isEmpty()) {
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

            if (history.size() >= config.getSoftThreshold() || tokens >= config.getSoftThresholdToken()) {
                submitAsyncCompression(conversationId, userId);
            }

        } catch (Exception e) {
            logger.error("checkAndCompress failed for conversationId={}: {}", conversationId, e.getMessage(), e);
        }
    }

    int estimateTokens(List<Map<String, String>> history) {
        return history.stream()
                .mapToInt(m -> {
                    String text = m.getOrDefault("role", "") + ": " + m.getOrDefault("content", "");
                    return TOKEN_ENCODING.countTokens(text);
                })
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
        try {
            activeTasks.computeIfAbsent(conversationId, key ->
                    CompletableFuture.runAsync(
                            () -> executeCompression(conversationId, userId),
                            compressionExecutor
                    )
            );
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.warn("Compression task rejected (pool full): conversationId={}", conversationId);
            activeTasks.remove(conversationId);
        } catch (Exception e) {
            logger.error("Failed to submit compression task: conversationId={}", conversationId, e);
            activeTasks.remove(conversationId);
        }
    }

    private void executeCompression(String conversationId, String userId) {
        int retryCount = 0;
        try {
            while (retryCount <= config.getRetryMax()) {
                try {
                    doCompress(conversationId);
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

    private void doCompress(String conversationId) throws Exception {
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

        // === 增量压缩：找已有摘要边界 ===
        String marker = config.getSummaryMarker();
        int lastSummaryIndex = -1;
        for (int i = 0; i < splitIndex; i++) {
            String content = currentHistory.get(i).getOrDefault("content", "");
            if (content.startsWith(marker)) {
                lastSummaryIndex = i;
            }
        }

        int compressStart = (lastSummaryIndex == -1) ? 0 : lastSummaryIndex + 1;

        // 新消息范围 [compressStart, splitIndex)
        List<Map<String, String>> toCompress = currentHistory.subList(compressStart, splitIndex);
        if (toCompress.isEmpty()) {
            logger.debug("No new messages to compress for conversationId={}", conversationId);
            return;
        }

        // 构建 LLM 输入（只对 toCompress 生成独立摘要，不合并旧摘要）
        StringBuilder sb = new StringBuilder();
        if (lastSummaryIndex >= 0) {
            String previousSummary = currentHistory.get(lastSummaryIndex).getOrDefault("content", "");
            String summaryText = previousSummary.substring(marker.length()).trim();
            // 旧摘要仅作为上下文帮助 LLM 理解连贯性，不要求合并
            sb.append("[以下是之前对话的摘要，仅供理解上下文，不需要合并]\n")
              .append(summaryText).append("\n\n");
            sb.append("[以下是新的对话内容，请只对这部分生成独立的摘要]\n");
        }
        for (Map<String, String> msg : toCompress) {
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
                "content", config.getSummaryMarker() + " " + summary,
                "timestamp", LocalDateTime.now().format(TS_FORMAT)
        ));

        // 调用 Lua 脚本：删除 [compressStart, splitIndex)，插入新摘要
        Long result = stringRedisTemplate.execute(
                compressScript,
                List.of(key),
                String.valueOf(compressStart),   // ARGV[1]
                String.valueOf(splitIndex),       // ARGV[2]
                summaryJson                       // ARGV[3]
        );

        if (result != null && result == -1) {
            logger.error("Lua script rejected summary JSON (code=-1): conversationId={}", conversationId);
            return;
        }

        logger.info("Incremental compression completed: conversationId={}, " +
                    "compressRange=[{},{}), before={} messages, after={} messages",
                conversationId, compressStart, splitIndex, currentHistory.size(), result);
    }

    private String callLlmForSummary(String conversationText) {
        String prompt = config.getSummaryPrompt() + "\n\n对话历史：\n" + conversationText;
        Duration timeout = Duration.ofSeconds(config.getLlmTimeoutSeconds());
        return deepSeekClient.callSync(prompt, timeout);
    }
}
