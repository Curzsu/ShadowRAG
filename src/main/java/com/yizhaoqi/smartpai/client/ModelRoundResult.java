package com.yizhaoqi.smartpai.client;

/** One fully completed provider round; persistence belongs to the chat stream owner. */
public record ModelRoundResult(String content, String reasoningContent,
                               java.util.List<ModelToolCall> toolCalls, String finishReason) {
    public ModelRoundResult { toolCalls = java.util.List.copyOf(toolCalls); }
}
