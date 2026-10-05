package com.yizhaoqi.smartpai.model.chat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.io.IOException;
import java.util.UUID;

public record ChatStreamRequest(
        @NotBlank String conversationId,
        @NotNull @JsonDeserialize(using = ChatStreamRequest.CanonicalUuidDeserializer.class) UUID requestId,
        @NotBlank @Size(max = 16000) String message) {

    /** Spring MVC may log this DTO even when validation rejects its fields. */
    @Override
    public String toString() {
        return "ChatStreamRequest[requestId=" + requestId
                + ", conversationIdLength=" + (conversationId == null ? 0 : conversationId.length())
                + ", messageLength=" + (message == null ? 0 : message.length()) + "]";
    }

    /** Keeps this HTTP contract strict without changing UUID decoding elsewhere. */
    public static final class CanonicalUuidDeserializer extends StdDeserializer<UUID> {
        public CanonicalUuidDeserializer() {
            super(UUID.class);
        }

        @Override
        public UUID deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String value = parser.hasToken(JsonToken.VALUE_STRING) ? parser.getText() : null;
            if (value != null && value.length() == 36) {
                try {
                    UUID parsed = UUID.fromString(value);
                    if (value.equalsIgnoreCase(parsed.toString())) return parsed;
                } catch (IllegalArgumentException ignored) {
                    // Report a mapping failure so the HTTP layer uses INVALID_REQUEST.
                }
            }
            throw context.weirdStringException(value, UUID.class, "requestId must use canonical UUID format");
        }
    }
}
