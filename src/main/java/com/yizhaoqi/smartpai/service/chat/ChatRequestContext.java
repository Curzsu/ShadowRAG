package com.yizhaoqi.smartpai.service.chat;

import com.yizhaoqi.smartpai.model.chat.ChatCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class ChatRequestContext implements Disposable {
    private static final Logger log = LoggerFactory.getLogger(ChatRequestContext.class);

    public enum State {
        REGISTERED, RUNNING, COMPLETING, FINISHED, CANCELLED, FAILED, TIMED_OUT;

        public boolean isTerminal() {
            return this == FINISHED || this == CANCELLED || this == FAILED || this == TIMED_OUT;
        }
    }

    private final ChatCommand command;
    private final AtomicReference<State> state = new AtomicReference<>(State.REGISTERED);
    private final Object resourceLock = new Object();
    private Disposable upstream;
    private final List<Runnable> cancellationCallbacks = new ArrayList<>();
    private boolean disposed;

    public ChatRequestContext(ChatCommand command) {
        this.command = Objects.requireNonNull(command);
    }

    public ChatCommand command() { return command; }
    public State state() { return state.get(); }
    public boolean isTerminal() { return state().isTerminal(); }

    /** State arbitration has no callbacks, so callers may use it in a short registry lock. */
    public boolean tryTransition(State expected, State next) {
        if (!isAllowed(expected, next)) return false;
        return state.compareAndSet(expected, next);
    }

    static boolean isAllowed(State expected, State next) {
        if (expected == null || next == null) return false;
        return switch (expected) {
            case REGISTERED -> next == State.RUNNING || next == State.CANCELLED
                    || next == State.FAILED || next == State.TIMED_OUT;
            case RUNNING -> next == State.COMPLETING || next == State.CANCELLED
                    || next == State.FAILED || next == State.TIMED_OUT;
            case COMPLETING -> next == State.FINISHED || next == State.FAILED;
            default -> false;
        };
    }

    public void attachUpstream(Disposable disposable) {
        Objects.requireNonNull(disposable);
        Disposable previous;
        synchronized (resourceLock) {
            if (disposed || isTerminal()) {
                previous = disposable;
            } else {
                previous = upstream;
                upstream = disposable;
            }
        }
        if (previous != null) safelyRun(previous::dispose);
    }

    public void onCancel(Runnable callback) {
        Objects.requireNonNull(callback);
        boolean notify;
        synchronized (resourceLock) {
            State snapshot = state();
            notify = snapshot == State.CANCELLED;
            // A later cancellation must hand off the queued callback to dispose under this lock.
            if (!notify && !disposed && !snapshot.isTerminal()) cancellationCallbacks.add(callback);
        }
        if (notify) safelyRun(callback);
    }

    /** Detaches all handles before invoking user/resource code, and invokes each once. */
    @Override
    public void dispose() {
        Disposable disposable;
        List<Runnable> callbacks;
        synchronized (resourceLock) {
            if (disposed) return;
            disposed = true;
            disposable = upstream;
            upstream = null;
            callbacks = state() == State.CANCELLED ? List.copyOf(cancellationCallbacks) : List.of();
            cancellationCallbacks.clear();
        }
        if (disposable != null) safelyRun(disposable::dispose);
        callbacks.forEach(ChatRequestContext::safelyRun);
    }

    @Override
    public boolean isDisposed() {
        synchronized (resourceLock) { return disposed; }
    }

    private static void safelyRun(Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException error) {
            // Do not log upstream exception messages, payloads or credentials.
            log.warn("Chat resource cleanup failed: {}", error.getClass().getSimpleName());
        }
    }
}
