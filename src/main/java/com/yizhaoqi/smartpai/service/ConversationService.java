package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
