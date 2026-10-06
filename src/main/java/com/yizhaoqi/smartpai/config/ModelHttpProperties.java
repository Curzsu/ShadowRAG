package com.yizhaoqi.smartpai.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "deepseek.api")
@Data
public class ModelHttpProperties {
    private int maxSseLineBytes = 1048576;
    private int maxSseFrameBytes = 1048576;
    private int maxStreamContentChars = 1048576;
    private int maxToolArgumentsChars = 262144;
    private int maxToolCallsPerRound = 16;
    private int maxReasoningChars = 1048576;
    private int maxJsonResponseBytes = 1048576;
    private int maxErrorResponseBytes = 16384;
    private long connectTimeoutMs = 10000;

    @PostConstruct
    public void validate() {
        if (maxSseLineBytes <= 0 || maxSseFrameBytes <= 0 || maxStreamContentChars <= 0
                || maxToolArgumentsChars <= 0 || maxJsonResponseBytes <= 0 || maxErrorResponseBytes <= 0
                || maxToolCallsPerRound <= 0 || maxToolCallsPerRound > 64 || maxReasoningChars <= 0
                || connectTimeoutMs <= 0) throw new IllegalArgumentException("Model HTTP limits must be positive and bounded");
    }
}
