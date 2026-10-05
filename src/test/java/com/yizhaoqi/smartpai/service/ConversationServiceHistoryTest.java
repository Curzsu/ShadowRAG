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
    void historyReadDoesNotSwitchGlobalConversation() {
        setupOwnedConversation();
        when(valueOperations.get("conversation:conv-1"))
                .thenReturn("[{\"role\":\"assistant\",\"content\":\"cached memory\"}]");
        assertEquals(List.of(Map.of("role", "assistant", "content", "cached memory")),
                service.loadHistoryForChat("alice", "conv-1"));
        verify(valueOperations, org.mockito.Mockito.never()).set(
                eq("user:alice:current_conversation"), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class));
        verify(conversationMessageService).getLatestSequenceId("conv-1");
        verify(conversationMessageService, org.mockito.Mockito.never()).getRawHistory("conv-1");
    }

    @Test
    void committedTurnPreservesCurrentSummaryWithoutRawReloadAndDeduplicatesRepeatedMaintenance() throws Exception {
        setupOwnedConversation();
        var summary = Map.of("type", "summary", "content", "memory", "sourceStartSeq", "1", "sourceEndSeq", "4");
        var appended = List.of(Map.of("seq", "11", "role", "user", "content", "question"),
                Map.of("seq", "14", "role", "assistant", "content", "answer"));
        var expected = List.of(summary, appended.get(0), appended.get(1));
        ObjectMapper mapper = new ObjectMapper();
        when(conversationMessageService.getLatestSequenceIdBefore("conv-1", 11L)).thenReturn(4L);
        when(valueOperations.get("conversation:conv-1"))
                .thenReturn(mapper.writeValueAsString(List.of(summary)), mapper.writeValueAsString(expected));
        when(compressionService.getWorkingSetVersion("conv-1")).thenReturn(7L, 8L);
        when(compressionService.replaceWorkingSet("conv-1", 7L, expected)).thenReturn(true);
        when(compressionService.replaceWorkingSet("conv-1", 8L, expected)).thenReturn(true);
        assertEquals(expected, service.cacheCommittedTurn("alice", "conv-1", appended));
        assertEquals(expected, service.cacheCommittedTurn("alice", "conv-1", appended));
        verify(conversationMessageService, org.mockito.Mockito.never()).getRawHistory("conv-1");
        var order = org.mockito.Mockito.inOrder(compressionService, valueOperations);
        order.verify(compressionService).getWorkingSetVersion("conv-1");
        order.verify(valueOperations).get("conversation:conv-1");
        order.verify(compressionService).replaceWorkingSet("conv-1", 7L, expected);
    }

    @Test
    void committedTurnRetriesChangedCacheSnapshotBeforeWriting() throws Exception {
        setupOwnedConversation();
        var old = Map.of("seq", "4", "role", "assistant", "content", "prior answer");
        var summary = Map.of("type", "summary", "content", "concurrent summary", "sourceEndSeq", "4");
        var appended = List.of(Map.of("seq", "11", "role", "user", "content", "question"),
                Map.of("seq", "14", "role", "assistant", "content", "answer"));
        var first = List.of(old, appended.get(0), appended.get(1));
        var second = List.of(summary, appended.get(0), appended.get(1));
        when(conversationMessageService.getLatestSequenceIdBefore("conv-1", 11L)).thenReturn(4L);
        ObjectMapper mapper = new ObjectMapper();
        when(valueOperations.get("conversation:conv-1"))
                .thenReturn(mapper.writeValueAsString(List.of(old)), mapper.writeValueAsString(List.of(summary)));
        when(compressionService.getWorkingSetVersion("conv-1")).thenReturn(7L, 8L);
        when(compressionService.replaceWorkingSet("conv-1", 7L, first)).thenReturn(false);
        when(compressionService.replaceWorkingSet("conv-1", 8L, second)).thenReturn(true);
        assertEquals(second, service.cacheCommittedTurn("alice", "conv-1", appended));
        verify(valueOperations, org.mockito.Mockito.times(2)).get("conversation:conv-1");
        verify(conversationMessageService, org.mockito.Mockito.never()).getRawHistory("conv-1");
    }

    @Test
    void committedTurnKeepsLegacyHistoryWhenThisIsTheFirstRawTurn() {
        setupOwnedConversation();
        var legacy = Map.of("role", "assistant", "content", "legacy memory");
        var appended = List.of(Map.of("seq", "11", "role", "user", "content", "question"),
                Map.of("seq", "14", "role", "assistant", "content", "answer"));
        var expected = List.of(legacy, appended.get(0), appended.get(1));
        when(valueOperations.get("conversation:conv-1"))
                .thenReturn("[{\"role\":\"assistant\",\"content\":\"legacy memory\"}]");
        when(compressionService.replaceWorkingSet("conv-1", 0L, expected)).thenReturn(true);
        assertEquals(expected, service.cacheCommittedTurn("alice", "conv-1", appended));
        verify(conversationMessageService).getLatestSequenceIdBefore("conv-1", 11L);
        verify(conversationMessageService, org.mockito.Mockito.never()).getRawHistory("conv-1");
    }

    @Test
    void priorDurableSequenceFailurePropagatesBeforeCacheAccess() {
        setupOwnedConversation();
        org.mockito.Mockito.reset(redisTemplate);
        var appended = List.of(Map.of("seq", "11", "role", "user", "content", "question"),
                Map.of("seq", "14", "role", "assistant", "content", "answer"));
        IllegalStateException failure = new IllegalStateException("database sequence unavailable");
        when(conversationMessageService.getLatestSequenceIdBefore("conv-1", 11L)).thenThrow(failure);
        org.junit.jupiter.api.Assertions.assertSame(failure,
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        () -> service.cacheCommittedTurn("alice", "conv-1", appended)));
        org.mockito.Mockito.verifyNoInteractions(redisTemplate, compressionService);
    }

    @Test
    void durableSequenceFailurePropagatesEvenWhenCacheCouldBeReadable() {
        setupOwnedConversation();
        org.mockito.Mockito.reset(redisTemplate);
        IllegalStateException failure = new IllegalStateException("database sequence unavailable");
        when(conversationMessageService.getLatestSequenceId("conv-1")).thenThrow(failure);
        org.junit.jupiter.api.Assertions.assertSame(failure,
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                        () -> service.loadHistoryForChat("alice", "conv-1")));
        org.mockito.Mockito.verifyNoInteractions(redisTemplate, compressionService);
    }

    @Test
    void legacyCacheWithoutSequencesRebuildsWhenRawTurnsExist() {
        setupOwnedConversation();
        when(valueOperations.get("conversation:conv-1"))
                .thenReturn("[{\"role\":\"assistant\",\"content\":\"legacy memory\"}]");
        when(conversationMessageService.getLatestSequenceId("conv-1")).thenReturn(12L);
        var raw = List.of(Map.of("seq", "11", "role", "user", "content", "question"),
                Map.of("seq", "12", "role", "assistant", "content", "answer"));
        when(conversationMessageService.getRawHistory("conv-1")).thenReturn(raw);
        when(compressionService.replaceWorkingSet(eq("conv-1"), eq(0L), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(true);
        assertEquals(List.of(Map.of("role", "assistant", "content", "legacy memory"), raw.get(0), raw.get(1)),
                service.loadHistoryForChat("alice", "conv-1"));
    }

    @Test
    void legacyPositionSummaryRemainsUsableWithoutRawTurns() {
        setupOwnedConversation();
        when(valueOperations.get("conversation:conv-1")).thenReturn(
                "[{\"type\":\"summary\",\"content\":\"legacy memory\",\"sourceStartSeq\":\"position:0\",\"sourceEndSeq\":\"position:3\"}]");
        assertEquals(List.of(Map.of("type", "summary", "content", "legacy memory",
                        "sourceStartSeq", "position:0", "sourceEndSeq", "position:3")),
                service.loadHistoryForChat("alice", "conv-1"));
        verify(conversationMessageService, org.mockito.Mockito.never()).getRawHistory("conv-1");
    }

    @Test
    void historyRedisFailureFallsBackToMergedMysqlHistory() {
        setupOwnedConversation();
        when(valueOperations.get("conversation:conv-1")).thenThrow(new IllegalStateException("cache unavailable"));
        List<Map<String, String>> raw = List.of(Map.of("seq", "11", "role", "user", "content", "raw question"));
        when(conversationMessageService.getRawHistory("conv-1")).thenReturn(raw);
        assertEquals(List.of(Map.of("role", "assistant", "content", "legacy memory"), raw.get(0)),
                service.loadHistoryForChat("alice", "conv-1"));
        org.mockito.Mockito.verifyNoInteractions(compressionService);
    }

    @Test
    void historyCorruptCacheAndFailedReplacementStillReturnMergedHistory() {
        setupOwnedConversation();
        when(valueOperations.get("conversation:conv-1")).thenReturn("not-json");
        when(conversationMessageService.getRawHistory("conv-1")).thenReturn(List.of());
        when(compressionService.getWorkingSetVersion("conv-1")).thenReturn(4L);
        when(compressionService.replaceWorkingSet(eq("conv-1"), eq(4L), org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new IllegalStateException("cache write unavailable"));
        assertEquals(List.of(Map.of("role", "assistant", "content", "legacy memory")),
                service.loadHistoryForChat("alice", "conv-1"));
    }

    @Test
    void historyDatabaseFailureIsNotHiddenAsEmptyHistory() {
        setupOwnedConversation();
        when(conversationMessageService.getRawHistory("conv-1")).thenThrow(new IllegalStateException("database unavailable"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.loadHistoryForChat("alice", "conv-1"));
    }

    @Test
    void chatHistoryRebuildRetriesCasWithFreshRawHistory() {
        setupOwnedConversation();
        var old = Map.of("seq", "11", "role", "user", "content", "old question");
        var recent = Map.of("seq", "12", "role", "assistant", "content", "recent answer");
        when(conversationMessageService.getRawHistory("conv-1")).thenReturn(List.of(old), List.of(old, recent));
        when(compressionService.getWorkingSetVersion("conv-1")).thenReturn(3L, 4L);
        when(compressionService.replaceWorkingSet(eq("conv-1"), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(false, true);
        assertEquals(List.of(Map.of("role", "assistant", "content", "legacy memory"), old, recent),
                service.loadHistoryForChat("alice", "conv-1"));
        verify(conversationMessageService, org.mockito.Mockito.times(2)).getRawHistory("conv-1");
    }

    private void setupOwnedConversation() {
        User user = new User(); user.setId(7L);
        Conversation conversation = new Conversation(); conversation.setConversationId("conv-1"); conversation.setUser(user);
        conversation.setMessages("[{\"role\":\"assistant\",\"content\":\"legacy memory\"}]");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(conversationRepository.findByConversationId("conv-1")).thenReturn(Optional.of(conversation));
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
