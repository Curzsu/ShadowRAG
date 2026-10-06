package com.yizhaoqi.smartpai.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ChatStreamingPropertiesTest {
    @Test
    void defaultsMatchDesign() {
        ChatStreamingProperties properties = new ChatStreamingProperties();
        assertDoesNotThrow(properties::validate);
        assertEquals(300000, properties.getGenerationTimeoutMs());
        assertEquals(320000, properties.getEmitterTimeoutMs());
        assertEquals(15000, properties.getHeartbeatIntervalMs());
        assertEquals(300000, properties.getTerminalRetentionMs());
        assertEquals(100, properties.getMaxActiveRequests());
        assertEquals(10000, properties.getMaxRetainedRequests());
        assertEquals(200, properties.getMaxRetainedPerUser());
        assertEquals(16, properties.getWorkerThreads());
        assertEquals(1024, properties.getWorkerQueueCapacity());
        assertEquals(64, properties.getMaxPendingEvents());
    }

    @Test
    void invalidConfigurationIsRejected() {
        List<Consumer<ChatStreamingProperties>> invalid = List.of(
                p -> p.setGenerationTimeoutMs(0), p -> p.setEmitterTimeoutMs(-1),
                p -> p.setHeartbeatIntervalMs(0), p -> p.setTerminalRetentionMs(-1),
                p -> p.setMaxActiveRequests(0), p -> p.setMaxRetainedRequests(-1),
                p -> p.setMaxRetainedPerUser(0), p -> p.setWorkerThreads(-1),
                p -> p.setGenerationWorkerThreads(0), p -> p.setGenerationWorkerQueueCapacity(-1), p -> p.setWorkerQueueCapacity(0), p -> p.setMaxPendingEvents(-1),
                p -> p.setEmitterTimeoutMs(309999),
                p -> p.setHeartbeatIntervalMs(320000),
                p -> p.setMaxActiveRequests(10001));
        for (Consumer<ChatStreamingProperties> change : invalid) {
            ChatStreamingProperties properties = new ChatStreamingProperties();
            change.accept(properties);
            assertThrows(IllegalArgumentException.class, properties::validate);
        }
    }

    @Test
    void emitterReservesTenSecondPersistenceWindow() {
        ChatStreamingProperties properties = new ChatStreamingProperties();
        properties.setEmitterTimeoutMs(310000);
        assertDoesNotThrow(properties::validate);
        properties.setGenerationTimeoutMs(Long.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, properties::validate, "overflow must not bypass validation");
    }
}
