package com.yizhaoqi.smartpai.service.chat;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChatGenerationResourcesTest {
    private ChatGenerationResources resources() {
        return new ChatGenerationResources(System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
    }

    @Test void stopClosesCurrentBodyAndCancelsPendingRequest() {
        var resources = resources();
        var body = new CountingBody();
        var request = new CompletableFuture<>();
        var worker = new FutureTask<>(() -> null);
        resources.attachGenerationTask(worker);
        resources.attachHttpRequest(request);
        resources.attachResponseBody(body);
        resources.stop(); resources.stop();
        assertEquals(1, body.closed.get());
        assertTrue(request.isCancelled());
        assertTrue(worker.isCancelled());
        assertThrows(CancellationException.class, resources::checkRunning);
    }

    @Test void lateBodyAfterStopIsClosed() throws Exception {
        var resources = resources();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var body = new CountingBody();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var attach = pool.submit(() -> { entered.countDown(); release.await(); resources.attachResponseBody(body); return null; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            resources.stop(); release.countDown(); attach.get(2, TimeUnit.SECONDS);
            assertEquals(1, body.closed.get());
            var late = new CompletableFuture<>();
            resources.attachHttpRequest(late);
            assertTrue(late.isCancelled());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void clearingPreviousBodyDoesNotClearNextBody() {
        var resources = resources();
        var first = new CountingBody(); var second = new CountingBody();
        resources.attachResponseBody(first);
        resources.attachResponseBody(second);
        resources.clearResponseBody(first);
        resources.stop();
        assertEquals(1, first.closed.get()); assertEquals(1, second.closed.get());
    }

    @Test void clearingPreviousRequestDoesNotClearNextRequest() {
        var resources = resources();
        var first = new CompletableFuture<>(); var second = new CompletableFuture<>();
        resources.attachHttpRequest(first); resources.attachHttpRequest(second);
        resources.clearHttpRequest(first); resources.stop();
        assertTrue(second.isCancelled());
    }

    @Test void normalFinishDoesNotInterruptWorker() {
        var resources = resources(); var task = new FutureTask<>(() -> null);
        var body = new CountingBody();
        resources.attachGenerationTask(task); resources.attachResponseBody(body);
        resources.finishNormally(); resources.stop();
        assertFalse(task.isCancelled()); assertEquals(1, body.closed.get());
    }

    @Test void cumulativeContentLimitCoversAllRounds() {
        var resources = resources();
        resources.addContentCharacters(524288, 1048576);
        resources.addContentCharacters(524288, 1048576);
        assertThrows(IllegalStateException.class, () -> resources.addContentCharacters(1, 1048576));
    }

    @Test void expiredDeadlineFailsWithoutStartingAnotherRequest() {
        var resources = new ChatGenerationResources(System.nanoTime() - 1);
        assertTrue(resources.remaining().isZero());
        assertThrows(HttpTimeoutException.class, resources::checkRunning);
    }

    private static class CountingBody extends ByteArrayInputStream {
        final AtomicInteger closed = new AtomicInteger();
        CountingBody() { super(new byte[0]); }
        @Override public void close() { closed.incrementAndGet(); }
    }
}
