package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.Conversation;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.ConversationRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ConversationService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ConversationMessageService conversationMessageService;
    private final ConversationCompressionService compressionService;

    public ConversationService(ConversationRepository conversationRepository,
                               UserRepository userRepository,
                               StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper,
                               ConversationMessageService conversationMessageService,
                               ConversationCompressionService compressionService) {
        this.conversationRepository = conversationRepository;
        this.userRepository = userRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.conversationMessageService = conversationMessageService;
        this.compressionService = compressionService;
    }

    /**
     * 新建对话
     * 生成 UUID，创建 MySQL 记录，设置 Redis current_conversation
     *
     * @param username 用户名
     * @return 新建的 Conversation 对象
     */
    public Conversation createConversation(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));

        String conversationId = UUID.randomUUID().toString();
        Conversation conversation = new Conversation();
        conversation.setConversationId(conversationId);
        conversation.setUser(user);
        conversation.setTitle("新对话");
        conversation.setMessages("[]");

        conversationRepository.save(conversation);

        // 设置 Redis 当前会话
        String redisKey = "user:" + username + ":current_conversation";
        redisTemplate.opsForValue().set(redisKey, conversationId, Duration.ofDays(7));

        logger.info("为用户 {} 创建新会话: {}", username, conversationId);
        return conversation;
    }

    /**
     * 获取用户的会话列表（按更新时间降序）
     *
     * @param username 用户名
     * @return 会话列表
     */
    public List<Conversation> getConversationList(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        return conversationRepository.findByUserIdOrderByUpdatedAtDesc(user.getId());
    }

    public Conversation requireOwnedConversation(String username, String conversationId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        Conversation conversation = conversationRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new CustomException("会话不存在", HttpStatus.NOT_FOUND));
        if (!conversation.getUser().getId().equals(user.getId())) {
            throw new CustomException("无权访问此会话", HttpStatus.FORBIDDEN);
        }
        return conversation;
    }

    /** Read or rebuild this conversation without changing the user's current pointer. */
    public List<Map<String, String>> loadHistoryForChat(String username, String conversationId) {
        Conversation conversation = requireOwnedConversation(username, conversationId);
        // An indexed scalar query prevents trusting a surviving stale Redis value, even after process restart.
        // Keep database failures outside the cache fallback catch.
        long latestDurableSequence = conversationMessageService.getLatestSequenceId(conversationId);
        try {
            String cached = redisTemplate.opsForValue().get("conversation:" + conversationId);
            if (cached != null) {
                JsonNode root = objectMapper.readTree(cached);
                if (isUsableWorkingSet(root) && coveredSequence(root) == latestDurableSequence) {
                    return objectMapper.convertValue(root, new TypeReference<List<Map<String, String>>>() {});
                }
            }
        } catch (JsonProcessingException error) {
            logger.warn("Conversation working set is malformed; rebuilding: conversationId={}", conversationId);
        } catch (RuntimeException error) {
            logger.warn("Conversation cache read unavailable; using database history: conversationId={}", conversationId);
            return loadMergedHistory(conversation);
        }

        List<Map<String, String>> latest = List.of();
        for (int attempt = 0; attempt < 3; attempt++) {
            long expectedVersion;
            try {
                expectedVersion = compressionService.getWorkingSetVersion(conversationId);
            } catch (RuntimeException error) {
                logger.warn("Conversation cache version unavailable; using database history: conversationId={}", conversationId);
                return loadMergedHistory(conversation);
            }
            // Database failures must propagate instead of becoming an empty history.
            latest = loadMergedHistory(conversation);
            try {
                if (compressionService.replaceWorkingSet(conversationId, expectedVersion, latest)) return latest;
            } catch (RuntimeException error) {
                logger.warn("Conversation cache rebuild unavailable; using database history: conversationId={}", conversationId);
                return latest;
            }
        }
        // Keep the fresh DB snapshot without overwriting a concurrently changed cache.
        return latest;
    }

    /** Preserve a current compressed working set, or rebuild a stale one, under one version CAS. */
    List<Map<String, String>> cacheCommittedTurn(String username, String conversationId,
                                                List<Map<String, String>> appended) {
        Conversation conversation = requireOwnedConversation(username, conversationId);
        if (appended.isEmpty() || appended.stream().anyMatch(message -> positiveSequence(message.get("seq")) == 0L)) {
            return loadHistoryForChat(username, conversationId);
        }
        long firstSequence = appended.stream().mapToLong(message -> positiveSequence(message.get("seq"))).min().orElseThrow();
        long lastSequence = appended.stream().mapToLong(message -> positiveSequence(message.get("seq"))).max().orElseThrow();
        // IDs are global across conversations, so firstSequence - 1 is not a valid prior watermark.
        long previousSequence = conversationMessageService.getLatestSequenceIdBefore(conversationId, firstSequence);
        for (int attempt = 0; attempt < 3; attempt++) {
            long expectedVersion;
            JsonNode cached;
            try {
                // Read version BEFORE content: a concurrent append/compression must invalidate our CAS.
                expectedVersion = compressionService.getWorkingSetVersion(conversationId);
                String json = redisTemplate.opsForValue().get("conversation:" + conversationId);
                cached = json == null ? null : objectMapper.readTree(json);
            } catch (JsonProcessingException error) {
                // Ordinary recovery handles malformed JSON with its own fresh version/read/rebuild cycle.
                return loadHistoryForChat(username, conversationId);
            } catch (RuntimeException error) {
                logger.warn("Post-commit cache read unavailable; using database history: conversationId={}, exceptionType={}",
                        conversationId, error.getClass().getSimpleName());
                return loadMergedHistory(conversation);
            }
            List<Map<String, String>> next;
            long coverage = isUsableWorkingSet(cached) ? coveredSequence(cached) : -1L;
            if (coverage == previousSequence || coverage == lastSequence) {
                next = objectMapper.convertValue(cached, new TypeReference<List<Map<String, String>>>() {});
                if (coverage == previousSequence) next.addAll(appended);
            } else {
                // Missing an earlier durable turn: never advance the cache watermark past that gap.
                next = loadMergedHistory(conversation);
            }
            try {
                if (compressionService.replaceWorkingSet(conversationId, expectedVersion, next)) return next;
            } catch (RuntimeException error) {
                logger.warn("Post-commit cache write unavailable; retaining history snapshot: conversationId={}, exceptionType={}",
                        conversationId, error.getClass().getSimpleName());
                return next;
            }
        }
        // A competing writer won every CAS. Do not overwrite it or retry the database append.
        logger.warn("Post-commit cache CAS retries exhausted; using database history: conversationId={}", conversationId);
        return loadMergedHistory(conversation);
    }

    private boolean isUsableWorkingSet(JsonNode root) {
        if (root == null || !root.isArray()) return false;
        for (JsonNode message : root) {
            if (!message.isObject() || !message.path("content").isTextual()) return false;
            JsonNode type = message.get("type");
            if (type != null && (!type.isTextual() || !"summary".equals(type.textValue()))) return false;
            boolean summary = type != null || message.get("content").textValue().startsWith("[历史摘要]");
            JsonNode role = message.get("role");
            if (role == null) {
                if (!summary) return false;
            } else {
                if (!role.isTextual()) return false;
                String value = role.textValue();
                if (!"user".equals(value) && !"assistant".equals(value) && !(summary && "system".equals(value))) return false;
            }
            JsonNode seq = message.get("seq");
            if (seq != null && (!seq.isTextual() || positiveSequence(seq.textValue()) == 0L)) return false;
            for (String field : List.of("sourceStartSeq", "sourceEndSeq")) {
                JsonNode boundary = message.get(field);
                if (boundary != null && (!summary || !boundary.isTextual() || !isSummaryBoundary(boundary.textValue()))) return false;
            }
            JsonNode timestamp = message.get("timestamp");
            if (timestamp != null && !timestamp.isTextual()) return false;
        }
        return true;
    }

    private boolean isSummaryBoundary(String value) {
        if (positiveSequence(value) > 0L) return true;
        if (!value.startsWith("position:")) return false;
        try { return Long.parseLong(value.substring("position:".length())) >= 0L; }
        catch (NumberFormatException error) { return false; }
    }

    private long coveredSequence(JsonNode history) {
        long latest = 0L;
        for (JsonNode message : history) {
            latest = Math.max(latest, positiveSequence(message.path("seq").asText("")));
            latest = Math.max(latest, positiveSequence(message.path("sourceEndSeq").asText("")));
        }
        return latest;
    }

    private long positiveSequence(String text) {
        try { return Math.max(0L, Long.parseLong(text)); }
        catch (NumberFormatException error) { return 0L; }
    }

    private List<Map<String, String>> loadMergedHistory(Conversation conversation) {
        List<Map<String, String>> raw = conversationMessageService.getRawHistory(conversation.getConversationId());
        try {
            return mergeLegacyAndRawHistory(conversation, raw);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid stored conversation history", error);
        }
    }

    /**
     * 切换到指定会话
     * 更新 Redis current_conversation，并将 MySQL 中的历史加载到 Redis
     *
     * @param username       用户名
     * @param conversationId 要切换到的会话UUID
     * @return 会话的聊天历史消息列表
     */
    public List<Map<String, String>> switchConversation(String username, String conversationId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));

        Conversation conversation = conversationRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new CustomException("会话不存在", HttpStatus.NOT_FOUND));

        // 权限校验
        if (!conversation.getUser().getId().equals(user.getId())) {
            throw new CustomException("无权访问此会话", HttpStatus.FORBIDDEN);
        }

        // 更新 Redis current_conversation
        String redisKey = "user:" + username + ":current_conversation";
        redisTemplate.opsForValue().set(redisKey, conversationId, Duration.ofDays(7));

        // 用 version CAS 重建工作集；冲突时重新读数据库，避免覆盖并发 append/compress。
        try {
            for (int attempt = 1; attempt <= 3; attempt++) {
                long expectedVersion = compressionService.getWorkingSetVersion(conversationId);
                List<Map<String, String>> rawMessages = conversationMessageService.getRawHistory(conversationId);
                List<Map<String, String>> messages = mergeLegacyAndRawHistory(conversation, rawMessages);
                if (compressionService.replaceWorkingSet(conversationId, expectedVersion, messages)) {
                    return messages;
                }
                logger.info("重建会话工作集发生版本冲突，准备重试: conversationId={}, attempt={}",
                        conversationId, attempt);
            }
            throw new CustomException("会话正在更新，请稍后重试", HttpStatus.CONFLICT);
        } catch (JsonProcessingException e) {
            logger.error("解析会话历史出错: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, String>> mergeLegacyAndRawHistory(
            Conversation conversation,
            List<Map<String, String>> rawMessages) throws JsonProcessingException {
        List<Map<String, String>> merged = new ArrayList<>();
        String legacyJson = conversation.getMessages();
        if (legacyJson != null && !legacyJson.isBlank()) {
            merged.addAll(objectMapper.readValue(
                    legacyJson, new TypeReference<List<Map<String, String>>>() {}));
        }
        merged.addAll(rawMessages);
        return merged;
    }

    /**
     * 删除会话
     * 删除 MySQL + Redis 中的会话数据
     *
     * @param username       用户名
     * @param conversationId 会话UUID
     */
    @Transactional
    public void deleteConversation(String username, String conversationId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));

        Conversation conversation = conversationRepository.findByConversationId(conversationId)
                .orElseThrow(() -> new CustomException("会话不存在", HttpStatus.NOT_FOUND));

        // 权限校验
        if (!conversation.getUser().getId().equals(user.getId())) {
            throw new CustomException("无权删除此会话", HttpStatus.FORBIDDEN);
        }

        // 先删除追加式原始消息，再删除会话元数据。
        conversationMessageService.deleteRawHistory(conversationId);
        conversationRepository.deleteByConversationId(conversationId);

        // 工作集与版本号必须一起删除，避免残留版本影响后续诊断。
        String historyKey = "conversation:" + conversationId;
        redisTemplate.delete(List.of(historyKey, historyKey + ":version"));

        // 如果删除的是当前会话，清除 current_conversation
        String currentKey = "user:" + username + ":current_conversation";
        String currentConvId = redisTemplate.opsForValue().get(currentKey);
        if (conversationId.equals(currentConvId)) {
            redisTemplate.delete(currentKey);
        }

        logger.info("用户 {} 删除会话: {}", username, conversationId);
    }

    /**
     * 获取用户当前会话ID（从 Redis）
     */
    public String getCurrentConversationId(String username) {
        String key = "user:" + username + ":current_conversation";
        return redisTemplate.opsForValue().get(key);
    }
}
