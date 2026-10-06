package com.yizhaoqi.smartpai.client;

/** Typed supplier deltas delivered by ordinary callbacks; request resources own the transport. */
public record ModelDelta(Kind kind, String value) {
    public enum Kind { CONTENT, TOOL_CALL_ID, TOOL_CALL_ARGUMENTS }
}
