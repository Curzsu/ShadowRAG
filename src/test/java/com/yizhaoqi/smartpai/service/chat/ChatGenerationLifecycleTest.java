package com.yizhaoqi.smartpai.service.chat;

import com.yizhaoqi.smartpai.config.ChatStreamingProperties;
import com.yizhaoqi.smartpai.model.chat.*;
import com.yizhaoqi.smartpai.service.*;
import com.yizhaoqi.smartpai.support.RecordingSseEmitter;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatGenerationLifecycleTest {
    final ChatHandler handler = mock(ChatHandler.class);
    final ChatStreamingProperties properties = new ChatStreamingProperties();
    final ChatRequestRegistry registry = new ChatRequestRegistry(properties);
    final ExecutorService writers = Executors.newSingleThreadExecutor();
    final ThreadPoolExecutor generators = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1));
    final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    final AtomicReference<RecordingSseEmitter> emitter = new AtomicReference<>();
    ChatStreamService service;
    @BeforeEach void setup() {
        properties.setHeartbeatIntervalMs(20);
        service = new ChatStreamService(handler, mock(ConversationService.class), registry, properties,
                writers, generators, timers, timeout -> { var e = new RecordingSseEmitter(timeout); emitter.set(e); return e; });
    }
    @AfterEach void close() { service.close(); generators.shutdownNow(); writers.shutdownNow(); timers.shutdownNow(); }
    ChatCommand command() { return new ChatCommand("alice",UUID.randomUUID().toString(),UUID.randomUUID(),"test"); }
    @Test void blockedGenerationDoesNotBlockSseDrain() throws Exception {
        var entered = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); new CountDownLatch(1).await(); return null; })
                .when(handler).generateReply(any(),any(),any());
        var c = command(); service.open(c); assertTrue(entered.await(2,TimeUnit.SECONDS));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while (emitter.get().heartbeats()==0 && System.nanoTime()<deadline) Thread.sleep(5);
        assertTrue(emitter.get().heartbeats()>0); assertEquals(1,emitter.get().eventsOfType("meta").size());
        service.cancel("alice",c.requestId()); emitter.get().awaitTerminal();
        assertEquals("cancelled",emitter.get().eventsOfType("completion").get(0).data().get("status"));
    }
    @Test void queuedTaskCancelledBeforeStartNeverCallsModel() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(); return null; }).when(handler).generateReply(any(),any(),any());
        var first=command(); service.open(first); assertTrue(entered.await(2,TimeUnit.SECONDS));
        var queued=command(); service.open(queued); service.cancel("alice",queued.requestId());
        release.countDown(); emitter.get().awaitTerminal(); generators.shutdown(); assertTrue(generators.awaitTermination(2,TimeUnit.SECONDS));
        verify(handler,never()).generateReply(eq(queued),any(),any());
    }
    @Test void rejectedTaskReleasesConversationLease() throws Exception {
        var entered=new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); new CountDownLatch(1).await(); return null; }).when(handler).generateReply(any(),any(),any());
        service.open(command()); assertTrue(entered.await(2,TimeUnit.SECONDS)); service.open(command());
        var rejected=command(); assertThrows(ChatRequestException.class, () -> service.open(rejected));
        assertEquals(2,registry.conversationLeaseCount()); assertEquals(2,registry.activeRequestCount());
        assertEquals(ChatRequestContext.State.FAILED,service.cancel("alice",rejected.requestId()).state());
    }
    @Test void cancellingQueuedTaskImmediatelyRestoresCapacityWhileWorkerStaysBlocked() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(); return null; })
                .when(handler).generateReply(any(),any(),any());
        try {
            service.open(command()); assertTrue(entered.await(2,TimeUnit.SECONDS));
            var queued = command(); service.open(queued); assertEquals(1,generators.getQueue().size());
            service.cancel("alice",queued.requestId());
            assertEquals(0,generators.getQueue().size(),"Cancellation must return the waiting slot before a worker exits");
            var replacement = command(); assertDoesNotThrow(() -> service.open(replacement));
            assertEquals(1,generators.getQueue().size()); assertEquals(1,generators.getActiveCount());
            verify(handler,never()).generateReply(eq(queued),any(),any());
            service.cancel("alice",replacement.requestId());
        } finally { release.countDown(); }
    }
    @Test void cancellationBeforeExecutorEnqueuesTaskDoesNotRetainCancelledEntry() throws Exception {
        var submitting = new CountDownLatch(1); var allowEnqueue = new CountDownLatch(1);
        var busy = new CountDownLatch(1); var releaseBusy = new CountDownLatch(1);
        var pool = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1)) {
            @Override public void execute(Runnable task) {
                if (task instanceof FutureTask<?>) {
                    submitting.countDown();
                    try { assertTrue(allowEnqueue.await(2,TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                }
                super.execute(task);
            }
        };
        try {
            pool.execute(() -> { busy.countDown(); try { releaseBusy.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            assertTrue(busy.await(2,TimeUnit.SECONDS)); service.close();
            service = new ChatStreamService(handler,mock(ConversationService.class),registry,properties,
                    writers,pool,timers,RecordingSseEmitter::new);
            var queued = command(); var opening = CompletableFuture.supplyAsync(() -> service.open(queued));
            assertTrue(submitting.await(2,TimeUnit.SECONDS)); service.cancel("alice",queued.requestId());
            allowEnqueue.countDown(); opening.get(2,TimeUnit.SECONDS);
            assertEquals(0,pool.getQueue().size(),"An already cancelled task must also be removed after late enqueue");
            verifyNoInteractions(handler);
        } finally { allowEnqueue.countDown(); releaseBusy.countDown(); pool.shutdownNow(); }
    }
    @Test void normalCompletionDoesNotCancelPersistenceThread() throws Exception {
        doAnswer(call -> { Consumer<ChatOutput> out=call.getArgument(2); out.accept(new ChatOutput("chunk",Map.of("chunk","answer"))); return null; })
                .when(handler).generateReply(any(),any(),any());
        doAnswer(call -> { assertFalse(Thread.currentThread().isInterrupted()); return null; }).when(handler).persistCompletedTurn(any(),anyString());
        var c=command(); service.open(c); emitter.get().awaitTerminal();
        verify(handler,times(1)).persistCompletedTurn(c,"answer");
        assertEquals("finished",emitter.get().eventsOfType("completion").get(0).data().get("status"));
    }
    @Test void rejectedInitialWriterDoesNotStartGeneration() {
        service.close();
        service = new ChatStreamService(handler, mock(ConversationService.class), registry, properties,
                task -> { throw new RejectedExecutionException("test sender unavailable"); }, Runnable::run, timers,
                RecordingSseEmitter::new);
        try {
            service.open(command());
            verify(handler, never()).generateReply(any(), any(), any());
            assertEquals(0, registry.conversationLeaseCount());
        } finally { Thread.interrupted(); }
    }
}
