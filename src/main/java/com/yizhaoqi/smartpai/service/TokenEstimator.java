package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TokenEstimator {

    private static final int MESSAGE_OVERHEAD_TOKENS = 4;
    private static final Encoding TOKEN_ENCODING = Encodings.newDefaultEncodingRegistry()
            .getEncoding(EncodingType.CL100K_BASE);

    private final ObjectMapper objectMapper;

    public TokenEstimator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public int countText(String text) {
        return TOKEN_ENCODING.countTokens(text == null ? "" : text);
    }

    public int countMessages(List<? extends Map<String, ?>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return messages.stream().mapToInt(this::countMessage).sum();
    }

    private int countMessage(Map<String, ?> message) {
        try {
            return countText(objectMapper.writeValueAsString(message)) + MESSAGE_OVERHEAD_TOKENS;
        } catch (JsonProcessingException e) {
            return countText(String.valueOf(message)) + MESSAGE_OVERHEAD_TOKENS;
        }
    }
}
