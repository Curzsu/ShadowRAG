package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.CompressionProperties;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationCompressionService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationCompressionService.class);
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private final ThreadPoolTaskExecutor compressionExecutor;
    private final CompressionProperties config;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> compressScript;
    private final RedisScript<Long> truncateScript;
    private final RedisScript<Long> appendMessagesScript;
    private final RedisScript<Long> replaceWorkingSetScript;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper;
    private final TokenEstimator tokenEstimator;
    private final ContextBudgetService contextBudgetService;

    private final ConcurrentHashMap<String, CompletableFuture<Void>> activeTasks = new ConcurrentHashMap<>();

    enum CompressionOutcome {
        COMPRESSED,
        NOT_NEEDED,
        VERSION_CONFLICT
    }

    public ConversationCompressionService(
            ThreadPoolTaskExecutor compressionExecutor,
            CompressionProperties config,
            StringRedisTemplate stringRedisTemplate,
            @Qualifier("compressScript") RedisScript<Long> compressScript,
            @Qualifier("truncateScript") RedisScript<Long> truncateScript,
            @Qualifier("appendMessagesScript") RedisScript<Long> appendMessagesScript,
            @Qualifier("replaceWorkingSetScript") RedisScript<Long> replaceWorkingSetScript,
            DeepSeekClient deepSeekClient,
            ObjectMapper objectMapper,
            TokenEstimator tokenEstimator,
            ContextBudgetService contextBudgetService) {
        this.compressionExecutor = compressionExecutor;
        this.config = config;
        this.stringRedisTemplate = stringRedisTemplate;
        this.compressScript = compressScript;
        this.truncateScript = truncateScript;
        this.appendMessagesScript = appendMessagesScript;
        this.replaceWorkingSetScript = replaceWorkingSetScript;
        this.deepSeekClient = deepSeekClient;
        this.objectMapper = objectMapper;
        this.tokenEstimator = tokenEstimator;
        this.contextBudgetService = contextBudgetService;
    }

    public void checkAndCompress(String conversationId, List<Map<String, String>> history) {
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

            // 仅按 token 维度判断：条数维度冗余（短消息 token 低无需压，长消息 token 高会触发），
            // 且防不住"条数多但 token 平缓"后的突变长消息——真正兜底是硬阈值。
            if (tokens >= config.getSoftThresholdToken()) {
                submitAsyncCompression(conversationId);
            }

        } catch (Exception e) {
            logger.error("checkAndCompress failed for conversationId={}: {}", conversationId, e.getMessage(), e);
        }
    }

    int estimateTokens(List<Map<String, String>> history) {
        return tokenEstimator.countMessages(history);
    }

    public long appendMessages(String conversationId, List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0L;
        }
        try {
            List<String> arguments = new ArrayList<>(messages.size() + 1);
            arguments.add(String.valueOf(config.getHistoryTtlSeconds()));
            for (Map<String, String> message : messages) {
                arguments.add(objectMapper.writeValueAsString(message));
            }
            Long result = stringRedisTemplate.execute(
                    appendMessagesScript,
                    List.of(historyKey(conversationId), versionKey(conversationId)),
                    arguments.toArray());
            if (result == null || result < 0) {
                throw new IllegalStateException("Redis rejected atomic message append, code=" + result);
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to append conversation messages atomically", e);
        }
    }

    public long getWorkingSetVersion(String conversationId) {
        String version = stringRedisTemplate.opsForValue().get(versionKey(conversationId));
        return version == null ? 0L : Long.parseLong(version);
    }

    public boolean replaceWorkingSet(String conversationId,
                                     long expectedVersion,
                                     List<Map<String, String>> history) {
        try {
            Long result = stringRedisTemplate.execute(
                    replaceWorkingSetScript,
                    List.of(historyKey(conversationId), versionKey(conversationId)),
                    String.valueOf(expectedVersion),
                    String.valueOf(config.getHistoryTtlSeconds()),
                    objectMapper.writeValueAsString(history));
            if (result != null && result == -2L) {
                return false;
            }
            if (result == null || result < 0L) {
                throw new IllegalStateException("Redis rejected working-set replacement, code=" + result);
            }
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to replace conversation working set", e);
        }
    }

    private void syncTruncate(String conversationId, int keepCount) {
        String key = "conversation:" + conversationId;
        Long result = stringRedisTemplate.execute(
                truncateScript,
                List.of(key, versionKey(conversationId)),
                String.valueOf(keepCount)
        );
        logger.info("Sync truncate completed: conversationId={}, remainingMessages={}", conversationId, result);
    }

    private void submitAsyncCompression(String conversationId) {
        CompletableFuture<Void> taskMarker = new CompletableFuture<>();
        if (activeTasks.putIfAbsent(conversationId, taskMarker) != null) {
            return;
        }
        try {
            compressionExecutor.execute(() -> {
                try {
                    executeCompression(conversationId);
                    taskMarker.complete(null);
                } catch (Throwable error) {
                    taskMarker.completeExceptionally(error);
                    logger.error("Unexpected compression task failure: conversationId={}", conversationId, error);
                } finally {
                    activeTasks.remove(conversationId, taskMarker);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.warn("Compression task rejected (pool full): conversationId={}", conversationId);
            taskMarker.completeExceptionally(e);
            activeTasks.remove(conversationId, taskMarker);
        } catch (Exception e) {
            logger.error("Failed to submit compression task: conversationId={}", conversationId, e);
            taskMarker.completeExceptionally(e);
            activeTasks.remove(conversationId, taskMarker);
        }
    }

    private void executeCompression(String conversationId) {
        int retryCount = 0;
        try {
            while (retryCount <= config.getRetryMax()) {
                try {
                    CompressionOutcome outcome = compressOnce(conversationId);
                    if (outcome == CompressionOutcome.VERSION_CONFLICT) {
                        logger.info("Discarded stale compression result after version conflict: conversationId={}",
                                conversationId);
                    }
                    return;
                } catch (ContextWindowExceededException e) {
                    logger.warn("Compression prompt exceeds model context; keeping current working set: " +
                                    "conversationId={}, requiredTokens={}, availableTokens={}",
                            conversationId, e.getRequiredTokens(), e.getAvailableTokens());
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
        }
    }

    CompressionOutcome compressOnce(String conversationId) throws Exception {
        String key = historyKey(conversationId);
        String versionKey = versionKey(conversationId);
        // 先读版本再读快照：若两次读取之间发生 append，最终 CAS 必然冲突，
        // 不会把基于混合时刻生成的摘要误写回新工作集。
        String versionText = stringRedisTemplate.opsForValue().get(versionKey);
        long expectedVersion = versionText == null ? 0L : Long.parseLong(versionText);
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) return CompressionOutcome.NOT_NEEDED;

        List<Map<String, String>> currentHistory = objectMapper.readValue(
                json, new TypeReference<List<Map<String, String>>>() {});

        int keepCount = config.getKeepRounds() * 2;
        int splitIndex = currentHistory.size() - keepCount;
        if (splitIndex <= 0) {
            logger.debug("Compression no longer needed for conversationId={}, size={}",
                    conversationId, currentHistory.size());
            return CompressionOutcome.NOT_NEEDED;
        }

        // === 增量压缩：找已有摘要边界 ===
        String marker = config.getSummaryMarker();
        int lastSummaryIndex = -1;
        for (int i = 0; i < splitIndex; i++) {
            if (isSummaryMessage(currentHistory.get(i), marker)) {
                lastSummaryIndex = i;
            }
        }

        int compressStart = (lastSummaryIndex == -1) ? 0 : lastSummaryIndex + 1;

        // 新消息范围 [compressStart, splitIndex)
        List<Map<String, String>> toCompress = currentHistory.subList(compressStart, splitIndex);
        if (toCompress.isEmpty()) {
            logger.debug("No new messages to compress for conversationId={}", conversationId);
            return CompressionOutcome.NOT_NEEDED;
        }

        // 构建 LLM 输入（只对 toCompress 生成独立摘要，不合并旧摘要）
        StringBuilder sb = new StringBuilder();
        if (lastSummaryIndex >= 0) {
            Map<String, String> previousSummary = currentHistory.get(lastSummaryIndex);
            String summaryText = summaryContent(previousSummary, marker);
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
            return CompressionOutcome.NOT_NEEDED;
        }

        Map<String, String> summaryMessage = new java.util.LinkedHashMap<>();
        summaryMessage.put("type", "summary");
        summaryMessage.put("role", "assistant");
        summaryMessage.put("content", summary.trim());
        summaryMessage.put("sourceStartSeq", sequenceOf(toCompress.get(0), compressStart));
        summaryMessage.put("sourceEndSeq", sequenceOf(toCompress.get(toCompress.size() - 1), splitIndex - 1));
        summaryMessage.put("timestamp", LocalDateTime.now().format(TS_FORMAT));
        String summaryJson = objectMapper.writeValueAsString(summaryMessage);

        // 调用 Lua 脚本：删除 [compressStart, splitIndex)，插入新摘要
        Long result = stringRedisTemplate.execute(
                compressScript,
                List.of(key, versionKey),
                String.valueOf(compressStart),   // ARGV[1]
                String.valueOf(splitIndex),       // ARGV[2]
                summaryJson,                      // ARGV[3]
                String.valueOf(expectedVersion)   // ARGV[4]
        );

        if (result != null && result == -2) {
            return CompressionOutcome.VERSION_CONFLICT;
        }
        if (result != null && result == -1) {
            throw new IllegalStateException("Lua script rejected compression payload");
        }
        if (result == null || result == 0) {
            return CompressionOutcome.NOT_NEEDED;
        }

        logger.info("Incremental compression completed: conversationId={}, " +
                    "compressRange=[{},{}), before={} messages, after={} messages",
                conversationId, compressStart, splitIndex, currentHistory.size(), result);
        return CompressionOutcome.COMPRESSED;
    }

    private boolean isSummaryMessage(Map<String, String> message, String marker) {
        return "summary".equals(message.get("type"))
                || message.getOrDefault("content", "").startsWith(marker);
    }

    private String summaryContent(Map<String, String> message, String marker) {
        String content = message.getOrDefault("content", "");
        return content.startsWith(marker) ? content.substring(marker.length()).trim() : content.trim();
    }

    private String sequenceOf(Map<String, String> message, int fallbackIndex) {
        return message.getOrDefault("seq", "position:" + fallbackIndex);
    }

    private String callLlmForSummary(String conversationText) {
        String prompt = config.getSummaryPrompt() + "\n\n对话历史：\n" + conversationText;
        contextBudgetService.fit(
                List.of(Map.of("role", "user", "content", prompt)),
                config.getSummaryOutputReserveTokens(),
                0,
                1);
        Duration timeout = Duration.ofSeconds(config.getLlmTimeoutSeconds());
        return deepSeekClient.callSync(prompt, timeout);
    }

    private String historyKey(String conversationId) {
        return "conversation:" + conversationId;
    }

    private String versionKey(String conversationId) {
        return historyKey(conversationId) + ":version";
    }
}
