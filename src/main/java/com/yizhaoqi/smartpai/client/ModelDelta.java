package com.yizhaoqi.smartpai.client;

/** Typed supplier deltas. Transport lifecycle belongs to the subscriber. */
public record ModelDelta(Kind kind, String value) {
    public enum Kind { CONTENT, TOOL_CALL_ID, TOOL_CALL_ARGUMENTS }
}
