package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.Conversation;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.ConversationRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationServiceHistoryTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private UserRepository userRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private ConversationMessageService conversationMessageService;
    @Mock private ConversationCompressionService compressionService;

    private ConversationService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new ConversationService(
                conversationRepository,
                userRepository,
                redisTemplate,
                new ObjectMapper(),
                conversationMessageService,
                compressionService);
    }

    @Test
    void switchConversation_shouldMergeLegacySnapshotWithAppendOnlyRawHistoryAndCasRebuild() throws Exception {
        User user = new User();
        user.setId(7L);
        Conversation conversation = new Conversation();
        conversation.setConversationId("conv-1");
        conversation.setUser(user);
        conversation.setMessages("[{\"role\":\"assistant\",\"content\":\"legacy summary\"}]");
        List<Map<String, String>> rawHistory = List.of(
                Map.of("seq", "11", "role", "user", "content", "原始问题", "timestamp", "2026-08-19T18:00:00"),
                Map.of("seq", "12", "role", "assistant", "content", "原始回答", "timestamp", "2026-08-19T18:00:01")
        );
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(conversationRepository.findByConversationId("conv-1")).thenReturn(Optional.of(conversation));
        when(conversationMessageService.getRawHistory("conv-1")).thenReturn(rawHistory);
        when(compressionService.getWorkingSetVersion("conv-1")).thenReturn(7L);
        when(compressionService.replaceWorkingSet(eq("conv-1"), eq(7L), eq(List.of(
                Map.of("role", "assistant", "content", "legacy summary"),
                rawHistory.get(0), rawHistory.get(1))))).thenReturn(true);

        List<Map<String, String>> result = service.switchConversation("alice", "conv-1");

        assertEquals(List.of(
                Map.of("role", "assistant", "content", "legacy summary"),
                rawHistory.get(0), rawHistory.get(1)), result);
        verify(compressionService).replaceWorkingSet("conv-1", 7L, result);
    }

    @Test
    void switchConversation_shouldRetryDatabaseReadWhenWorkingSetVersionConflicts() {
        User user = new User();
        user.setId(7L);
        Conversation conversation = new Conversation();
        conversation.setConversationId("conv-1");
        conversation.setUser(user);
        conversation.setMessages("[]");
        List<Map<String, String>> firstRead = List.of(
                Map.of("seq", "11", "role", "user", "content", "old"));
        List<Map<String, String>> secondRead = List.of(
                firstRead.get(0), Map.of("seq", "12", "role", "assistant", "content", "new"));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(conversationRepository.findByConversationId("conv-1")).thenReturn(Optional.of(conversation));
        when(compressionService.getWorkingSetVersion("conv-1")).thenReturn(3L, 4L);
        when(conversationMessageService.getRawHistory("conv-1")).thenReturn(firstRead, secondRead);
        when(compressionService.replaceWorkingSet("conv-1", 3L, firstRead)).thenReturn(false);
        when(compressionService.replaceWorkingSet("conv-1", 4L, secondRead)).thenReturn(true);

        List<Map<String, String>> result = service.switchConversation("alice", "conv-1");

        assertEquals(secondRead, result);
        verify(conversationMessageService, org.mockito.Mockito.times(2)).getRawHistory("conv-1");
    }

    @Test
    void deleteConversation_shouldDeleteRawMessagesAndBothRedisWorkingSetKeys() {
        User user = new User();
        user.setId(7L);
        Conversation conversation = new Conversation();
        conversation.setConversationId("conv-1");
        conversation.setUser(user);
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(conversationRepository.findByConversationId("conv-1")).thenReturn(Optional.of(conversation));
        when(valueOperations.get("user:alice:current_conversation")).thenReturn("another-conversation");

        service.deleteConversation("alice", "conv-1");

        verify(conversationMessageService).deleteRawHistory("conv-1");
        verify(conversationRepository).deleteByConversationId("conv-1");
        verify(redisTemplate).delete(List.of("conversation:conv-1", "conversation:conv-1:version"));
    }
}
