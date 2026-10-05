package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.Conversation;
import com.yizhaoqi.smartpai.model.ConversationMessage;
import com.yizhaoqi.smartpai.repository.ConversationMessageRepository;
import com.yizhaoqi.smartpai.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationMessageServiceTest {

    @Mock private ConversationMessageRepository messageRepository;
    @Mock private ConversationRepository conversationRepository;

    private ConversationMessageService service;

    @Test
    void durableSequenceReturnsZeroForLegacyOnlyConversationAndLatestRawIdOtherwise() {
        jakarta.persistence.EntityManager entityManager = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        jakarta.persistence.TypedQuery<Long> query = org.mockito.Mockito.mock(jakarta.persistence.TypedQuery.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", entityManager);
        when(entityManager.createQuery(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(Long.class)))
                .thenReturn(query);
        when(query.setParameter("conversationId", "conv-1")).thenReturn(query);
        when(query.getSingleResult()).thenReturn(null, 102L);
        assertEquals(0L, service.getLatestSequenceId("conv-1"));
        assertEquals(102L, service.getLatestSequenceId("conv-1"));
        org.mockito.Mockito.verifyNoInteractions(messageRepository, conversationRepository);
    }

    @BeforeEach
    void setUp() {
        service = new ConversationMessageService(messageRepository, conversationRepository);
    }

    @Test
    void priorDurableSequenceUsesConversationAndExclusiveUpperBoundWithoutAssumingAdjacentIds() {
        jakarta.persistence.EntityManager entityManager = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        jakarta.persistence.TypedQuery<Long> query = org.mockito.Mockito.mock(jakarta.persistence.TypedQuery.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", entityManager);
        when(entityManager.createQuery(org.mockito.ArgumentMatchers.contains("message.id < :exclusiveUpperBound"),
                org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(query);
        when(query.setParameter("conversationId", "conv-1")).thenReturn(query);
        when(query.setParameter("exclusiveUpperBound", 11L)).thenReturn(query);
        when(query.getSingleResult()).thenReturn(null, 4L);
        assertEquals(0L, service.getLatestSequenceIdBefore("conv-1", 11L));
        assertEquals(4L, service.getLatestSequenceIdBefore("conv-1", 11L));
        org.mockito.Mockito.verifyNoInteractions(messageRepository, conversationRepository);
    }

    @Test
    void completedTurnTransactionHasTenSecondLimit() throws Exception {
        var transaction = ConversationMessageService.class.getMethod("appendTurn", String.class, String.class,
                String.class, LocalDateTime.class).getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertEquals(10, transaction.timeout());
    }

    @Test
    void appendTurn_shouldPersistRawMessagesAndExposeDatabaseIdsAsSequence() {
        Conversation conversation = conversation("conv-1");
        when(conversationRepository.findByConversationIdForUpdate("conv-1")).thenReturn(Optional.of(conversation));
        when(messageRepository.saveAll(anyList())).thenAnswer(invocation -> {
            List<ConversationMessage> saved = invocation.getArgument(0);
            saved.get(0).setId(101L);
            saved.get(1).setId(102L);
            return saved;
        });
        LocalDateTime timestamp = LocalDateTime.parse("2026-08-19T18:00:00");

        List<Map<String, String>> result = service.appendTurn(
                "conv-1", "问题", "回答", timestamp);

        assertEquals(List.of("user", "assistant"), result.stream().map(m -> m.get("role")).toList());
        assertEquals(List.of("101", "102"), result.stream().map(m -> m.get("seq")).toList());
        assertEquals(List.of("问题", "回答"), result.stream().map(m -> m.get("content")).toList());

        ArgumentCaptor<List<ConversationMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageRepository).saveAll(captor.capture());
        assertSame(conversation, captor.getValue().get(0).getConversation());
        assertEquals(timestamp, captor.getValue().get(1).getCreatedAt());
    }

    @Test
    void appendTurn_shouldRejectMissingParentConversation() {
        when(conversationRepository.findByConversationIdForUpdate("conv-2")).thenReturn(Optional.empty());

        assertThrows(com.yizhaoqi.smartpai.exception.CustomException.class,
                () -> service.appendTurn("conv-2", "第一条问题", "第一条回答", LocalDateTime.now()));

        org.mockito.Mockito.verifyNoInteractions(messageRepository);
    }

    @Test
    void getRawHistory_shouldReturnAppendOnlyMessagesInRepositoryOrder() {
        Conversation conversation = conversation("conv-1");
        ConversationMessage first = rawMessage(11L, conversation, "user", "问题");
        ConversationMessage second = rawMessage(12L, conversation, "assistant", "回答");
        when(messageRepository.findByConversationConversationIdOrderByIdAsc("conv-1"))
                .thenReturn(List.of(first, second));

        List<Map<String, String>> result = service.getRawHistory("conv-1");

        assertEquals(List.of("11", "12"), result.stream().map(m -> m.get("seq")).toList());
        assertEquals(List.of("user", "assistant"), result.stream().map(m -> m.get("role")).toList());
        assertEquals(2, result.size());
    }

    private static Conversation conversation(String id) {
        Conversation conversation = new Conversation();
        conversation.setConversationId(id);
        conversation.setTitle("新对话");
        return conversation;
    }

    private static ConversationMessage rawMessage(long id, Conversation conversation, String role, String content) {
        ConversationMessage message = new ConversationMessage();
        message.setId(id);
        message.setConversation(conversation);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(LocalDateTime.parse("2026-08-19T18:00:00"));
        return message;
    }
}
