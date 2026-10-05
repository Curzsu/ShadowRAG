package com.yizhaoqi.smartpai.model.chat;

import java.util.UUID;

/** Immutable command whose username is supplied by the authenticated principal. */
public record ChatCommand(String username, String conversationId, UUID requestId, String message) {
    public ChatCommand {
        if (conversationId != null && conversationId.length() == 36) {
            try {
                String canonical = UUID.fromString(conversationId).toString();
                if (canonical.equalsIgnoreCase(conversationId)) conversationId = canonical;
            } catch (IllegalArgumentException ignored) {
                // Invalid values remain unchanged for the HTTP layer's existing validation.
            }
        }
    }

    @Override
    public String toString() {
        return "ChatCommand[requestId=" + requestId
                + ", conversationIdLength=" + (conversationId == null ? 0 : conversationId.length())
                + ", messageLength=" + (message == null ? 0 : message.length()) + "]";
    }
}
