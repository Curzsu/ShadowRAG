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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ConversationService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationService.class);

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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

        // 将 MySQL 中的历史加载到 Redis
        String historyKey = "conversation:" + conversationId;
        if (conversation.getMessages() != null) {
            redisTemplate.opsForValue().set(historyKey, conversation.getMessages(), Duration.ofDays(7));
        }

        // 解析并返回历史消息
        try {
            String messages = conversation.getMessages();
            if (messages == null || messages.isEmpty()) {
                return List.of();
            }
            return objectMapper.readValue(messages, new TypeReference<List<Map<String, String>>>() {});
        } catch (JsonProcessingException e) {
            logger.error("解析会话历史出错: {}", e.getMessage());
            return List.of();
        }
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

        // 删除 MySQL
        conversationRepository.deleteByConversationId(conversationId);

        // 删除 Redis 历史数据
        redisTemplate.delete("conversation:" + conversationId);

        // 如果删除的是当前会话，清除 current_conversation
        String currentKey = "user:" + username + ":current_conversation";
        String currentConvId = redisTemplate.opsForValue().get(currentKey);
        if (conversationId.equals(currentConvId)) {
            redisTemplate.delete(currentKey);
        }

        logger.info("用户 {} 删除会话: {}", username, conversationId);
    }

    /**
     * 将 Redis 聊天历史同步到 MySQL
     * 由 ChatHandler 在每次对话完成后调用
     *
     * @param conversationId 会话UUID
     * @param username       用户名（用于在 MySQL 中找不到记录时创建）
     */
    public void syncToMySQL(String conversationId, String username) {
        try {
            // 从 Redis 读取最新历史
            String historyKey = "conversation:" + conversationId;
            String messagesJson = redisTemplate.opsForValue().get(historyKey);

            if (messagesJson == null) {
                return;
            }

            // 查找 MySQL 中的记录
            Conversation conversation = conversationRepository.findByConversationId(conversationId).orElse(null);

            if (conversation == null) {
                // MySQL 中没有记录，创建一条新的
                User user = userRepository.findByUsername(username).orElse(null);
                if (user == null) {
                    logger.warn("同步会话失败，用户不存在: {}", username);
                    return;
                }
                conversation = new Conversation();
                conversation.setConversationId(conversationId);
                conversation.setUser(user);
            }

            // 更新 messages 和 title
            conversation.setMessages(messagesJson);

            // 如果标题还是默认的"新对话"，尝试从第一条用户消息中提取标题
            if ("新对话".equals(conversation.getTitle()) || conversation.getTitle() == null) {
                try {
                    List<Map<String, String>> messages = objectMapper.readValue(messagesJson,
                            new TypeReference<List<Map<String, String>>>() {});
                    String firstUserMsg = messages.stream()
                            .filter(m -> "user".equals(m.get("role")))
                            .map(m -> m.get("content"))
                            .findFirst()
                            .orElse("新对话");
                    conversation.setTitle(firstUserMsg.length() > 20 ? firstUserMsg.substring(0, 20) + "..." : firstUserMsg);
                } catch (Exception e) {
                    logger.debug("提取会话标题失败: {}", e.getMessage());
                }
            }

            conversationRepository.save(conversation);
            logger.debug("同步会话 {} 到 MySQL 成功", conversationId);
        } catch (Exception e) {
            logger.error("同步会话 {} 到 MySQL 失败: {}", conversationId, e.getMessage(), e);
        }
    }

    /**
     * 获取用户当前会话ID（从 Redis）
     */
    public String getCurrentConversationId(String username) {
        String key = "user:" + username + ":current_conversation";
        return redisTemplate.opsForValue().get(key);
    }
}
