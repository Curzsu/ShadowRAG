package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.Conversation;
import com.yizhaoqi.smartpai.model.ConversationMessage;
import com.yizhaoqi.smartpai.repository.ConversationMessageRepository;
import com.yizhaoqi.smartpai.repository.ConversationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConversationMessageService {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final ConversationMessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    @PersistenceContext
    private EntityManager entityManager;

    public ConversationMessageService(ConversationMessageRepository messageRepository,
                                      ConversationRepository conversationRepository) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
    }

    @Transactional(timeout = 10)
    public List<Map<String, String>> appendTurn(String conversationId,
                                                 String userContent,
                                                 String assistantContent,
                                                 LocalDateTime timestamp) {
        Conversation conversation = conversationRepository.findByConversationIdForUpdate(conversationId)
                .orElseThrow(() -> new CustomException("会话不存在", HttpStatus.NOT_FOUND));
        if (conversation.getTitle() == null || "新对话".equals(conversation.getTitle())) {
            conversation.setTitle(toTitle(userContent));
        }
        conversation.setUpdatedAt(timestamp);
        conversationRepository.save(conversation);
        ConversationMessage userMessage = createMessage(conversation, "user", userContent, timestamp);
        ConversationMessage assistantMessage = createMessage(conversation, "assistant", assistantContent, timestamp);

        List<ConversationMessage> saved = messageRepository.saveAll(List.of(userMessage, assistantMessage));
        return saved.stream().map(this::toHistoryMap).toList();
    }

    @Transactional(readOnly = true)
    public long getLatestSequenceId(String conversationId) {
        Long latest = entityManager.createQuery(
                        "select max(message.id) from ConversationMessage message where message.conversation.conversationId = :conversationId",
                        Long.class)
                .setParameter("conversationId", conversationId)
                .getSingleResult();
        return latest == null ? 0L : latest;
    }

    @Transactional(readOnly = true)
    public long getLatestSequenceIdBefore(String conversationId, long exclusiveUpperBound) {
        Long latest = entityManager.createQuery(
                        "select max(message.id) from ConversationMessage message where message.conversation.conversationId = :conversationId and message.id < :exclusiveUpperBound",
                        Long.class)
                .setParameter("conversationId", conversationId)
                .setParameter("exclusiveUpperBound", exclusiveUpperBound)
                .getSingleResult();
        return latest == null ? 0L : latest;
    }

    @Transactional(readOnly = true)
    public List<Map<String, String>> getRawHistory(String conversationId) {
        return messageRepository.findByConversationConversationIdOrderByIdAsc(conversationId)
                .stream()
                .map(this::toHistoryMap)
                .toList();
    }

    @Transactional
    public void deleteRawHistory(String conversationId) {
        messageRepository.deleteByConversationConversationId(conversationId);
    }

    private ConversationMessage createMessage(Conversation conversation,
                                                String role,
                                                String content,
                                                LocalDateTime timestamp) {
        ConversationMessage message = new ConversationMessage();
        message.setConversation(conversation);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setCreatedAt(timestamp);
        return message;
    }

    private Map<String, String> toHistoryMap(ConversationMessage message) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("seq", String.valueOf(message.getId()));
        result.put("role", message.getRole());
        result.put("content", message.getContent());
        result.put("timestamp", message.getCreatedAt().format(TIMESTAMP_FORMAT));
        return result;
    }

    private String toTitle(String content) {
        String normalized = content == null || content.isBlank() ? "新对话" : content.trim();
        return normalized.length() > 20 ? normalized.substring(0, 20) + "..." : normalized;
    }
}
