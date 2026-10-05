package com.yizhaoqi.smartpai.service.chat;

import com.yizhaoqi.smartpai.config.ChatStreamingProperties;
import com.yizhaoqi.smartpai.model.chat.ChatCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.yizhaoqi.smartpai.service.chat.ChatRequestContext.State;

/** Single-instance bounded request namespaces and conversation leases. */
@Component
public class ChatRequestRegistry {
    public record CancelResult(UUID requestId, State state, boolean changed) { }
    private record RequestKey(String username, UUID requestId) { }
    private record ConversationKey(String username, String conversationId) { }
    // This record deliberately holds no command, context, connection or generated text.
    private record Retained(State state, long expiresAt, boolean cancelledBeforeRegistration) { }

    private final Object lock = new Object();
    private final ChatStreamingProperties properties;
    private final Clock clock;
    private final Map<RequestKey, ChatRequestContext> active = new HashMap<>();
    private final Map<RequestKey, Retained> retained = new HashMap<>();
    private final Map<ConversationKey, ChatRequestContext> leases = new HashMap<>();
    private final Set<ChatRequestContext> finalizing = new HashSet<>();

    @Autowired
    public ChatRequestRegistry(ChatStreamingProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public ChatRequestRegistry(ChatStreamingProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties);
        this.clock = Objects.requireNonNull(clock);
        properties.validate();
    }

    public ChatRequestContext register(ChatCommand command) {
        requireValid(command);
        RequestKey requestKey = key(command);
        ConversationKey conversationKey = conversationKey(command);
        synchronized (lock) {
            purgeExpiredLocked();
            Retained previous = retained.get(requestKey);
            if (previous != null && previous.cancelledBeforeRegistration()) {
                throw error("REQUEST_CANCELLED", HttpStatus.CONFLICT, "请求已取消");
            }
            if (previous != null || active.containsKey(requestKey)) {
                throw error("REQUEST_DUPLICATE", HttpStatus.CONFLICT, "请求标识已使用");
            }
            if (leases.containsKey(conversationKey)) {
                throw error("CONVERSATION_BUSY", HttpStatus.CONFLICT, "当前会话正在生成回答");
            }
            requireCapacity(command.username(), true);
            ChatRequestContext context = new ChatRequestContext(command);
            active.put(requestKey, context);
            leases.put(conversationKey, context);
            return context;
        }
    }

    public CancelResult cancel(String username, UUID requestId) {
        requireIdentity(username, requestId);
        RequestKey requestKey = new RequestKey(username, requestId);
        ChatRequestContext cancelled;
        synchronized (lock) {
            purgeExpiredLocked();
            Retained previous = retained.get(requestKey);
            if (previous != null) return new CancelResult(requestId, previous.state(), false);
            cancelled = active.get(requestKey);
            if (cancelled == null) {
                requireCapacity(username, false);
                retained.put(requestKey, new Retained(State.CANCELLED, expiresAt(), true));
                return new CancelResult(requestId, State.CANCELLED, true);
            }
            while (true) {
                State state = cancelled.state();
                if (state != State.REGISTERED && state != State.RUNNING) {
                    return new CancelResult(requestId, state, false);
                }
                if (cancelled.tryTransition(state, State.CANCELLED)) break;
            }
        }
        // Both subscription disposal and callbacks can invoke arbitrary code; never hold lock.
        finish(cancelled, State.CANCELLED);
        return new CancelResult(requestId, State.CANCELLED, true);
    }

    public void finish(ChatRequestContext context, State terminal) {
        Objects.requireNonNull(context);
        if (terminal == null || !terminal.isTerminal()) {
            throw new IllegalArgumentException("finish requires a terminal state");
        }
        RequestKey requestKey = key(context.command());
        synchronized (lock) {
            if (active.get(requestKey) != context || finalizing.contains(context)) return;
            while (true) {
                State state = context.state();
                if (state == terminal) break;
                if (!ChatRequestContext.isAllowed(state, terminal)) return;
                if (context.tryTransition(state, terminal)) break;
            }
            // A second finish must not mistake disposal-started for cleanup-completed.
            finalizing.add(context);
        }
        try {
            context.dispose();
        } finally {
            synchronized (lock) {
                if (active.get(requestKey) == context) {
                    active.remove(requestKey);
                    leases.remove(conversationKey(context.command()), context);
                    retained.put(requestKey, new Retained(terminal, expiresAt(), false));
                }
                finalizing.remove(context);
            }
        }
    }

    public void purgeExpired() {
        synchronized (lock) { purgeExpiredLocked(); }
    }

    public int activeRequestCount() {
        synchronized (lock) { return active.size(); }
    }

    public int retainedRequestCount() {
        synchronized (lock) { return retained.size(); }
    }

    public int totalRequestCount() {
        synchronized (lock) { return active.size() + retained.size(); }
    }

    public int conversationLeaseCount() {
        synchronized (lock) { return leases.size(); }
    }

    public List<ChatRequestContext> activeSnapshot() {
        synchronized (lock) { return List.copyOf(active.values()); }
    }

    private void purgeExpiredLocked() {
        long now = clock.millis();
        retained.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private long expiresAt() {
        long now = clock.millis();
        long retention = properties.getTerminalRetentionMs();
        return now > Long.MAX_VALUE - retention ? Long.MAX_VALUE : now + retention;
    }

    private void requireCapacity(String username, boolean generation) {
        long userRecords = active.keySet().stream().filter(key -> key.username().equals(username)).count()
                + retained.keySet().stream().filter(key -> key.username().equals(username)).count();
        if ((generation && active.size() >= properties.getMaxActiveRequests())
                || active.size() + retained.size() >= properties.getMaxRetainedRequests()
                || userRecords >= properties.getMaxRetainedPerUser()) {
            throw error("CHAT_CAPACITY_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS, "聊天请求容量已满，请稍后重试");
        }
    }

    private static RequestKey key(ChatCommand command) {
        return new RequestKey(command.username(), command.requestId());
    }

    private static ConversationKey conversationKey(ChatCommand command) {
        return new ConversationKey(command.username(), command.conversationId());
    }

    private static void requireValid(ChatCommand command) {
        if (command == null) throw invalid();
        requireIdentity(command.username(), command.requestId());
        if (command.conversationId() == null || command.conversationId().isBlank()
                || command.message() == null || command.message().isBlank() || command.message().length() > 16000) {
            throw invalid();
        }
    }

    private static void requireIdentity(String username, UUID requestId) {
        if (username == null || username.isBlank() || requestId == null) throw invalid();
    }

    private static ChatRequestException invalid() {
        return error("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "聊天请求参数无效");
    }

    private static ChatRequestException error(String code, HttpStatus status, String message) {
        return new ChatRequestException(code, status, message);
    }
}
