package com.yizhaoqi.smartpai.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "chat.streaming")
@Data
public class ChatStreamingProperties {
    private long generationTimeoutMs = 300000;
    private long emitterTimeoutMs = 320000;
    private long heartbeatIntervalMs = 15000;
    private long terminalRetentionMs = 300000;
    private int maxActiveRequests = 100;
    private int maxRetainedRequests = 10000;
    private int maxRetainedPerUser = 200;
    private int workerThreads = 16;
    private int workerQueueCapacity = 1024;
    private int generationWorkerThreads = 16;
    private int generationWorkerQueueCapacity = 64;
    private int maxPendingEvents = 64;

    /** Called after configuration binding and by explicitly constructed services/tests. */
    @PostConstruct
    public void validate() {
        if (generationTimeoutMs <= 0 || emitterTimeoutMs <= 0 || heartbeatIntervalMs <= 0
                || terminalRetentionMs <= 0 || maxActiveRequests <= 0 || maxRetainedRequests <= 0
                || maxRetainedPerUser <= 0 || workerThreads <= 0 || workerQueueCapacity <= 0
                || generationWorkerThreads <= 0 || generationWorkerQueueCapacity <= 0 || maxPendingEvents <= 0) {
            throw new IllegalArgumentException("Chat streaming limits must be positive");
        }
        // Subtraction avoids overflowing generationTimeoutMs + the transaction allowance.
        if (emitterTimeoutMs <= generationTimeoutMs || emitterTimeoutMs - generationTimeoutMs < 10000) {
            throw new IllegalArgumentException("Emitter timeout must allow ten seconds after generation");
        }
        if (heartbeatIntervalMs >= emitterTimeoutMs) {
            throw new IllegalArgumentException("Heartbeat interval must be shorter than emitter timeout");
        }
        if (maxActiveRequests > maxRetainedRequests) {
            throw new IllegalArgumentException("Active request capacity cannot exceed total record capacity");
        }
    }
}
