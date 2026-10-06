package com.yizhaoqi.smartpai.client;

/** One fully completed provider round; persistence belongs to the chat stream owner. */
public record ModelRoundResult(String content, String toolCallId, String toolArgumentsJson, String finishReason) { }
