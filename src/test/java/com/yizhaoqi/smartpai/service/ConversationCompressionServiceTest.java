package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.config.CompressionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationCompressionServiceTest {

    @Mock private ThreadPoolTaskExecutor compressionExecutor;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private RedisScript<Long> compressScript;
    @Mock private RedisScript<Long> truncateScript;
    @Mock private RedisScript<Long> appendMessagesScript;
    @Mock private RedisScript<Long> replaceWorkingSetScript;
    @Mock private DeepSeekClient deepSeekClient;
    @Mock private ValueOperations<String, String> valueOperations;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CompressionProperties config;
    private AiProperties aiProperties;

    private ConversationCompressionService service;

    @BeforeEach
    void setUp() {
        config = new CompressionProperties();
        config.setSoftThresholdToken(20000);
        config.setKeepRounds(6);
        config.setHardThresholdToken(50000);
        config.setSummaryMarker("[历史摘要]");
        aiProperties = new AiProperties();
        TokenEstimator tokenEstimator = new TokenEstimator(objectMapper);

        service = new ConversationCompressionService(
                compressionExecutor, config, stringRedisTemplate,
                compressScript, truncateScript, appendMessagesScript, replaceWorkingSetScript,
                deepSeekClient, objectMapper, tokenEstimator,
                new ContextBudgetService(aiProperties, tokenEstimator)
        );
    }

    @Test
    void estimateTokens_shouldReturnPositiveForNonEmptyHistory() {
        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "Hello world")
        );
        int tokens = service.estimateTokens(history);
        assertTrue(tokens > 0);
    }

    @Test
    void estimateTokens_shouldReturnZeroForEmptyHistory() {
        int tokens = service.estimateTokens(List.of());
        assertEquals(0, tokens);
    }

    @Test
    void checkAndCompress_shouldNotTrigger_belowSoftThresholdToken() {
        // 单条短消息，token 远低于软阈值（20000），不应触发任何压缩
        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "hi")
        );
        assertDoesNotThrow(() -> service.checkAndCompress("conv1", history));
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void checkAndCompress_shouldTriggerSyncTruncate_atHardThreshold() {
        // Create history with enough messages to exceed hard threshold
        // softThresholdToken=20000, hardThresholdToken=50000
        // CL100K_BASE tokenizer is very efficient on repeated chars:
        // ~254 tokens per message of "a".repeat(2000), so ~7600 tokens for 30 msgs.
        // Use 200 messages to comfortably exceed 50000 tokens.
        List<Map<String, String>> history = new ArrayList<>();
        String longContent = "a".repeat(2000);
        for (int i = 0; i < 200; i++) {
            history.add(Map.of("role", "user", "content", longContent));
        }

        // Mock Redis operations for syncTruncate
        when(stringRedisTemplate.execute(eq(truncateScript), anyList(), any(String.class)))
                .thenReturn(12L);

        assertDoesNotThrow(() -> service.checkAndCompress("conv1", history));
        verify(stringRedisTemplate).execute(eq(truncateScript), anyList(), any(String.class));
    }

    @Test
    void checkAndCompress_shouldTriggerAsync_whenTokensExceedSoftThresholdToken() {
        // 2 条长消息（条数少但 token 高），验证压缩只按 token 维度触发
        // content 足够长以超过 softThresholdToken=20000（约 24000 tokens）
        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "Hello world. ".repeat(8000)),
                Map.of("role", "assistant", "content", "Hello response. ".repeat(8000))
        );

        assertDoesNotThrow(() -> service.checkAndCompress("conv1", history));
        verify(compressionExecutor).execute(any(Runnable.class));
    }

    @Test
    void completedCompressionTask_shouldReleaseDedupSlotForTheNextCheck() {
        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "Hello world. ".repeat(8000)),
                Map.of("role", "assistant", "content", "Hello response. ".repeat(8000))
        );
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(compressionExecutor).execute(any(Runnable.class));

        service.checkAndCompress("conv1", history);
        service.checkAndCompress("conv1", history);

        verify(compressionExecutor, times(2)).execute(any(Runnable.class));
    }

    @Test
    void appendMessages_shouldAtomicallyAppendDatabaseSequencedMessagesAndVersion() {
        config.setHistoryTtlSeconds(604800);
        List<Map<String, String>> appended = List.of(
                Map.of("seq", "101", "role", "user", "content", "问题", "timestamp", "2026-08-19T18:00:00"),
                Map.of("seq", "102", "role", "assistant", "content", "回答", "timestamp", "2026-08-19T18:00:01")
        );
        when(stringRedisTemplate.execute(eq(appendMessagesScript), anyList(),
                any(String.class), any(String.class), any(String.class))).thenReturn(2L);

        long size = service.appendMessages("conv1", appended);

        assertEquals(2L, size);
        verify(stringRedisTemplate).execute(
                eq(appendMessagesScript),
                eq(List.of("conversation:conv1", "conversation:conv1:version")),
                eq("604800"),
                contains("\"seq\":\"101\""),
                contains("\"seq\":\"102\""));
    }

    @Test
    void replaceWorkingSet_shouldUseExpectedVersionAndAtomicTtlRefresh() {
        config.setHistoryTtlSeconds(604800);
        List<Map<String, String>> history = List.of(
                Map.of("seq", "11", "role", "user", "content", "问题")
        );
        when(stringRedisTemplate.execute(eq(replaceWorkingSetScript), anyList(),
                any(String.class), any(String.class), any(String.class))).thenReturn(8L);

        boolean replaced = service.replaceWorkingSet("conv1", 7L, history);

        assertTrue(replaced);
        verify(stringRedisTemplate).execute(
                eq(replaceWorkingSetScript),
                eq(List.of("conversation:conv1", "conversation:conv1:version")),
                eq("7"), eq("604800"), contains("\"seq\":\"11\""));
    }

    @Test
    void compressOnce_shouldDiscardStaleSummaryWhenRedisVersionChanged() throws Exception {
        config.setKeepRounds(1);
        List<Map<String, String>> history = List.of(
                Map.of("seq", "1", "role", "user", "content", "old question"),
                Map.of("seq", "2", "role", "assistant", "content", "old answer"),
                Map.of("seq", "3", "role", "user", "content", "recent question"),
                Map.of("seq", "4", "role", "assistant", "content", "recent answer")
        );
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("conversation:conv1")).thenReturn(objectMapper.writeValueAsString(history));
        when(valueOperations.get("conversation:conv1:version")).thenReturn("7");
        when(deepSeekClient.callSync(anyString(), any())).thenReturn("summary");
        when(stringRedisTemplate.execute(eq(compressScript), anyList(),
                any(String.class), any(String.class), any(String.class), any(String.class)))
                .thenReturn(-2L);

        ConversationCompressionService.CompressionOutcome outcome = service.compressOnce("conv1");

        assertEquals(ConversationCompressionService.CompressionOutcome.VERSION_CONFLICT, outcome);
        verify(stringRedisTemplate).execute(
                eq(compressScript),
                eq(List.of("conversation:conv1", "conversation:conv1:version")),
                eq("0"), eq("2"), any(String.class), eq("7"));
    }

    @Test
    void compressOnce_shouldWriteStructuredUntrustedSummaryWithSourceRange() throws Exception {
        config.setKeepRounds(1);
        List<Map<String, String>> history = List.of(
                Map.of("type", "summary", "role", "assistant", "content", "previous summary",
                        "sourceStartSeq", "1", "sourceEndSeq", "2"),
                Map.of("seq", "3", "role", "user", "content", "old question"),
                Map.of("seq", "4", "role", "assistant", "content", "old answer"),
                Map.of("seq", "5", "role", "user", "content", "recent question"),
                Map.of("seq", "6", "role", "assistant", "content", "recent answer")
        );
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("conversation:conv1")).thenReturn(objectMapper.writeValueAsString(history));
        when(valueOperations.get("conversation:conv1:version")).thenReturn("8");
        when(deepSeekClient.callSync(contains("previous summary"), any())).thenReturn("new summary");
        ArgumentCaptor<String> summaryJson = ArgumentCaptor.forClass(String.class);
        when(stringRedisTemplate.execute(eq(compressScript), anyList(),
                any(String.class), any(String.class), summaryJson.capture(), any(String.class)))
                .thenReturn(4L);

        ConversationCompressionService.CompressionOutcome outcome = service.compressOnce("conv1");

        assertEquals(ConversationCompressionService.CompressionOutcome.COMPRESSED, outcome);
        Map<String, String> summary = objectMapper.readValue(summaryJson.getValue(), Map.class);
        assertEquals("summary", summary.get("type"));
        assertEquals("assistant", summary.get("role"));
        assertEquals("new summary", summary.get("content"));
        assertEquals("3", summary.get("sourceStartSeq"));
        assertEquals("4", summary.get("sourceEndSeq"));
        assertFalse(summary.get("content").startsWith(config.getSummaryMarker()));
    }

    @Test
    void compressOnce_shouldRejectOversizedSummaryPromptBeforeCallingModel() throws Exception {
        aiProperties.getContext().setWindowTokens(300);
        aiProperties.getContext().setSafetyMarginTokens(20);
        config.setSummaryOutputReserveTokens(50);
        config.setKeepRounds(1);
        List<Map<String, String>> history = List.of(
                Map.of("seq", "1", "role", "user", "content", "old question ".repeat(1000)),
                Map.of("seq", "2", "role", "assistant", "content", "old answer"),
                Map.of("seq", "3", "role", "user", "content", "recent question"),
                Map.of("seq", "4", "role", "assistant", "content", "recent answer")
        );
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("conversation:conv1:version")).thenReturn("9");
        when(valueOperations.get("conversation:conv1")).thenReturn(objectMapper.writeValueAsString(history));

        assertThrows(ContextWindowExceededException.class, () -> service.compressOnce("conv1"));

        verifyNoInteractions(deepSeekClient);
    }
}
