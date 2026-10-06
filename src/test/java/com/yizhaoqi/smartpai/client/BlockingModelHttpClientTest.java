package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.ModelHttpProperties;
import com.yizhaoqi.smartpai.service.chat.ChatGenerationResources;
import com.yizhaoqi.smartpai.support.MockModelSseServer;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BlockingModelHttpClientTest {
    MockModelSseServer server;
    ModelHttpProperties p;
    BlockingModelHttpClient client;
    final Map<String,Object> request = Map.of("model", "test", "stream", true, "messages", List.of());
    @BeforeEach void setup() throws Exception {
        server = new MockModelSseServer(); p = new ModelHttpProperties();
        client = new BlockingModelHttpClient(server.url(), "", "", p, new ObjectMapper());
    }
    @AfterEach void close() { server.close(); }
    ChatGenerationResources resources() { return new ChatGenerationResources(System.nanoTime()+TimeUnit.SECONDS.toNanos(5)); }

    @Test void contentArrivesBeforeDone() throws Exception {
        var first = new CountDownLatch(1); var release = new CountDownLatch(1);
        server.enqueue(s -> { s.content("中文😀"); s.pause(release); s.data("[DONE]"); });
        var outputs = new CopyOnWriteArrayList<ModelDelta>(); var pool = Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> client.stream(request, resources(), d -> { outputs.add(d); first.countDown(); }));
            assertTrue(first.await(2, TimeUnit.SECONDS)); assertFalse(result.isDone());
            assertEquals("中文😀", outputs.get(0).value()); release.countDown();
            assertEquals("中文😀", result.get(3, TimeUnit.SECONDS).content());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void missingDoneLengthMalformedAndProviderErrorsFailWithoutRetry() {
        for (String frame : List.of("{\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}",
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}", "broken",
                "{\"error\":{\"message\":\"private supplier detail\"}}")) {
            server.enqueue(s -> { s.data(frame); if (!frame.contains("partial")) s.data("[DONE]"); });
            assertThrows(IOException.class, () -> client.stream(request, resources(), ignored -> {}));
        }
        assertEquals(4, server.requests());
    }
    @Test void doneClosesConnectionWithoutWaitingForEof() throws Exception {
        server.enqueue(s -> { s.content("answer"); s.data("[DONE]"); s.probeUntilDisconnected(); });
        assertEquals("answer", client.stream(request, resources(), ignored -> {}).content());
        assertTrue(server.awaitDisconnect(Duration.ofSeconds(2)));
    }
    @Test void cancellingBodyClosesRealConnection() throws Exception {
        var resources = resources();
        server.enqueue(s -> { s.content("first"); s.probeUntilDisconnected(); });
        assertThrows(CancellationException.class, () -> client.stream(request, resources, d -> resources.stop()));
        assertTrue(server.awaitDisconnect(Duration.ofSeconds(2)));
    }
    @Test void cancellingBeforeHeadersNeverDeliversLateBody() throws Exception {
        var release = new CountDownLatch(1); var resources = resources();
        server.enqueueDelayedHeaders(release, s -> { s.content("late"); s.probeUntilDisconnected(); });
        var pool = Executors.newSingleThreadExecutor(); var outputs = new CopyOnWriteArrayList<ModelDelta>();
        try {
            var run = pool.submit(() -> assertThrows(CancellationException.class, () -> client.stream(request, resources, outputs::add)));
            long limit = System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while (server.requests()==0 && System.nanoTime()<limit) Thread.onSpinWait();
            assertEquals(1, server.requests()); resources.stop(); release.countDown(); run.get(3, TimeUnit.SECONDS);
            assertTrue(outputs.isEmpty()); assertTrue(server.awaitDisconnect(Duration.ofSeconds(2)));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void summaryPreservesV1PathAndOptionalAuthorization() throws Exception {
        try (var versioned = new MockModelSseServer("/v1/chat/completions")) {
            versioned.enqueueJson(200, s -> s.raw("{\"choices\":[{\"message\":{\"content\":\"summary\"}}]}"));
            var c = new BlockingModelHttpClient(versioned.url()+"/v1/", "", "", p, new ObjectMapper());
            assertTrue(c.postJson(Map.of("model", "test"), Duration.ofSeconds(2)).contains("summary"));
            assertEquals(1, versioned.requests());
        }
    }
    @Test void summaryLimitAppliesWithoutContentLength() {
        p.setMaxJsonResponseBytes(30);
        server.enqueueJson(200, s -> s.raw("x".repeat(100)));
        assertThrows(IOException.class, () -> client.postJson(Map.of("model", "test"), Duration.ofSeconds(2)));
    }
    @Test void silentSummaryAndStreamRespectOverallDeadline() throws Exception {
        server.enqueueJson(200, s -> s.pause(new CountDownLatch(1)));
        assertThrows(java.net.http.HttpTimeoutException.class, () -> client.postJson(Map.of("model","test"), Duration.ofMillis(500)));
        try (var streamServer = new MockModelSseServer()) {
            streamServer.enqueue(s -> s.pause(new CountDownLatch(1)));
            var streamClient = new BlockingModelHttpClient(streamServer.url(), "", "", p, new ObjectMapper());
            assertThrows(java.net.http.HttpTimeoutException.class, () -> streamClient.stream(request,
                    new ChatGenerationResources(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500)), ignored -> {}));
        }
    }
    @Test void toolArgumentsAndContentAreBounded() {
        p.setMaxToolArgumentsChars(8);
        server.enqueue(s -> { s.tool("call-1", "{\"query\":\"long\"}"); s.data("[DONE]"); });
        assertThrows(IOException.class, () -> client.stream(request, resources(), ignored -> {}));
        p.setMaxStreamContentChars(3);
        server.enqueue(s -> { s.content("1234"); s.data("[DONE]"); });
        assertThrows(IOException.class, () -> client.stream(request, resources(), ignored -> {}));
    }
    @Test void nonSuccessErrorBodyIsBoundedAndNeverExposed() {
        p.setMaxErrorResponseBytes(16);
        server.enqueue(503, s -> { s.raw("private-token".repeat(100)); s.probeUntilDisconnected(); });
        var error = assertThrows(IOException.class, () -> client.stream(request, resources(), ignored -> {}));
        assertFalse(error.getMessage().contains("private-token")); assertEquals(1, server.requests());
    }
}
