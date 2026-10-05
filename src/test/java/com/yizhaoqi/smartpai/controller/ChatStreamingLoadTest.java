package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.chat.ChatStreamingResourceProbe;
import com.yizhaoqi.smartpai.support.ChatStreamingAcceptanceFixture;
import com.yizhaoqi.smartpai.support.ChatStreamingAcceptanceSupport;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ChatStreamingAcceptanceFixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"server.port=${chat.acceptance.backend-port:0}",
                "jwt.secret-key=" + ChatStreamingAcceptanceFixture.SECRET,
                "logging.level.com.yizhaoqi.smartpai=ERROR"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatStreamingLoadTest extends ChatStreamingAcceptanceSupport {
    // Regression caught: delayed/coalesced body, crossed request routing, duplicate persistence, leaked subscriptions.
    @Test @Order(1) @Timeout(90)
    void fiftyConcurrentRealModelStreamsHaveIsolatedPacedBodiesAndBoundedFirstText() throws Exception {
        for (int i = 0; i < 3; i++) answer(directUrl(), "acceptance-warmup", UUID.randomUUID().toString(), "short");
        awaitEmpty();
        AtomicInteger peakRequests = new AtomicInteger(), peakBuffers = new AtomicInteger(), peakTimers = new AtomicInteger();
        ScheduledExecutorService observer = Executors.newSingleThreadScheduledExecutor();
        var sample = observer.scheduleAtFixedRate(() -> {
            peakRequests.accumulateAndGet(registry.activeRequestCount(), Math::max);
            peakBuffers.accumulateAndGet(ChatStreamingResourceProbe.pendingEvents(streams), Math::max);
            peakTimers.accumulateAndGet(((ScheduledThreadPoolExecutor) timerService).getQueue().size(), Math::max);
        }, 0, 5, TimeUnit.MILLISECONDS);
        List<Answer> answers;
        try { answers = concurrentAnswers(directUrl(), 50, "load"); }
        finally { sample.cancel(false); observer.shutdownNow(); }
        for (Answer answer : answers) {
            assertEquals(200, answer.chunkMillis().size());
            assertEquals(expectedText(answer.marker(), 200), answer.text(), "No cross-request text");
            assertTrue(answer.chunkMillis().get(199) - answer.chunkMillis().get(0) >= 3000,
                    "Paced model must be observed before its last frame");
        }
        assertEquals(50, peakRequests.get(), "This must be 50 simultaneous requests, not serial requests");
        assertTrue(model.peakActiveConnections() >= 50, "All 50 upstream HTTP sockets overlap");
        assertTrue(state.peakSubscriptions.get() >= 50);
        assertTrue(p95(answers) <= 1000, "Direct first-body p95 was " + p95(answers) + "ms");
        assertTrue(peakBuffers.get() <= 50 * 64);
        assertTrue(peakTimers.get() <= 101);
        awaitEmpty();
        assertTrue(workers.getLargestPoolSize() <= 16);
        assertTrue(((ScheduledThreadPoolExecutor) timerService).getPoolSize() <= 2);
        saveMetrics("task-7-load-metrics", Map.of("concurrentRequests", 50, "chunksPerRequest", 200,
                "upstreamIntervalMillis", 20, "firstTextP95Millis", p95(answers),
                "firstTextMillis", answers.stream().map(Answer::firstTextMillis).sorted().toList(),
                "peakActiveRequests", peakRequests.get(), "peakModelConnections", model.peakActiveConnections(),
                "peakPendingEvents", peakBuffers.get(), "peakTimers", peakTimers.get(), "settledResources", resourceMetrics()));
    }

    // Regression caught: each completed request retaining timers/buffers/leases or terminal records past TTL.
    @Test @Order(2) @Timeout(120)
    void tenUsersOneThousandSerialRequestsReleaseEveryResourceAndExpireTerminalRecords() throws Exception {
        awaitEmpty();
        clock.advance(properties.getTerminalRetentionMs());
        registry.purgeExpired();
        assertEquals(0, registry.retainedRequestCount());
        int persistedBefore = state.persisted.size(), modelBefore = model.requests();
        int peakTimers = 0, peakBuffers = 0;
        for (int i = 0; i < 1000; i++) {
            Answer answer = answer(directUrl(), "acceptance-user-" + (i % 10), UUID.randomUUID().toString(), "short");
            assertEquals(1, answer.chunkMillis().size());
            assertEquals(expectedText(answer.marker(), 1), answer.text());
            peakTimers = Math.max(peakTimers, ((ScheduledThreadPoolExecutor) timerService).getQueue().size());
            peakBuffers = Math.max(peakBuffers, ChatStreamingResourceProbe.pendingEvents(streams));
            if (i % 100 == 99) {
                awaitEmpty();
                assertEquals(i + 1, registry.retainedRequestCount());
                assertTrue(workers.getPoolSize() <= 16);
                assertTrue(((ScheduledThreadPoolExecutor) timerService).getPoolSize() <= 2);
                assertEquals(0, workers.getQueue().size());
            }
        }
        awaitEmpty();
        assertEquals(1000, model.requests() - modelBefore);
        assertEquals(1000, state.persisted.size() - persistedBefore);
        assertEquals(1000, registry.retainedRequestCount());
        assertTrue(peakTimers <= 3, "No accumulation from old serial requests");
        assertTrue(peakBuffers <= 64);
        Map<String, Object> retained = resourceMetrics();
        clock.advance(properties.getTerminalRetentionMs() - 1);
        registry.purgeExpired();
        assertEquals(1000, registry.retainedRequestCount(), "Retention lasts the entire TTL");
        clock.advance(1);
        registry.purgeExpired();
        assertEquals(0, registry.totalRequestCount());
        saveMetrics("task-7-resource-metrics", Map.of("users", 10, "serialRequests", 1000,
                "modelRequests", model.requests() - modelBefore, "persistedTurns", state.persisted.size() - persistedBefore,
                "peakTimersAfterResponse", peakTimers, "peakPendingAfterResponse", peakBuffers,
                "beforeTtl", retained, "afterTtl", resourceMetrics()));
    }
}
