package com.yizhaoqi.smartpai.service.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.InputStream;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/** Request-owned handles. Network cleanup always runs outside the short handle lock. */
public final class ChatGenerationResources {
    private static final Logger log = LoggerFactory.getLogger(ChatGenerationResources.class);
    private final Object lock = new Object();
    private final long deadlineNanos;
    private Future<?> generationTask;
    private CompletableFuture<?> httpRequest;
    private InputStream responseBody;
    private boolean stopped;
    private boolean finished;
    private long contentCharacters;

    public ChatGenerationResources(long deadlineNanos) { this.deadlineNanos = deadlineNanos; }

    public void attachGenerationTask(Future<?> task) {
        Objects.requireNonNull(task);
        Future<?> previous;
        synchronized (lock) {
            previous = stopped || finished ? task : generationTask;
            if (!stopped && !finished) generationTask = task;
        }
        if (previous != null) previous.cancel(true);
    }

    public void attachHttpRequest(CompletableFuture<?> request) {
        Objects.requireNonNull(request);
        CompletableFuture<?> previous;
        synchronized (lock) {
            previous = stopped || finished ? request : httpRequest;
            if (!stopped && !finished) httpRequest = request;
        }
        if (previous != null) previous.cancel(true);
    }

    public void attachResponseBody(InputStream body) {
        Objects.requireNonNull(body);
        InputStream previous;
        synchronized (lock) {
            previous = stopped || finished ? body : responseBody;
            if (!stopped && !finished) responseBody = body;
        }
        close(previous);
    }

    public void clearHttpRequest(CompletableFuture<?> expected) {
        synchronized (lock) { if (httpRequest == expected) httpRequest = null; }
    }

    public void clearResponseBody(InputStream expected) {
        synchronized (lock) { if (responseBody == expected) responseBody = null; }
    }

    public Duration remaining() { return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime())); }

    public void checkRunning() throws HttpTimeoutException {
        if (isStopped() || Thread.currentThread().isInterrupted()) throw new CancellationException("Chat generation stopped");
        if (remaining().isZero()) throw new HttpTimeoutException("Chat generation deadline exceeded");
    }

    public void addContentCharacters(int count, int maximum) {
        synchronized (lock) {
            if (count < 0 || maximum <= 0 || contentCharacters > maximum - (long) count)
                throw new IllegalStateException("Model content limit exceeded");
            contentCharacters += count;
        }
    }

    public void stop() { release(true); }
    public void finishNormally() { release(false); }
    public boolean isStopped() { synchronized (lock) { return stopped; } }

    private void release(boolean cancelWorker) {
        Future<?> task; CompletableFuture<?> request; InputStream body;
        synchronized (lock) {
            if (stopped || finished) return;
            stopped = cancelWorker; finished = !cancelWorker;
            task = generationTask; generationTask = null;
            request = httpRequest; httpRequest = null;
            body = responseBody; responseBody = null;
        }
        if (request != null) request.cancel(true);
        close(body);
        if (cancelWorker && task != null) task.cancel(true);
    }

    private static void close(InputStream body) {
        if (body == null) return;
        try { body.close(); }
        catch (IOException | RuntimeException error) { log.warn("Model body cleanup failed: {}", error.getClass().getSimpleName()); }
    }
}
