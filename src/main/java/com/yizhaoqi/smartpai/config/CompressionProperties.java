package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ai.compression")
@Data
public class CompressionProperties {

    private int softThresholdToken = 20000;
    private int hardThresholdToken = 50000;
    private int keepRounds = 6;
    private int retryMax = 3;
    private int llmTimeoutSeconds = 30;
    private String summaryPrompt = "请将以下对话历史压缩为简洁摘要，保留关键信息。";
    private String summaryMarker = "[历史摘要]";
    private ThreadPoolConfig threadPool;

    @Data
    public static class ThreadPoolConfig {
        private int coreSize = 2;
        private int maxSize = 4;
        private int queueCapacity = 50;
        private String threadNamePrefix = "compression-";
    }
}
