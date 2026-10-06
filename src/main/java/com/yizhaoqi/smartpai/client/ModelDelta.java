package com.yizhaoqi.smartpai.client;

/** Typed supplier deltas delivered by ordinary callbacks; request resources own the transport. */
public record ModelDelta(Kind kind, String value, Integer toolCallIndex) {
    public ModelDelta(Kind kind, String value) { this(kind, value, kind == Kind.CONTENT ? null : 0); }
    public enum Kind { CONTENT, TOOL_CALL_ID, TOOL_CALL_ARGUMENTS }
}
