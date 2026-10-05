package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.model.chat.ChatCommand;
import com.yizhaoqi.smartpai.model.Conversation;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.ConversationRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.support.MockModelSseServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatHandlerStreamingTest {
    private MockModelSseServer server;
    private Scheduler worker;
    private ChatHandler handler;
    private HybridSearchService search;
    private ConversationService conversations;
    private ConversationMessageService messages;
    private ConversationCompressionService compression;
    private final ChatCommand command = new ChatCommand("alice", "explicit-conversation", UUID.randomUUID(), "test question");

    @BeforeEach void setup() throws Exception {
        server = new MockModelSseServer();
        worker = Schedulers.newBoundedElastic(2, 16, "test-chat-worker");
        search = mock(HybridSearchService.class);
        conversations = mock(ConversationService.class);
        messages = mock(ConversationMessageService.class);
        compression = mock(ConversationCompressionService.class);
        lenient().when(conversations.loadHistoryForChat("alice", "explicit-conversation")).thenReturn(List.of());
        ObjectMapper mapper = new ObjectMapper();
        AiProperties config = new AiProperties();
        config.getContext().setWindowTokens(4000);
        config.getGeneration().setMaxTokens(200);
        TokenEstimator estimator = new TokenEstimator(mapper);
        handler = new ChatHandler(mock(StringRedisTemplate.class), search,
                new DeepSeekClient(server.url(), "test-model-token", "test-model", config, mapper),
                mapper, config, compression, messages, new ContextBudgetService(config, estimator), estimator, conversations, worker);
    }
    @AfterEach void close() { worker.dispose(); server.close(); }

    @Test void publisherIsCold() {
        var reply = handler.generateReply(command);
        verifyNoInteractions(conversations, search, messages);
        assertEquals(0, server.requests());
        server.enqueue(s -> { s.content("answer"); s.data("[DONE]"); });
        StepVerifier.create(reply).assertNext(out -> assertEquals("answer", out.data().get("chunk")))
                .expectComplete().verify(Duration.ofSeconds(5));
    }
    @Test void firstRoundContentArrivesBeforeModelCompletion() {
        server.enqueue(s -> { s.content("first"); s.probeUntilDisconnected(); });
        StepVerifier.create(handler.generateReply(command).take(1))
                .assertNext(out -> { assertEquals("chunk", out.type()); assertEquals("first", out.data().get("chunk")); })
                .expectComplete().verify(Duration.ofSeconds(5));
        assertEquals(0, server.secondModelCalls());
        verifyNoInteractions(messages);
    }
    @Test void incompleteFirstRoundNeverSearchesStartsSecondModelOrPersists() {
        server.enqueue(s -> { s.content("partial"); s.tool("call-1", "{\"query\":\"private document\"}"); });
        StepVerifier.create(handler.generateReply(command))
                .assertNext(out -> assertEquals("partial", out.data().get("chunk")))
                .expectErrorSatisfies(error -> assertEquals("MODEL_ERROR", ((ChatHandler.GenerationException) error).getErrorCode()))
                .verify(Duration.ofSeconds(5));
        assertEquals(0, server.secondModelCalls());
        verifyNoInteractions(search, messages);
    }
    @Test void incompleteSecondRoundFailsWithoutPersistingPartialAnswer() {
        server.enqueue(s -> { s.tool("call-1", "{\"query\":\"private document\"}"); s.data("[DONE]"); });
        server.enqueue(s -> s.content("partial"));
        when(search.searchWithPermission(anyString(), eq("alice"), eq(10))).thenReturn(List.of());
        StepVerifier.create(handler.generateReply(command)).expectNextCount(2)
                .assertNext(out -> assertEquals("partial", out.data().get("chunk")))
                .expectErrorSatisfies(error -> assertEquals("MODEL_ERROR", ((ChatHandler.GenerationException) error).getErrorCode()))
                .verify(Duration.ofSeconds(5));
        verifyNoInteractions(messages);
    }
    @Test void explicitConversationIgnoresCurrentPointer() {
        server.enqueue(s -> { s.content("answer"); s.data("[DONE]"); });
        StepVerifier.create(handler.generateReply(command)).expectNextCount(1).expectComplete().verify(Duration.ofSeconds(5));
        verify(conversations).loadHistoryForChat("alice", "explicit-conversation");
        verify(conversations, never()).getCurrentConversationId(anyString());
        verify(conversations, never()).switchConversation(anyString(), anyString());
    }
    @Test void searchAndSecondModelStayInSameOrderedSubscription() {
        server.enqueue(s -> { s.content("preface"); s.tool("call-1", "{\"query\":\"private document\"}"); s.data("[DONE]"); });
        server.enqueue(s -> { s.content("answer"); s.data("[DONE]"); });
        AtomicReference<String> searchThread = new AtomicReference<>();
        when(search.searchWithPermission("private document", "alice", 10)).thenAnswer(inv -> {
            searchThread.set(Thread.currentThread().getName()); return List.of();
        });
        StepVerifier.create(handler.generateReply(command))
                .assertNext(out -> assertEquals("preface", out.data().get("chunk")))
                .assertNext(out -> assertEquals(Map.of("tool", "search_knowledge_base", "status", "started"), out.data()))
                .assertNext(out -> assertEquals(Map.of("tool", "search_knowledge_base", "status", "finished"), out.data()))
                .assertNext(out -> assertEquals("answer", out.data().get("chunk")))
                .expectComplete().verify(Duration.ofSeconds(5));
        assertTrue(searchThread.get().startsWith("test-chat-worker"));
        verifyNoInteractions(messages);
    }
    @Test void cancelDuringSearchNeverStartsSecondModel() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), returned = new CountDownLatch(1);
        server.enqueue(s -> { s.tool("call-1", "{\"query\":\"private document\"}"); s.data("[DONE]"); });
        when(search.searchWithPermission(anyString(), eq("alice"), eq(10))).thenAnswer(inv -> {
            started.countDown();
            // Simulate an external blocking search that does not honor thread interruption.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (release.getCount() != 0 && System.nanoTime() < deadline) {
                try { release.await(100, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
            }
            returned.countDown(); return List.of();
        });
        var outputs = new java.util.concurrent.CopyOnWriteArrayList<com.yizhaoqi.smartpai.model.chat.ChatOutput>();
        var subscription = handler.generateReply(command).subscribe(outputs::add);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        subscription.dispose();
        release.countDown();
        assertTrue(returned.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(1, outputs.size());
        assertEquals("started", outputs.get(0).data().get("status"));
        assertEquals(0, server.secondModelCalls());
        verifyNoInteractions(messages);
    }
    @Test void cancelDuringSecondModelCancelsCurrentHttp() throws Exception {
        server.enqueue(s -> { s.tool("call-1", "{\"query\":\"private document\"}"); s.data("[DONE]"); });
        server.enqueue(s -> { s.content("partial"); s.probeUntilDisconnected(); });
        when(search.searchWithPermission(anyString(), eq("alice"), eq(10))).thenReturn(List.of());
        StepVerifier.create(handler.generateReply(command)).expectNextCount(2)
                .assertNext(out -> assertEquals("partial", out.data().get("chunk")))
                .thenCancel().verify(Duration.ofSeconds(5));
        assertTrue(server.awaitDisconnect(Duration.ofSeconds(1)));
        assertEquals(1, server.secondModelCalls());
        verifyNoInteractions(messages);
    }
    @Test void mysqlFailurePropagates() {
        when(messages.appendTurn(eq(command.conversationId()), eq(command.message()), eq("answer"), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> handler.persistCompletedTurn(command, "answer"));
        verifyNoInteractions(compression);
    }
    @Test void redisFailureAfterCommitDoesNotAppendAgain() {
        List<Map<String, String>> appended = List.of(Map.of("role", "assistant", "content", "answer"));
        when(messages.appendTurn(eq(command.conversationId()), eq(command.message()), eq("answer"), any())).thenReturn(appended);
        doThrow(new IllegalStateException("cache unavailable")).when(conversations)
                .cacheCommittedTurn("alice", command.conversationId(), appended);
        assertDoesNotThrow(() -> handler.persistCompletedTurn(command, "answer"));
        verify(messages, times(1)).appendTurn(eq(command.conversationId()), eq(command.message()), eq("answer"), any());
        verify(compression, never()).checkAndCompress(anyString(), anyList());
    }
    @Test void postCommitCacheMissRebuildsHistoryWithoutDuplicatingCommittedTurn() {
        List<Map<String, String>> appended = List.of(
                Map.of("seq", "101", "role", "user", "content", command.message()),
                Map.of("seq", "102", "role", "assistant", "content", "answer"));
        List<Map<String, String>> rebuilt = List.of(
                Map.of("seq", "1", "role", "user", "content", "previous question"),
                Map.of("seq", "2", "role", "assistant", "content", "previous answer"), appended.get(0), appended.get(1));
        when(messages.appendTurn(eq(command.conversationId()), eq(command.message()), eq("answer"), any())).thenReturn(appended);
        // A post-commit rebuild from MySQL already contains the committed turn.
        when(conversations.cacheCommittedTurn("alice", command.conversationId(), appended)).thenReturn(rebuilt);
        handler.persistCompletedTurn(command, "answer");
        verify(messages, times(1)).appendTurn(eq(command.conversationId()), eq(command.message()), eq("answer"), any());
        verify(compression, never()).appendMessages(anyString(), anyList());
        verify(compression).checkAndCompress(command.conversationId(), rebuilt);
    }
    @Test void failedPostCommitAppendRecoversSurvivingOldCacheExactlyOnce() throws Exception {
        CachedHistoryFixture storage = new CachedHistoryFixture(false);
        String oldCache = storage.cache.get();
        storage.handler().persistCompletedTurn(command, "answer");
        assertEquals(oldCache, storage.cache.get());
        verify(storage.compression).replaceWorkingSet(eq(command.conversationId()), anyLong(), eq(storage.durable.get()));
        // Recreate the reader to ensure recovery does not depend on ephemeral invalidation state.
        ConversationService restartedReader = storage.reader();
        assertEquals(storage.durable.get(), restartedReader.loadHistoryForChat("alice", command.conversationId()));
        assertEquals(storage.durable.get(), restartedReader.loadHistoryForChat("alice", command.conversationId()));
        verify(storage.messages, times(1)).appendTurn(eq(command.conversationId()), eq(command.message()), eq("answer"), any());
        assertEquals(4, new ObjectMapper().readTree(storage.cache.get()).size());
    }
    @Test void postCommitRedisReadFailureRecoversOldCacheAfterConnectionReturns() throws Exception {
        CachedHistoryFixture storage = new CachedHistoryFixture(true);
        String oldCache = storage.cache.get();
        storage.handler().persistCompletedTurn(command, "answer");
        assertEquals(oldCache, storage.cache.get());
        storage.cacheUnavailable.set(false);
        assertEquals(storage.durable.get(), storage.reader().loadHistoryForChat("alice", command.conversationId()));
        assertEquals(storage.durable.get(), storage.reader().loadHistoryForChat("alice", command.conversationId()));
        verify(storage.messages, times(1)).appendTurn(eq(command.conversationId()), eq(command.message()), eq("answer"), any());
        verify(storage.compression, never()).appendMessages(anyString(), anyList());
        assertEquals(4, new ObjectMapper().readTree(storage.cache.get()).size());
    }
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void redisRecoveryDuringNextGenerationDoesNotHidePreviouslyCommittedTurn(boolean readUnavailable) throws Exception {
        CachedHistoryFixture storage = new CachedHistoryFixture(true);
        String originalCache = storage.cache.get();
        storage.handler().persistCompletedTurn(command, "answer");
        assertEquals(originalCache, storage.cache.get());
        // Recreate the service/handler as after a restart; B loads durable A while Redis cannot be repaired.
        storage.cacheUnavailable.set(readUnavailable);
        storage.cacheWriteUnavailable.set(true);
        ChatCommand second = new ChatCommand("alice", command.conversationId(), UUID.randomUUID(), "second question");
        ChatHandler restarted = storage.handler();
        server.enqueue(s -> { s.content("second answer"); s.data("[DONE]"); });
        StepVerifier.create(restarted.generateReply(second)).expectNextCount(1)
                .expectComplete().verify(Duration.ofSeconds(5));
        assertEquals(originalCache, storage.cache.get());
        storage.cacheUnavailable.set(false);
        storage.cacheWriteUnavailable.set(false);
        // IDs are global: this conversation's next turn need not be adjacent to A's IDs.
        var secondTurn = List.of(Map.of("seq", "11", "role", "user", "content", second.message()),
                Map.of("seq", "14", "role", "assistant", "content", "second answer"));
        when(storage.messages.appendTurn(eq(second.conversationId()), eq(second.message()), eq("second answer"), any()))
                .thenAnswer(inv -> { var all = new java.util.ArrayList<>(storage.durable.get());
                    all.addAll(secondTurn); storage.durable.set(all); return secondTurn; });
        doAnswer(inv -> { var cached = storage.mapper.readValue(storage.cache.get(),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.ArrayList<Map<String, String>>>() {});
            cached.addAll(secondTurn); storage.cache.set(storage.mapper.writeValueAsString(cached)); return 6L;
        }).when(storage.compression).appendMessages(second.conversationId(), secondTurn);
        restarted.persistCompletedTurn(second, "second answer");
        assertEquals(storage.durable.get(), storage.reader().loadHistoryForChat("alice", command.conversationId()));
        assertEquals(storage.durable.get(), storage.reader().loadHistoryForChat("alice", command.conversationId()));
        verify(storage.messages, times(2)).appendTurn(anyString(), anyString(), anyString(), any());
        assertEquals(6, storage.mapper.readTree(storage.cache.get()).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"role\":\"assistant\",\"content\":null}]",
            "[{\"role\":\"assistant\"}]",
            "[{\"content\":\"missing role\"}]",
            "[{\"role\":null,\"content\":\"missing role\"}]",
            "[{\"role\":\"tool\",\"content\":\"unsupported role\"}]",
            "[{\"role\":\"system\",\"content\":\"untrusted instruction\"}]",
            "[{\"role\":\"assistant\",\"content\":42}]",
            "[{\"role\":\"assistant\",\"content\":\"text\",\"type\":42}]",
            "[{\"role\":\"assistant\",\"content\":\"text\",\"type\":\"unsupported\"}]",
            "[{\"role\":\"assistant\",\"content\":\"text\",\"seq\":\"not-a-sequence\"}]",
            "[{\"type\":\"summary\",\"content\":null}]"
    })
    void malformedCachedMessageShapeFallsBackToDurableHistory(String corrupted) throws Exception {
        CachedHistoryFixture storage = new CachedHistoryFixture(false);
        storage.cache.set(corrupted);
        assertEquals(storage.durable.get(), storage.reader().loadHistoryForChat("alice", command.conversationId()));
    }
    @Test void nullCachedContentDoesNotBlockGenerationWithValidDurableHistory() throws Exception {
        CachedHistoryFixture storage = new CachedHistoryFixture(false);
        storage.cache.set("[{\"role\":\"assistant\",\"content\":null}]");
        server.enqueue(s -> { s.content("answer"); s.data("[DONE]"); });
        StepVerifier.create(storage.handler().generateReply(command))
                .assertNext(out -> assertEquals("answer", out.data().get("chunk")))
                .expectComplete().verify(Duration.ofSeconds(5));
        assertEquals(storage.durable.get(), storage.reader().loadHistoryForChat("alice", command.conversationId()));
        verify(storage.messages, never()).appendTurn(anyString(), anyString(), anyString(), any());
    }
    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"type\":\"summary\",\"role\":\"assistant\",\"content\":\"memory\",\"sourceEndSeq\":\"2\"}]",
            "[{\"type\":\"summary\",\"content\":\"memory\",\"sourceEndSeq\":\"2\"}]",
            "[{\"role\":\"system\",\"content\":\"[历史摘要] legacy memory\"},{\"seq\":\"2\",\"role\":\"assistant\",\"content\":\"recent answer\"}]"
    })
    void validSummaryShapesRemainUsableWhenTheirSequenceCoverageIsCurrent(String cached) throws Exception {
        CachedHistoryFixture storage = new CachedHistoryFixture(false);
        storage.cache.set(cached);
        assertEquals(new ObjectMapper().readValue(cached, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {}),
                storage.reader().loadHistoryForChat("alice", command.conversationId()));
        verify(storage.messages, never()).getRawHistory(anyString());
    }

    /** Real cache reader/orchestration; doubles replace only Redis, database, and the model transport. */
    private final class CachedHistoryFixture {
        final ObjectMapper mapper = new ObjectMapper();
        final AtomicReference<List<Map<String, String>>> durable = new AtomicReference<>(List.of(
                Map.of("seq", "1", "role", "user", "content", "previous question"),
                Map.of("seq", "2", "role", "assistant", "content", "previous answer")));
        final List<Map<String, String>> appended = List.of(
                Map.of("seq", "3", "role", "user", "content", command.message()),
                Map.of("seq", "4", "role", "assistant", "content", "answer"));
        final AtomicReference<String> cache = new AtomicReference<>();
        final AtomicBoolean cacheUnavailable = new AtomicBoolean();
        final AtomicBoolean cacheWriteUnavailable = new AtomicBoolean();
        final AtomicBoolean failNextReplacement = new AtomicBoolean();
        final StringRedisTemplate redis = mock(StringRedisTemplate.class);
        final ValueOperations<String, String> values = mock(ValueOperations.class);
        final ConversationRepository repository = mock(ConversationRepository.class);
        final UserRepository users = mock(UserRepository.class);
        final ConversationCompressionService compression = mock(ConversationCompressionService.class);
        final ConversationMessageService messages = mock(ConversationMessageService.class, invocation -> {
            // The durable scalar query remains a database boundary double, including before its API is added.
            if ("getLatestSequenceId".equals(invocation.getMethod().getName())) {
                return durable.get().stream().mapToLong(message -> Long.parseLong(message.get("seq"))).max().orElse(0L);
            }
            if ("getLatestSequenceIdBefore".equals(invocation.getMethod().getName())) {
                long upperBound = invocation.getArgument(1);
                return durable.get().stream().mapToLong(message -> Long.parseLong(message.get("seq")))
                        .filter(sequence -> sequence < upperBound).max().orElse(0L);
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        CachedHistoryFixture(boolean failReadAfterCommit) throws Exception {
            cache.set(mapper.writeValueAsString(durable.get()));
            when(redis.opsForValue()).thenReturn(values);
            when(values.get("conversation:" + command.conversationId())).thenAnswer(inv -> {
                if (cacheUnavailable.get()) throw new IllegalStateException("Redis unavailable");
                return cache.get();
            });
            User user = new User(); user.setId(7L);
            Conversation conversation = new Conversation(); conversation.setUser(user);
            conversation.setConversationId(command.conversationId()); conversation.setMessages("[]");
            when(users.findByUsername("alice")).thenReturn(Optional.of(user));
            when(repository.findByConversationId(command.conversationId())).thenReturn(Optional.of(conversation));
            when(messages.getRawHistory(command.conversationId())).thenAnswer(inv -> durable.get());
            when(messages.appendTurn(eq(command.conversationId()), eq(command.message()), eq("answer"), any())).thenAnswer(inv -> {
                var committed = new java.util.ArrayList<>(durable.get()); committed.addAll(appended); durable.set(committed);
                cacheUnavailable.set(failReadAfterCommit);
                failNextReplacement.set(!failReadAfterCommit); return appended;
            });
            doThrow(new IllegalStateException("Redis append unavailable")).when(compression).appendMessages(command.conversationId(), appended);
            when(compression.replaceWorkingSet(eq(command.conversationId()), anyLong(), anyList())).thenAnswer(inv -> {
                if (cacheUnavailable.get() || cacheWriteUnavailable.get() || failNextReplacement.getAndSet(false))
                    throw new IllegalStateException("Redis replace unavailable");
                cache.set(mapper.writeValueAsString(inv.getArgument(2))); return true;
            });
        }
        ConversationService reader() { return new ConversationService(repository, users, redis, mapper, messages, compression); }
        ChatHandler handler() {
            AiProperties config = new AiProperties(); config.getContext().setWindowTokens(4000); config.getGeneration().setMaxTokens(200);
            TokenEstimator estimator = new TokenEstimator(mapper);
            return new ChatHandler(redis, search,
                    new DeepSeekClient(server.url(), "test-model-token", "test-model", config, mapper), mapper, config,
                    compression, messages, new ContextBudgetService(config, estimator), estimator, reader(), worker);
        }
    }
    @Test void modelAndToolFailuresHaveSafeDistinctCodes() {
        server.enqueue(s -> s.data("{\"error\":\"supplier-secret\"}"));
        StepVerifier.create(handler.generateReply(command)).expectErrorSatisfies(error -> {
            assertEquals("MODEL_ERROR", ((ChatHandler.GenerationException) error).getErrorCode());
            assertFalse(error.getMessage().contains("supplier-secret"));
        }).verify(Duration.ofSeconds(5));
        server.enqueue(s -> { s.tool("call-1", "{\"query\":\"private document\"}"); s.data("[DONE]"); });
        when(search.searchWithPermission(anyString(), anyString(), anyInt())).thenThrow(new IllegalStateException("search-secret"));
        StepVerifier.create(handler.generateReply(command)).expectNextCount(1).expectErrorSatisfies(error -> {
            assertEquals("TOOL_ERROR", ((ChatHandler.GenerationException) error).getErrorCode());
            assertFalse(error.getMessage().contains("search-secret"));
        }).verify(Duration.ofSeconds(5));
    }
}
