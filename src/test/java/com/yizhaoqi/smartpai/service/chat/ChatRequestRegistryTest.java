package com.yizhaoqi.smartpai.service.chat;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.ChatStreamingProperties;
import com.yizhaoqi.smartpai.model.chat.ChatCommand;
import com.yizhaoqi.smartpai.model.chat.ChatEventEnvelope;
import com.yizhaoqi.smartpai.model.chat.ChatOutput;
import com.yizhaoqi.smartpai.model.chat.ChatStreamRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.io.InputStream;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.yizhaoqi.smartpai.service.chat.ChatRequestContext.State.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class ChatRequestRegistryTest {
    private MutableClock clock;
    private ChatStreamingProperties properties;
    private ChatRequestRegistry registry;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        properties = new ChatStreamingProperties();
        registry = new ChatRequestRegistry(properties, clock);
    }

    @Test
    void cancelBeforeRegisterRejectsGeneration() {
        ChatCommand command = command("alice", "conversation-a");
        assertEquals(CANCELLED, registry.cancel("alice", command.requestId()).state());
        assertError("REQUEST_CANCELLED", HttpStatus.CONFLICT, () -> registry.register(command));
        assertEquals(0, registry.activeRequestCount());
        assertEquals(1, registry.retainedRequestCount());
    }

    @Test
    void lateBodyIsClosed() {
        ChatRequestContext context = registry.register(command("alice", "conversation-a"));
        registry.cancel("alice", context.command().requestId());
        CountingBody disposable = new CountingBody();
        AtomicInteger callback = new AtomicInteger();
        context.generationResources().attachResponseBody(disposable);
        context.onCancel(callback::incrementAndGet);
        context.releaseResources();
        context.releaseResources();
        assertEquals(1, disposable.disposals.get());
        assertEquals(1, callback.get());
    }

    @Test
    void cancellationClosesExistingResourcesOnlyOnce() {
        ChatRequestContext context = registry.register(command("alice", "conversation-a"));
        CountingBody disposable = new CountingBody();
        AtomicInteger callback = new AtomicInteger();
        context.generationResources().attachResponseBody(disposable);
        context.onCancel(callback::incrementAndGet);
        assertTrue(registry.cancel("alice", context.command().requestId()).changed());
        assertFalse(registry.cancel("alice", context.command().requestId()).changed());
        registry.finish(context, CANCELLED);
        context.releaseResources();
        assertEquals(1, disposable.disposals.get());
        assertEquals(1, callback.get());
        assertEquals(0, registry.activeRequestCount());
        assertEquals(0, registry.conversationLeaseCount());
    }

    @Test
    void sameIdWithDifferentPayloadIsDuplicate() {
        ChatCommand original = command("alice", "conversation-a");
        registry.register(original);
        ChatCommand changed = new ChatCommand("alice", "conversation-b", original.requestId(), "changed");
        assertError("REQUEST_DUPLICATE", HttpStatus.CONFLICT, () -> registry.register(changed));
        assertError("REQUEST_DUPLICATE", HttpStatus.CONFLICT, () -> registry.register(original));
    }

    @Test
    void registeredCancelledRequestRemainsDuplicate() {
        ChatCommand original = command("alice", "conversation-a");
        registry.register(original);
        registry.cancel("alice", original.requestId());
        assertError("REQUEST_DUPLICATE", HttpStatus.CONFLICT,
                () -> registry.register(new ChatCommand("alice", "conversation-b", original.requestId(), "changed")));
    }

    @Test
    void singleFlightPerConversation() {
        ChatRequestContext first = registry.register(command("alice", "conversation-a"));
        assertError("CONVERSATION_BUSY", HttpStatus.CONFLICT,
                () -> registry.register(command("alice", "conversation-a")));
        registry.finish(first, FAILED);
        assertEquals(REGISTERED, registry.register(command("alice", "conversation-a")).state());
    }

    @Test
    void canonicalConversationIdSharesSingleLeaseAcrossUuidCase() {
        String conversationId = "12345678-abcd-4abc-8abc-123456789abc";
        ChatCommand first = new ChatCommand("alice", conversationId, UUID.randomUUID(), "question one");
        ChatCommand second = new ChatCommand("alice", conversationId.toUpperCase(java.util.Locale.ROOT),
                UUID.randomUUID(), "question two");
        registry.register(first);
        assertError("CONVERSATION_BUSY", HttpStatus.CONFLICT, () -> registry.register(second));
        assertEquals(1, registry.activeRequestCount());
        assertEquals(1, registry.conversationLeaseCount());
    }

    @Test
    void commandNormalizesOnlyCanonicalUuidConversationAndPreservesMessage() {
        String canonical = "12345678-abcd-4abc-8abc-123456789abc";
        String message = "  question\n中文😀  ";
        ChatCommand command = new ChatCommand("alice", canonical.toUpperCase(java.util.Locale.ROOT),
                UUID.randomUUID(), message);
        assertEquals(canonical, command.conversationId(), "all downstream leases and caches need the same identity");
        assertEquals(message, command.message());
        for (String invalid : new String[] { null, "not-a-uuid", "1-1-1-1-1", canonical + " " }) {
            ChatCommand unchanged = new ChatCommand("alice", invalid, UUID.randomUUID(), message);
            assertEquals(invalid, unchanged.conversationId(), "invalid inputs remain for existing HTTP validation");
            assertEquals(message, unchanged.message());
        }
    }

    @Test
    void concurrentRegistersAcquireOneLease() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger busy = new AtomicInteger();
        Runnable register = () -> {
            await(start);
            try {
                registry.register(command("alice", "conversation-a"));
                accepted.incrementAndGet();
            } catch (ChatRequestException error) {
                assertEquals("CONVERSATION_BUSY", error.getErrorCode());
                busy.incrementAndGet();
            }
        };
        CompletableFuture<Void> first = CompletableFuture.runAsync(register);
        CompletableFuture<Void> second = CompletableFuture.runAsync(register);
        start.countDown();
        CompletableFuture.allOf(first, second).get(2, TimeUnit.SECONDS);
        assertEquals(1, accepted.get());
        assertEquals(1, busy.get());
        assertEquals(1, registry.conversationLeaseCount());
    }

    @Test
    void differentConversationCanRun() {
        registry.register(command("alice", "conversation-a"));
        registry.register(command("alice", "conversation-b"));
        registry.register(command("bob", "conversation-c"));
        assertEquals(3, registry.activeRequestCount());
        assertEquals(3, registry.activeSnapshot().size());
    }

    @Test
    void foreignIdCannotCancelOwner() {
        ChatRequestContext owner = registry.register(command("alice", "conversation-a"));
        assertEquals(CANCELLED, registry.cancel("bob", owner.command().requestId()).state());
        assertEquals(REGISTERED, owner.state());
        assertEquals(1, registry.activeRequestCount());
        assertEquals(1, registry.retainedRequestCount());
        assertEquals(REGISTERED, registry.register(new ChatCommand("bob", "conversation-b",
                UUID.randomUUID(), "question")).state());
        assertError("REQUEST_CANCELLED", HttpStatus.CONFLICT, () -> registry.register(
                new ChatCommand("bob", "conversation-b", owner.command().requestId(), "question")));
    }

    @Test
    void oldCleanupCannotReleaseNewLease() {
        ChatRequestContext old = registry.register(command("alice", "conversation-a"));
        registry.finish(old, FAILED);
        clock.advance(properties.getTerminalRetentionMs());
        registry.purgeExpired();
        ChatRequestContext replacement = registry.register(old.command());
        registry.finish(old, FAILED);
        assertEquals(1, registry.activeRequestCount());
        assertEquals(1, registry.conversationLeaseCount());
        assertSame(replacement, registry.activeSnapshot().get(0));
        assertError("CONVERSATION_BUSY", HttpStatus.CONFLICT,
                () -> registry.register(command("alice", "conversation-a")));
    }

    @Test
    void concurrentFinishKeepsLeaseUntilResourceCleanupCompletes() throws Exception {
        ChatRequestContext context = registry.register(command("alice", "conversation-a"));
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        context.generationResources().attachResponseBody(cleanupBody(() -> {
            cleanupStarted.countDown();
            await(releaseCleanup);
        }));
        CompletableFuture<Void> firstFinish = CompletableFuture.runAsync(() -> registry.finish(context, FAILED));
        try {
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS));
            registry.finish(context, FAILED);
            assertEquals(1, registry.activeRequestCount(), "only the cleanup owner may finalize the request");
            assertEquals(1, registry.conversationLeaseCount(), "lease must outlive in-progress upstream cleanup");
            assertError("CONVERSATION_BUSY", HttpStatus.CONFLICT,
                    () -> registry.register(command("alice", "conversation-a")));
        } finally {
            releaseCleanup.countDown();
            firstFinish.get(2, TimeUnit.SECONDS);
        }
        assertEquals(0, registry.activeRequestCount());
        assertEquals(1, registry.retainedRequestCount());
    }

    @Test
    void capacityAndTtlAreBounded() {
        properties.setMaxActiveRequests(1);
        properties.setMaxRetainedRequests(3);
        properties.setMaxRetainedPerUser(2);
        registry = new ChatRequestRegistry(properties, clock);
        ChatRequestContext active = registry.register(command("alice", "conversation-a"));
        assertError("CHAT_CAPACITY_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS,
                () -> registry.register(command("bob", "conversation-b")));
        UUID tombstone = UUID.randomUUID();
        registry.cancel("alice", tombstone);
        assertError("CHAT_CAPACITY_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS,
                () -> registry.cancel("alice", UUID.randomUUID()));
        registry.cancel("bob", UUID.randomUUID());
        assertEquals(3, registry.totalRequestCount());
        assertError("CHAT_CAPACITY_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS,
                () -> registry.cancel("charlie", UUID.randomUUID()));
        clock.advance(properties.getTerminalRetentionMs() - 1);
        assertFalse(registry.cancel("alice", tombstone).changed(), "repeated cancel must not extend retention");
        registry.purgeExpired();
        assertEquals(3, registry.totalRequestCount());
        clock.advance(1);
        registry.purgeExpired();
        assertEquals(1, registry.totalRequestCount(), "active requests never expire by TTL");
        assertSame(active, registry.activeSnapshot().get(0));
        registry.finish(active, FAILED);
        clock.advance(properties.getTerminalRetentionMs());
        registry.purgeExpired();
        assertEquals(0, registry.totalRequestCount());
        assertEquals(0, registry.conversationLeaseCount());
    }

    @Test
    void completingCannotBeCancelledAndKeepsLeaseUntilFinished() {
        ChatRequestContext context = registry.register(command("alice", "conversation-a"));
        assertTrue(context.tryTransition(REGISTERED, RUNNING));
        assertTrue(context.tryTransition(RUNNING, COMPLETING));
        ChatRequestRegistry.CancelResult result = registry.cancel("alice", context.command().requestId());
        assertEquals(COMPLETING, result.state());
        assertFalse(result.changed());
        assertFalse(context.tryTransition(COMPLETING, CANCELLED));
        registry.finish(context, CANCELLED);
        assertEquals(COMPLETING, context.state());
        assertEquals(1, registry.conversationLeaseCount());
        registry.finish(context, FINISHED);
        assertEquals(FINISHED, registry.cancel("alice", context.command().requestId()).state());
        assertEquals(0, registry.conversationLeaseCount());
    }

    @Test
    void terminalStatesCannotBeRewrittenOrRetentionExtended() {
        ChatRequestContext context = registry.register(command("alice", "conversation-a"));
        registry.finish(context, TIMED_OUT);
        assertFalse(context.tryTransition(TIMED_OUT, RUNNING));
        registry.finish(context, FAILED);
        clock.advance(properties.getTerminalRetentionMs() - 1);
        registry.finish(context, TIMED_OUT);
        assertEquals(TIMED_OUT, registry.cancel("alice", context.command().requestId()).state());
        clock.advance(1);
        registry.purgeExpired();
        assertEquals(0, registry.totalRequestCount());
    }

    @Test
    void completionAndCancelHaveOnlyOneWinner() throws Exception {
        for (int i = 0; i < 200; i++) {
            ChatRequestContext context = registry.register(command("alice", "conversation-" + i));
            assertTrue(context.tryTransition(REGISTERED, RUNNING));
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<Boolean> completion = CompletableFuture.supplyAsync(() -> {
                await(start);
                return context.tryTransition(RUNNING, COMPLETING);
            });
            CompletableFuture<ChatRequestRegistry.CancelResult> cancel = CompletableFuture.supplyAsync(() -> {
                await(start);
                return registry.cancel("alice", context.command().requestId());
            });
            start.countDown();
            boolean completed = completion.get(2, TimeUnit.SECONDS);
            ChatRequestRegistry.CancelResult result = cancel.get(2, TimeUnit.SECONDS);
            assertEquals(completed ? COMPLETING : CANCELLED, result.state());
            assertEquals(!completed, result.changed());
            if (completed) registry.finish(context, FINISHED);
        }
        assertEquals(0, registry.activeRequestCount());
    }

    @Test
    void cancellationCallbacksRunOutsideRegistryLock() {
        ChatRequestContext context = registry.register(command("alice", "conversation-a"));
        context.onCancel(() -> assertDoesNotThrow(() -> CompletableFuture.runAsync(
                () -> registry.cancel("bob", UUID.randomUUID())).get(2, TimeUnit.SECONDS)));
        registry.cancel("alice", context.command().requestId());
        assertEquals(2, registry.retainedRequestCount());
    }

    @Test
    void cancellationBetweenCallbackStateReadsStillNotifiesOnceAndCleansTimers() throws Exception {
        ChatRequestContext context = spy(new ChatRequestContext(command("alice", "conversation-a")));
        CountingBody upstream = new CountingBody();
        CountingBody timer = new CountingBody();
        AtomicBoolean terminalReady = new AtomicBoolean();
        AtomicInteger callbacks = new AtomicInteger();
        context.generationResources().attachResponseBody(upstream);
        CountDownLatch cancelled = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        AtomicReference<CompletableFuture<Void>> cleanup = new AtomicReference<>();
        // Instrument the observable read boundary, without changing the production CAS/locks.
        doAnswer(invocation -> {
            ChatRequestContext.State snapshot = (ChatRequestContext.State) invocation.callRealMethod();
            if (firstRead.compareAndSet(true, false)) {
                cleanup.set(CompletableFuture.runAsync(() -> {
                    assertTrue(context.tryTransition(REGISTERED, CANCELLED));
                    cancelled.countDown();
                    context.releaseResources();
                }));
                assertTrue(cancelled.await(2, TimeUnit.SECONDS));
            }
            return snapshot;
        }).when(context).state();

        context.onCancel(() -> {
            callbacks.incrementAndGet();
            terminalReady.set(true);
            timer.close();
        });
        cleanup.get().get(2, TimeUnit.SECONDS);
        context.releaseResources();
        assertEquals(1, callbacks.get(), "cancellation must not drop the concurrently registered callback");
        assertTrue(terminalReady.get());
        assertEquals(1, timer.disposals.get());
        assertEquals(1, upstream.disposals.get());
    }

    @Test
    void lateAttachmentAndCallbackDuringCleanupAreHandledOnce() throws Exception {
        ChatRequestContext context = registry.register(command("alice", "conversation-a"));
        CountDownLatch disposalStarted = new CountDownLatch(1);
        CountDownLatch releaseDisposal = new CountDownLatch(1);
        context.generationResources().attachResponseBody(cleanupBody(() -> {
            disposalStarted.countDown();
            await(releaseDisposal);
        }));
        CompletableFuture<ChatRequestRegistry.CancelResult> cancellation = CompletableFuture.supplyAsync(
                () -> registry.cancel("alice", context.command().requestId()));
        CountingBody lateUpstream = new CountingBody();
        AtomicInteger callbacks = new AtomicInteger();
        try {
            assertTrue(disposalStarted.await(2, TimeUnit.SECONDS));
            context.generationResources().attachResponseBody(lateUpstream);
            context.onCancel(callbacks::incrementAndGet);
            assertEquals(1, lateUpstream.disposals.get());
            assertEquals(1, callbacks.get());
        } finally {
            releaseDisposal.countDown();
            assertEquals(CANCELLED, cancellation.get(2, TimeUnit.SECONDS).state());
        }
        context.releaseResources();
        assertEquals(1, lateUpstream.disposals.get());
        assertEquals(1, callbacks.get());
        assertEquals(0, registry.conversationLeaseCount());
    }

    @Test
    void throwingCleanupDoesNotLeakLeaseOrSkipOtherCallbacks() {
        ChatRequestContext context = registry.register(command("alice", "conversation-a"));
        AtomicInteger callback = new AtomicInteger();
        context.generationResources().attachResponseBody(cleanupBody(() -> { throw new IllegalStateException("test cleanup failure"); }));
        context.onCancel(() -> { throw new IllegalStateException("test callback failure"); });
        context.onCancel(callback::incrementAndGet);
        assertDoesNotThrow(() -> registry.cancel("alice", context.command().requestId()));
        assertEquals(1, callback.get());
        assertEquals(0, registry.conversationLeaseCount());
    }

    @Test
    void finishedCleanupDoesNotNotifyCancellation() {
        ChatRequestContext context = registry.register(command("alice", "conversation-a"));
        CountingBody disposable = new CountingBody();
        AtomicInteger callback = new AtomicInteger();
        context.generationResources().attachResponseBody(disposable);
        context.onCancel(callback::incrementAndGet);
        context.tryTransition(REGISTERED, RUNNING);
        context.tryTransition(RUNNING, COMPLETING);
        registry.finish(context, FINISHED);
        context.onCancel(callback::incrementAndGet);
        assertEquals(0, callback.get());
        assertEquals(1, disposable.disposals.get());
    }

    @Test
    void invalidCommandIsRejectedBeforeRegistration() {
        assertError("INVALID_REQUEST", HttpStatus.BAD_REQUEST, () -> registry.register(
                new ChatCommand("alice", "conversation-a", UUID.randomUUID(), "\u2003\t\n")));
        assertError("INVALID_REQUEST", HttpStatus.BAD_REQUEST, () -> registry.register(
                new ChatCommand("alice", "conversation-a", UUID.randomUUID(), "x".repeat(16001))));
        assertEquals(0, registry.totalRequestCount());
        assertEquals(REGISTERED, registry.register(new ChatCommand("alice", "conversation-a",
                UUID.randomUUID(), "x".repeat(16000))).state());
    }

    @Test
    void immutableBusinessOutputsOnlyAllowSupportedTypes() {
        Map<String, Object> source = new HashMap<>(Map.of("chunk", "hello"));
        ChatOutput output = new ChatOutput("chunk", source);
        source.put("chunk", "changed");
        assertEquals("hello", output.data().get("chunk"));
        assertThrows(UnsupportedOperationException.class, () -> output.data().put("chunk", "changed"));
        assertThrows(IllegalArgumentException.class, () -> new ChatOutput("completion", Map.of()));
        ChatEventEnvelope envelope = new ChatEventEnvelope("meta", UUID.randomUUID(), "conversation-a", 1, Map.of());
        assertThrows(UnsupportedOperationException.class, () -> envelope.data().put("unsafe", "value"));
    }

    @Test
    void requestIdRequiresCanonicalUuidString() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String request = "{\"conversationId\":\"conversation-a\",\"requestId\":\"%s\",\"message\":\"question\"}";
        String canonical = "12345678-abcd-4abc-8abc-123456789abc";
        assertEquals(UUID.fromString(canonical), mapper.readValue(request.formatted(canonical), ChatStreamRequest.class).requestId());
        assertEquals(UUID.fromString(canonical), mapper.readValue(request.formatted(canonical.toUpperCase(java.util.Locale.ROOT)),
                ChatStreamRequest.class).requestId());
        String base64 = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(JsonMappingException.class, () -> mapper.readValue(request.formatted(base64), ChatStreamRequest.class));
        assertThrows(JsonMappingException.class, () -> mapper.readValue(request.formatted("1-1-1-1-1"), ChatStreamRequest.class));
        assertThrows(JsonMappingException.class, () -> mapper.readValue(request.formatted(canonical + " "), ChatStreamRequest.class));
        assertNull(mapper.readValue(request.formatted(canonical).replace('"' + canonical + '"', "null"),
                ChatStreamRequest.class).requestId(), "null must remain available to required-field validation");
    }

    @Test
    void requestAndCommandStringRepresentationsNeverExposeMessage() throws Exception {
        String sensitive = "TEST_SENSITIVE_PROMPT_f2f439e3_DO_NOT_LOG";
        for (String conversationId : new String[] { null, "c" }) {
            ChatStreamRequest request = new ChatStreamRequest(conversationId, null, sensitive);
            assertFalse(request.toString().contains(sensitive), "even rejected requests must not log the prompt");
            assertTrue(request.toString().contains("messageLength=" + sensitive.length()));
            assertEquals(sensitive, request.message());
            ObjectMapper mapper = new ObjectMapper();
            assertEquals(sensitive, mapper.readTree(mapper.writeValueAsString(request)).get("message").asText());
        }
        ChatCommand command = new ChatCommand("alice", "c", UUID.randomUUID(), sensitive);
        assertFalse(command.toString().contains(sensitive));
        assertTrue(command.toString().contains("messageLength=" + sensitive.length()));
        assertEquals(sensitive, command.message());
    }

    private static ChatCommand command(String username, String conversationId) {
        return new ChatCommand(username, conversationId, UUID.randomUUID(), "question");
    }

    private static void assertError(String code, HttpStatus status, Runnable action) {
        ChatRequestException error = assertThrows(ChatRequestException.class, action::run);
        assertEquals(code, error.getErrorCode());
        assertEquals(status, error.getStatus());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static final class CountingBody extends InputStream {
        private final AtomicInteger disposals = new AtomicInteger();
        @Override public int read() { return -1; }
        @Override public void close() { disposals.incrementAndGet(); }
    }
    private static InputStream cleanupBody(Runnable cleanup) {
        return new InputStream() {
            @Override public int read() { return -1; }
            @Override public void close() { cleanup.run(); }
        };
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-10-03T00:00:00Z");
        void advance(long millis) { instant = instant.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
