package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatHandlerHistoryTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private HybridSearchService searchService;
    @Mock private DeepSeekClient deepSeekClient;
    @Mock private ConversationCompressionService compressionService;
    @Mock private ConversationMessageService conversationMessageService;
    @Mock private ConversationService conversationService;

    private ChatHandler handler;
    private ObjectMapper objectMapper;
    private AiProperties aiProperties;
    private TokenEstimator tokenEstimator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aiProperties = new AiProperties();
        aiProperties.getPrompt().setRules("base rules");
        aiProperties.getContext().setWindowTokens(2000);
        aiProperties.getContext().setSafetyMarginTokens(50);
        aiProperties.getContext().setMaxSummarySegments(2);
        aiProperties.getGeneration().setMaxTokens(200);
        tokenEstimator = new TokenEstimator(objectMapper);
        handler = new ChatHandler(
                redisTemplate,
                searchService,
                deepSeekClient,
                objectMapper,
                aiProperties,
                compressionService,
                conversationMessageService,
                new ContextBudgetService(aiProperties, tokenEstimator),
                tokenEstimator,
                conversationService);
    }

    @Test
    void updateConversationHistory_shouldPersistRawTurnBeforeAtomicRedisAppend() throws Exception {
        List<Map<String, String>> appended = List.of(
                Map.of("seq", "101", "role", "user", "content", "问题", "timestamp", "2026-08-19T18:00:00"),
                Map.of("seq", "102", "role", "assistant", "content", "回答", "timestamp", "2026-08-19T18:00:01")
        );
        when(conversationMessageService.appendTurn(
                eq("conv-1"), eq("问题"), eq("回答"), any(LocalDateTime.class)))
                .thenReturn(appended);
        when(conversationService.cacheCommittedTurn("alice", "conv-1", appended)).thenReturn(appended);

        handler.updateConversationHistory("conv-1", "alice", "问题", "回答");

        InOrder order = inOrder(conversationMessageService, conversationService, compressionService);
        order.verify(conversationMessageService).appendTurn(
                eq("conv-1"), eq("问题"), eq("回答"), any(LocalDateTime.class));
        order.verify(conversationService).cacheCommittedTurn("alice", "conv-1", appended);
        order.verify(compressionService).checkAndCompress("conv-1", appended);
        verify(valueOperations, never()).set(eq("conversation:conv-1"), any(), any(java.time.Duration.class));
    }

    @Test
    void getConversationHistory_shouldReturnRedisHitWithoutDatabaseReload() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        List<Map<String, String>> cached = List.of(
                Map.of("seq", "11", "role", "user", "content", "cached question")
        );
        when(valueOperations.get("conversation:conv-1"))
                .thenReturn(objectMapper.writeValueAsString(cached));

        List<Map<String, String>> result = handler.getConversationHistory("conv-1", "alice");

        assertEquals(cached, result);
        verify(conversationService, never()).loadHistoryForChat("alice", "conv-1");
    }

    @Test
    void getConversationHistory_shouldReloadAndCasRebuildWhenRedisMisses() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("conversation:conv-1")).thenReturn(null);
        List<Map<String, String>> restored = List.of(
                Map.of("seq", "21", "role", "user", "content", "restored question"),
                Map.of("seq", "22", "role", "assistant", "content", "restored answer")
        );
        when(conversationService.loadHistoryForChat("alice", "conv-1")).thenReturn(restored);

        List<Map<String, String>> result = handler.getConversationHistory("conv-1", "alice");

        assertEquals(restored, result);
        verify(conversationService).loadHistoryForChat("alice", "conv-1");
    }

    @Test
    void getConversationHistory_shouldSupportEmptyNewConversationOnRedisMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("conversation:new-conv")).thenReturn(null);
        when(conversationService.loadHistoryForChat("alice", "new-conv")).thenReturn(List.of());

        List<Map<String, String>> result = handler.getConversationHistory("new-conv", "alice");

        assertTrue(result.isEmpty());
        verify(conversationService).loadHistoryForChat("alice", "new-conv");
    }

    @Test
    void getConversationHistory_shouldRepairMalformedRedisWorkingSetFromDatabase() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("conversation:conv-1")).thenReturn("not-json");
        List<Map<String, String>> restored = List.of(
                Map.of("seq", "31", "role", "user", "content", "database history")
        );
        when(conversationService.loadHistoryForChat("alice", "conv-1")).thenReturn(restored);

        List<Map<String, String>> result = handler.getConversationHistory("conv-1", "alice");

        assertEquals(restored, result);
        verify(conversationService).loadHistoryForChat("alice", "conv-1");
    }

    @Test
    void buildMessages_shouldTreatBoundedSummariesAsUntrustedMemoryAndKeepOneSystemMessage() {
        List<Map<String, String>> history = List.of(
                Map.of("type", "summary", "role", "assistant", "content", "oldest summary"),
                Map.of("role", "system", "content", "[历史摘要] legacy summary"),
                Map.of("type", "summary", "role", "assistant", "content", "latest summary"),
                Map.of("role", "user", "content", "recent question"),
                Map.of("role", "assistant", "content", "recent answer")
        );

        List<Map<String, Object>> messages = handler.buildMessagesForAgenticRAG(history, "current question");

        assertEquals(1, messages.stream().filter(m -> "system".equals(m.get("role"))).count());
        String systemContent = String.valueOf(messages.get(0).get("content"));
        assertTrue(systemContent.contains("非可信历史记忆"));
        assertTrue(systemContent.contains("legacy summary"));
        assertTrue(systemContent.contains("latest summary"));
        assertFalse(systemContent.contains("oldest summary"));
        assertEquals(List.of("system", "user", "assistant", "user"),
                messages.stream().map(m -> String.valueOf(m.get("role"))).toList());
    }

    @Test
    void buildMessages_shouldApplyBudgetBeforeFirstModelCall() {
        aiProperties.getContext().setWindowTokens(700);
        aiProperties.getContext().setSafetyMarginTokens(50);
        aiProperties.getGeneration().setMaxTokens(100);
        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "old question ".repeat(1000)),
                Map.of("role", "assistant", "content", "old answer")
        );

        List<Map<String, Object>> messages = handler.buildMessagesForAgenticRAG(history, "current question");

        assertEquals(List.of("system", "user"),
                messages.stream().map(m -> String.valueOf(m.get("role"))).toList());
        assertEquals("current question", messages.get(1).get("content"));
    }

    @Test
    void prepareToolResponseMessages_shouldTruncateRetrievalContextBeforeSecondModelCall() {
        aiProperties.getContext().setWindowTokens(500);
        aiProperties.getContext().setSafetyMarginTokens(50);
        aiProperties.getGeneration().setMaxTokens(100);
        List<Map<String, Object>> original = List.of(
                Map.of("role", "system", "content", "base rules"),
                Map.of("role", "user", "content", "current question")
        );

        List<Map<String, Object>> messages = handler.prepareToolResponseMessages(
                original, "call-1", "{\"query\":\"x\"}", "document result ".repeat(1000));

        assertEquals(List.of("system", "user", "assistant", "tool"),
                messages.stream().map(m -> String.valueOf(m.get("role"))).toList());
        assertTrue(String.valueOf(messages.get(3).get("content")).contains("已按上下文预算截断"));
        assertTrue(tokenEstimator.countMessages(messages) <= 350);
    }
}
