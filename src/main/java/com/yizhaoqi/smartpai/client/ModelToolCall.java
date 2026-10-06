package com.yizhaoqi.smartpai.client;

/** A complete, supplier-identified call; arguments are validated by the tool executor. */
public record ModelToolCall(int index, String id, String name, String argumentsJson) { }
