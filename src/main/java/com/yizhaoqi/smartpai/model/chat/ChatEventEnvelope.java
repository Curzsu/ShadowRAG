package com.yizhaoqi.smartpai.model.chat;

import java.util.Map;
import java.util.UUID;

public record ChatEventEnvelope(String type, UUID requestId, String conversationId, long seq,
                                Map<String, Object> data) {
    public ChatEventEnvelope {
        data = Map.copyOf(data);
    }
}
