package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.CompressionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    @Mock private DeepSeekClient deepSeekClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private CompressionProperties config;

    private ConversationCompressionService service;

    @BeforeEach
    void setUp() {
        config = new CompressionProperties();
        config.setSoftThreshold(30);
        config.setKeepRounds(6);
        config.setHardThresholdToken(50000);
        config.setSummaryMarker("[历史摘要]");

        service = new ConversationCompressionService(
                compressionExecutor, config, stringRedisTemplate,
                compressScript, truncateScript, deepSeekClient, objectMapper
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
    void checkAndCompress_shouldNotTrigger_belowSoftThreshold() {
        List<Map<String, String>> history = List.of(
                Map.of("role", "user", "content", "hi")
        );
        assertDoesNotThrow(() -> service.checkAndCompress("conv1", history, "user1"));
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void checkAndCompress_shouldTriggerSyncTruncate_atHardThreshold() {
        // Create history with enough messages to exceed hard threshold
        // softThreshold=30, hardThresholdToken=50000
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

        assertDoesNotThrow(() -> service.checkAndCompress("conv1", history, "user1"));
        verify(stringRedisTemplate).execute(eq(truncateScript), anyList(), any(String.class));
    }
}
