package com.yizhaoqi.smartpai.model.chat;

import java.util.Map;

/** Business output before the transport assigns its sequence number. */
public record ChatOutput(String type, Map<String, Object> data) {
    public ChatOutput {
        if (!"chunk".equals(type) && !"tool_progress".equals(type) && !"round_end".equals(type)) {
            throw new IllegalArgumentException("Unsupported chat output type");
        }
        data = Map.copyOf(data);
    }
}
