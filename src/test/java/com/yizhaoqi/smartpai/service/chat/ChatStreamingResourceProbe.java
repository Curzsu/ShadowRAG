package com.yizhaoqi.smartpai.service.chat;

/** Test-only access to existing package-scoped lifecycle measurements. */
public final class ChatStreamingResourceProbe {
    private ChatStreamingResourceProbe() { }
    public static int streams(ChatStreamService service) { return service.activeStreamCount(); }
    public static int pendingEvents(ChatStreamService service) { return service.pendingEventCount(); }
}
