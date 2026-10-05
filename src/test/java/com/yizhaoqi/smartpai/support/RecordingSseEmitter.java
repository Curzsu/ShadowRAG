package com.yizhaoqi.smartpai.support;

import com.yizhaoqi.smartpai.model.chat.ChatEventEnvelope;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Captures envelopes and supplies deterministic downstream failure/slow-consumer controls. */
public class RecordingSseEmitter extends SseEmitter {
    private final List<ChatEventEnvelope> events = new CopyOnWriteArrayList<>();
    private final CountDownLatch terminal = new CountDownLatch(1);
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicInteger heartbeats = new AtomicInteger();
    private Runnable completionCallback;

    public volatile int failAtWrite = Integer.MAX_VALUE;
    public volatile int runtimeFailureAtWrite = Integer.MAX_VALUE;
    public volatile CountDownLatch blockSend;
    public volatile CountDownLatch enteredSend;

    public RecordingSseEmitter(long timeout) {
        super(timeout);
    }

    @Override
    public void send(SseEventBuilder event) throws IOException {
        int write = writes.incrementAndGet();
        if (write >= runtimeFailureAtWrite) throw new IllegalStateException("dedicated runtime disconnect");
        if (enteredSend != null) enteredSend.countDown();
        if (blockSend != null) {
            try {
                if (!blockSend.await(5, TimeUnit.SECONDS)) throw new IOException("test blocked sender");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            }
        }
        if (write >= failAtWrite) throw new IOException("dedicated test disconnect");
        boolean business = false;
        for (var part : event.build()) {
            if (part.getData() instanceof ChatEventEnvelope envelope) {
                events.add(envelope);
                business = true;
            }
        }
        if (!business) heartbeats.incrementAndGet();
    }

    @Override
    public void onCompletion(Runnable callback) {
        completionCallback = callback;
    }

    @Override
    public void complete() {
        terminal.countDown();
        if (completionCallback != null) completionCallback.run();
    }

    public void disconnect() {
        if (completionCallback != null) completionCallback.run();
    }

    public void awaitTerminal() throws InterruptedException {
        if (!terminal.await(5, TimeUnit.SECONDS)) throw new AssertionError("No terminal within five seconds");
    }

    public List<ChatEventEnvelope> events() {
        return List.copyOf(events);
    }

    public List<ChatEventEnvelope> eventsOfType(String type) {
        return events.stream().filter(event -> event.type().equals(type)).toList();
    }

    public int writes() { return writes.get(); }
    public int heartbeats() { return heartbeats.get(); }
}
