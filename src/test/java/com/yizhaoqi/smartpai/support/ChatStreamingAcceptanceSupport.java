package com.yizhaoqi.smartpai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.ChatStreamingProperties;
import com.yizhaoqi.smartpai.service.chat.*;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP consumer and resource observations; never exposes credentials or new production endpoints. */
public abstract class ChatStreamingAcceptanceSupport {
    @LocalServerPort protected int port;
    @Autowired protected JwtUtils jwt;
    @Autowired protected ChatRequestRegistry registry;
    @Autowired protected ChatStreamService streams;
    @Autowired protected ChatStreamingProperties properties;
    @Autowired protected ChatStreamingAcceptanceFixture.State state;
    @Autowired protected ChatStreamingAcceptanceFixture.AdjustableClock clock;
    @Autowired protected MockModelSseServer model;
    @Autowired @Qualifier("chatStreamingExecutor") protected ThreadPoolExecutor workers;
    @Autowired @Qualifier("chatGenerationExecutor") protected ThreadPoolExecutor generators;
    @Autowired @Qualifier("chatStreamingTimers") protected ScheduledExecutorService timerService;
    protected final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1).build();
    protected final ObjectMapper mapper = new ObjectMapper();

    protected String directUrl() { return "http://127.0.0.1:" + port; }
    protected record Answer(String conversation, String marker, long firstTextMillis, List<Long> chunkMillis,
                            List<Long> heartbeats, String text, int completions) { }

    protected Answer answer(String base, String username, String conversation, String mode) throws Exception {
        String marker = mode + ":" + conversation;
        UUID requestId = UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/chat/stream"))
                .header("Authorization", "Bearer " + jwt.generateToken(username))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(180))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of(
                        "conversationId", conversation, "requestId", requestId, "message", marker)))).build();
        long start = System.nanoTime();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode(), "Accepted stream must return HTTP 200");
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
        List<Long> chunkTimes = new ArrayList<>(), heartbeats = new ArrayList<>();
        StringBuilder text = new StringBuilder(), data = new StringBuilder();
        String eventName = "";
        long sequence = 0;
        int completions = 0;
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                if (line.startsWith(":")) heartbeats.add(elapsed);
                if (line.startsWith("event:")) eventName = line.substring(6).trim();
                if (line.startsWith("data:")) data.append(line.substring(5).trim());
                if (!line.isEmpty() || data.isEmpty()) continue;
                JsonNode event = mapper.readTree(data.toString());
                data.setLength(0);
                assertEquals(requestId.toString(), event.path("requestId").asText(), "Request isolation");
                assertEquals(conversation, event.path("conversationId").asText(), "Conversation isolation");
                assertEquals(++sequence, event.path("seq").asLong(), "No duplicate/missing/out-of-order frame");
                assertEquals(eventName, event.path("type").asText());
                switch (eventName) {
                    case "meta" -> assertEquals(1, sequence);
                    case "chunk" -> {
                        assertEquals(0, completions, "No chunk after terminal");
                        text.append(event.path("data").path("chunk").asText());
                        chunkTimes.add(elapsed);
                    }
                    case "error" -> fail("Unexpected stream error: " + event.path("data").path("code").asText());
                    case "completion" -> {
                        completions++;
                        assertEquals("finished", event.path("data").path("status").asText());
                        assertEquals(List.of(text.toString()), state.persisted.get(conversation), "One durable turn before terminal");
                    }
                    case "tool_progress" -> { }
                    default -> fail("Unexpected event: " + eventName);
                }
            }
        }
        assertEquals(1, completions, "Exactly one completion before EOF");
        return new Answer(conversation, marker, chunkTimes.isEmpty() ? Long.MAX_VALUE : chunkTimes.get(0),
                List.copyOf(chunkTimes), List.copyOf(heartbeats), text.toString(), completions);
    }

    protected List<Answer> concurrentAnswers(String base, int count, String mode) throws Exception {
        ExecutorService readers = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count), start = new CountDownLatch(1);
        List<Future<Answer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                String username = "acceptance-user-" + (i % 10), conversation = UUID.randomUUID().toString();
                results.add(readers.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return answer(base, username, conversation, mode);
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "All concurrent consumers must be ready");
            start.countDown();
            List<Answer> answers = new ArrayList<>();
            for (Future<Answer> result : results) answers.add(result.get(30, TimeUnit.SECONDS));
            return answers;
        } finally {
            start.countDown();
            readers.shutdownNow();
        }
    }

    protected static String expectedText(String marker, int chunks) {
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < chunks; i++) expected.append(marker).append('/').append(i).append(';');
        return expected.toString();
    }
    protected static long p95(List<Answer> answers) {
        long[] sorted = answers.stream().mapToLong(Answer::firstTextMillis).sorted().toArray();
        return sorted[(int) Math.ceil(sorted.length * .95) - 1];
    }
    protected void awaitEmpty() throws Exception {
        await(() -> registry.activeRequestCount() == 0 && registry.conversationLeaseCount() == 0
                && ChatStreamingResourceProbe.streams(streams) == 0 && state.subscriptions.get() == 0
                && model.activeConnections() == 0 && workers.getActiveCount() == 0 && workers.getQueue().isEmpty()
                && generators.getActiveCount() == 0 && generators.getQueue().isEmpty()
                && ((ScheduledThreadPoolExecutor) timerService).getQueue().size() == 1, Duration.ofSeconds(10));
        assertEquals(0, ChatStreamingResourceProbe.pendingEvents(streams));
    }
    protected static void await(BooleanSupplier condition, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition did not settle within " + timeout);
    }
    protected Map<String, Object> resourceMetrics() {
        return Map.ofEntries(Map.entry("activeRequests", registry.activeRequestCount()),
                Map.entry("leases", registry.conversationLeaseCount()),
                Map.entry("streams", ChatStreamingResourceProbe.streams(streams)),
                Map.entry("pendingEvents", ChatStreamingResourceProbe.pendingEvents(streams)),
                Map.entry("subscriptions", state.subscriptions.get()),
                Map.entry("modelConnections", model.activeConnections()),
                Map.entry("workerPoolSize", workers.getPoolSize()),
                Map.entry("workerLargestPoolSize", workers.getLargestPoolSize()),
                Map.entry("workerQueue", workers.getQueue().size()),
                Map.entry("generationActive", generators.getActiveCount()),
                Map.entry("generationQueue", generators.getQueue().size()),
                Map.entry("generationLargestPoolSize", generators.getLargestPoolSize()),
                Map.entry("timerPoolSize", ((ScheduledThreadPoolExecutor) timerService).getPoolSize()),
                Map.entry("timerQueue", ((ScheduledThreadPoolExecutor) timerService).getQueue().size()),
                Map.entry("retainedRecords", registry.retainedRequestCount()));
    }
    protected void saveMetrics(String name, Map<String, Object> values) throws IOException {
        Path directory = Path.of(System.getProperty("chat.acceptance.metrics-dir",
                ".superpowers/sdd/2026-10-06-remove-flux-chat"));
        Files.createDirectories(directory);
        Map<String, Object> artifact = new LinkedHashMap<>(values);
        artifact.put("recordedAt", java.time.Instant.now().toString());
        artifact.put("javaVersion", System.getProperty("java.version"));
        artifact.put("operatingSystem", System.getProperty("os.name"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(name + ".json").toFile(), artifact);
    }
}
