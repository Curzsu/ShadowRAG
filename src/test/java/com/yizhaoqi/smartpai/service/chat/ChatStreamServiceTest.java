package com.yizhaoqi.smartpai.service.chat;

import com.yizhaoqi.smartpai.config.ChatStreamingProperties;
import com.yizhaoqi.smartpai.model.chat.*;
import com.yizhaoqi.smartpai.service.*;
import com.yizhaoqi.smartpai.support.RecordingSseEmitter;
import org.junit.jupiter.api.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yizhaoqi.smartpai.service.chat.ChatRequestContext.State.*;

class ChatStreamServiceTest {
    ChatHandler handler; ConversationService conversations; ChatRequestRegistry registry;
    ChatStreamingProperties properties; ChatStreamService service; ExecutorService workers; ScheduledExecutorService timers;
    final AtomicReference<RecordingSseEmitter> recorded = new AtomicReference<>();
    ChatCommand command;
    @BeforeEach void setup() {
        handler=mock(ChatHandler.class); conversations=mock(ConversationService.class); properties=new ChatStreamingProperties();
        registry=new ChatRequestRegistry(properties); workers=Executors.newFixedThreadPool(4); timers=Executors.newSingleThreadScheduledExecutor();
        service=new ChatStreamService(handler,conversations,registry,properties,workers,timers,timeout -> { var emitter=new RecordingSseEmitter(timeout); recorded.set(emitter); return emitter; });
        command=new ChatCommand("alice",UUID.randomUUID().toString(),UUID.randomUUID(),"dedicated test question");
    }
    @AfterEach void close() { service.close(); workers.shutdownNow(); timers.shutdownNow(); }
    ChatOutput chunk(String value) { return new ChatOutput("chunk",Map.of("chunk",value)); }
    void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(5); assertTrue(condition.getAsBoolean());
    }
    @Test void metaPrecedesContent() throws Exception {
        when(handler.generateReply(command)).thenReturn(Flux.just(chunk("one"),chunk("two")));
        service.open(command); recorded.get().awaitTerminal();
        assertEquals(List.of("meta","chunk","chunk","completion"),recorded.get().events().stream().map(ChatEventEnvelope::type).toList());
        for(int i=0;i<4;i++) assertEquals(i+1,recorded.get().events().get(i).seq());
        verify(handler).persistCompletedTurn(command,"onetwo");
    }
    @Test void mysqlCommitPrecedesFinishedEvent() throws Exception {
        when(handler.generateReply(command)).thenReturn(Flux.just(chunk("answer")));
        doAnswer(invocation -> { assertTrue(recorded.get().eventsOfType("completion").isEmpty()); return null; }).when(handler).persistCompletedTurn(command,"answer");
        service.open(command); recorded.get().awaitTerminal(); assertEquals("finished",recorded.get().eventsOfType("completion").get(0).data().get("status"));
    }
    @Test void cancelWinsAndNeverPersists() throws Exception {
        Sinks.Many<ChatOutput> sink=Sinks.many().unicast().onBackpressureBuffer(); CountDownLatch subscribed=new CountDownLatch(1);
        when(handler.generateReply(command)).thenReturn(sink.asFlux().doOnSubscribe(s -> subscribed.countDown()));
        service.open(command); assertTrue(subscribed.await(5,TimeUnit.SECONDS));
        assertEquals(CANCELLED,service.cancel("alice",command.requestId()).state()); sink.tryEmitComplete(); recorded.get().awaitTerminal();
        verify(handler,never()).persistCompletedTurn(any(),anyString()); assertEquals(1,recorded.get().eventsOfType("completion").size());
    }
    @Test void completingWinsAndCancelReturnsCompleting() throws Exception {
        CountDownLatch committing=new CountDownLatch(1), release=new CountDownLatch(1);
        when(handler.generateReply(command)).thenReturn(Flux.just(chunk("answer")));
        doAnswer(i -> { committing.countDown(); release.await(5,TimeUnit.SECONDS); return null; }).when(handler).persistCompletedTurn(any(),anyString());
        service.open(command); assertTrue(committing.await(5,TimeUnit.SECONDS)); assertEquals(COMPLETING,service.cancel("alice",command.requestId()).state());
        release.countDown(); recorded.get().awaitTerminal(); assertEquals("finished",recorded.get().eventsOfType("completion").get(0).data().get("status"));
    }
    @Test void repeatedCompletionDoesNotSaveAgain() throws Exception {
        when(handler.generateReply(command)).thenReturn(Flux.just(chunk("answer")));
        service.open(command); recorded.get().awaitTerminal(); recorded.get().disconnect(); service.cancel("alice",command.requestId());
        verify(handler,times(1)).persistCompletedTurn(command,"answer"); assertEquals(1,recorded.get().eventsOfType("completion").size());
    }
    @Test void disconnectCancelsUpstream() throws Exception {
        AtomicBoolean cancelled=new AtomicBoolean(); when(handler.generateReply(command)).thenReturn(Flux.<ChatOutput>never().doOnCancel(() -> cancelled.set(true)));
        service.open(command); await(() -> registry.activeRequestCount()==1); await(() -> recorded.get().eventsOfType("meta").size()==1);
        recorded.get().disconnect(); await(() -> registry.activeRequestCount()==0); await(cancelled::get); verify(handler,never()).persistCompletedTurn(any(),anyString());
    }
    @Test void ioFailureDoesNotWriteAgain() throws Exception {
        Sinks.Many<ChatOutput> sink=Sinks.many().unicast().onBackpressureBuffer(); when(handler.generateReply(command)).thenReturn(sink.asFlux());
        service.open(command); await(() -> recorded.get().eventsOfType("meta").size()==1); recorded.get().failAtWrite=2;
        sink.tryEmitNext(chunk("answer")); await(() -> registry.activeRequestCount()==0); int count=recorded.get().writes(); sink.tryEmitError(new RuntimeException("test"));
        assertEquals(2,count); assertEquals(count,recorded.get().writes()); verify(handler,never()).persistCompletedTurn(any(),anyString());
    }
    @Test void terminalRaceHasOneWinner() throws Exception {
        for(int round=0;round<200;round++) {
            ChatCommand cmd=new ChatCommand("alice",command.conversationId(),UUID.randomUUID(),"test");
            Sinks.Many<ChatOutput> sink=Sinks.many().unicast().onBackpressureBuffer(); CountDownLatch subscribed=new CountDownLatch(1);
            when(handler.generateReply(cmd)).thenReturn(sink.asFlux().doOnSubscribe(s -> subscribed.countDown()));
            service.open(cmd); RecordingSseEmitter emitter=recorded.get(); assertTrue(subscribed.await(5,TimeUnit.SECONDS));
            CompletableFuture<Void> cancel=CompletableFuture.runAsync(() -> service.cancel("alice",cmd.requestId())); sink.tryEmitComplete(); cancel.get(5,TimeUnit.SECONDS); emitter.awaitTerminal();
            assertEquals(1,emitter.eventsOfType("completion").size()); verify(handler,atMostOnce()).persistCompletedTurn(eq(cmd),anyString());
        }
    }
    @Test void persistenceFailureSendsSafeError() throws Exception {
        when(handler.generateReply(command)).thenReturn(Flux.just(chunk("answer")));
        doThrow(new IllegalStateException("private database credentials")).when(handler).persistCompletedTurn(any(),anyString());
        service.open(command); recorded.get().awaitTerminal(); assertEquals("PERSISTENCE_ERROR",recorded.get().eventsOfType("error").get(0).data().get("code"));
        assertEquals("failed",recorded.get().eventsOfType("completion").get(0).data().get("status")); assertFalse(recorded.get().events().toString().contains("credentials"));
    }
    @Test void cancelBeforeSubscriptionDisposesLateHandle() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1); AtomicBoolean cancelled=new AtomicBoolean();
        when(handler.generateReply(command)).thenReturn(Flux.<ChatOutput>create(sink -> {
            sink.onCancel(() -> cancelled.set(true)); entered.countDown();
            try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        service.open(command); assertTrue(entered.await(5,TimeUnit.SECONDS));
        assertEquals(CANCELLED,service.cancel("alice",command.requestId()).state()); release.countDown(); await(cancelled::get);
        recorded.get().awaitTerminal(); verify(handler,never()).persistCompletedTurn(any(),anyString());
    }
    @Test void overflowCancelsModelAndCancelDoesNotWaitForSendLock() throws Exception {
        Sinks.Many<ChatOutput> sink=Sinks.many().unicast().onBackpressureBuffer(); AtomicBoolean cancelled=new AtomicBoolean();
        when(handler.generateReply(command)).thenReturn(sink.asFlux().doOnCancel(() -> cancelled.set(true)));
        service.open(command); await(() -> recorded.get().eventsOfType("meta").size()==1);
        recorded.get().enteredSend=new CountDownLatch(1); recorded.get().blockSend=new CountDownLatch(1);
        sink.tryEmitNext(chunk("blocked")); assertTrue(recorded.get().enteredSend.await(5,TimeUnit.SECONDS));
        for(int i=0;i<66;i++) sink.tryEmitNext(chunk("queued"));
        await(cancelled::get); var cancel=CompletableFuture.supplyAsync(() -> service.cancel("alice",command.requestId()));
        assertEquals(FAILED,cancel.get(500,TimeUnit.MILLISECONDS).state());
        recorded.get().blockSend.countDown(); recorded.get().awaitTerminal();
        assertEquals("STREAM_OVERFLOW",recorded.get().eventsOfType("error").get(0).data().get("code")); verify(handler,never()).persistCompletedTurn(any(),anyString());
    }
    @Test void heartbeatContinuesDuringToolGapAndDoesNotResetGenerationDeadline() throws Exception {
        ManualTimers manual=new ManualTimers(); timers.shutdownNow(); timers=manual;
        service=new ChatStreamService(handler,conversations,registry,properties,workers,timers,timeout -> { var emitter=new RecordingSseEmitter(timeout); recorded.set(emitter); return emitter; });
        AtomicBoolean cancelled=new AtomicBoolean(); when(handler.generateReply(command)).thenReturn(Flux.<ChatOutput>never().doOnCancel(() -> cancelled.set(true)));
        service.open(command); await(() -> recorded.get().eventsOfType("meta").size()==1);
        for(int i=1;i<=3;i++) { manual.heartbeat.run(); int expected=i; await(() -> recorded.get().heartbeats()==expected); }
        manual.deadline.run(); recorded.get().awaitTerminal(); await(cancelled::get);
        assertEquals("STREAM_TIMEOUT",recorded.get().eventsOfType("error").get(0).data().get("code"));
        assertEquals("timed_out",recorded.get().eventsOfType("completion").get(0).data().get("status")); verify(handler,never()).persistCompletedTurn(any(),anyString());
        assertTrue(manual.deadlineFuture.isCancelled()); assertTrue(manual.heartbeatFuture.isCancelled());
    }
    @Test void cleanupStopsAllResources() throws Exception {
        when(handler.generateReply(command)).thenReturn(Flux.just(chunk("answer"))); service.open(command); recorded.get().awaitTerminal();
        assertEquals(0,registry.activeRequestCount()); assertEquals(0,registry.conversationLeaseCount());
        assertEquals(0,service.activeStreamCount()); assertEquals(0,service.pendingEventCount());
    }
    @Test void shutdownCancelsRunningButDoesNotCancelCommittedTurn() throws Exception {
        CountDownLatch commit=new CountDownLatch(1), release=new CountDownLatch(1); AtomicBoolean cancelled=new AtomicBoolean();
        when(handler.generateReply(command)).thenReturn(Flux.<ChatOutput>never().doOnCancel(() -> cancelled.set(true)));
        service.open(command); await(() -> recorded.get().eventsOfType("meta").size()==1);
        ChatCommand committing=new ChatCommand("bob",UUID.randomUUID().toString(),UUID.randomUUID(),"test");
        when(handler.generateReply(committing)).thenReturn(Flux.just(chunk("committed")));
        doAnswer(i -> { commit.countDown(); release.await(5,TimeUnit.SECONDS); return null; }).when(handler).persistCompletedTurn(committing,"committed");
        service.open(committing); RecordingSseEmitter completed=recorded.get(); assertTrue(commit.await(5,TimeUnit.SECONDS));
        var shutdown=CompletableFuture.runAsync(service::close); await(cancelled::get); release.countDown(); shutdown.get(5,TimeUnit.SECONDS); completed.awaitTerminal();
        assertEquals("finished",completed.eventsOfType("completion").get(0).data().get("status")); verify(handler,never()).persistCompletedTurn(eq(command),anyString());
        assertThrows(ChatRequestException.class,() -> service.open(new ChatCommand("alice",UUID.randomUUID().toString(),UUID.randomUUID(),"test")));
    }
    static class ManualTimers extends ScheduledThreadPoolExecutor {
        Runnable deadline, heartbeat; ScheduledFuture<?> deadlineFuture,heartbeatFuture;
        ManualTimers() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable command,long delay,TimeUnit unit) { deadline=command; return deadlineFuture=new ManualFuture(command); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,long delay,long period,TimeUnit unit) { heartbeat=command; return heartbeatFuture=new ManualFuture(command); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,long delay,long period,TimeUnit unit) { heartbeat=command; return heartbeatFuture=new ManualFuture(command); }
    }
    static class ManualFuture extends FutureTask<Void> implements ScheduledFuture<Void> {
        ManualFuture(Runnable task) { super(task,null); }
        public long getDelay(TimeUnit unit) { return 0; }
        public int compareTo(Delayed other) { return 0; }
    }    @Test void ownershipFailureRejectsBeforeModelAndHistory() {
        doThrow(new com.yizhaoqi.smartpai.exception.CustomException("forbidden",org.springframework.http.HttpStatus.FORBIDDEN))
            .when(conversations).requireOwnedConversation(command.username(),command.conversationId());
        ChatRequestException error=assertThrows(ChatRequestException.class,() -> service.open(command));
        assertEquals("CONVERSATION_FORBIDDEN",error.getErrorCode()); verifyNoInteractions(handler); assertEquals(0,registry.activeRequestCount());
    }    @Test void typedGenerationErrorsUseContractCodes() throws Exception {
        for(String code:List.of("MODEL_ERROR","TOOL_ERROR","HISTORY_ERROR")) {
            ChatCommand cmd=new ChatCommand("alice",command.conversationId(),UUID.randomUUID(),"test");
            when(handler.generateReply(cmd)).thenReturn(Flux.error(new ChatHandler.GenerationException(code,"safe message",new IllegalStateException("private credentials"))));
            service.open(cmd); recorded.get().awaitTerminal();
            assertEquals(code,recorded.get().eventsOfType("error").get(0).data().get("code"));
            assertEquals("failed",recorded.get().eventsOfType("completion").get(0).data().get("status"));
            assertFalse(recorded.get().events().toString().contains("credentials")); verify(handler,never()).persistCompletedTurn(eq(cmd),anyString());
        }
    }    @Test void productionEmitterWaitsForMvcTransportReadiness() {
        service.close();
        service=new ChatStreamService(handler,conversations,registry,properties,workers,timers,
                com.yizhaoqi.smartpai.config.ChatStreamingConfig.TransportSseEmitter::new);
        var emitter=service.open(command);
        verifyNoInteractions(handler);
        assertEquals(REGISTERED,registry.activeSnapshot().get(0).state());
        assertEquals(0,service.pendingEventCount());
        assertEquals(CANCELLED,service.cancel("alice",command.requestId()).state());
        verifyNoInteractions(handler);
    }    @Test void runtimeTerminalWriteFailureNeverRetriesOrLeaks() throws Exception {
        Sinks.Many<ChatOutput> sink=Sinks.many().unicast().onBackpressureBuffer();
        when(handler.generateReply(command)).thenReturn(sink.asFlux());
        service.open(command); await(() -> recorded.get().eventsOfType("meta").size()==1);
        recorded.get().runtimeFailureAtWrite=3;
        sink.tryEmitNext(chunk("answer")); sink.tryEmitComplete();
        await(() -> service.activeStreamCount()==0);
        assertEquals(3,recorded.get().writes()); assertEquals(FINISHED,service.cancel("alice",command.requestId()).state());
        verify(handler,times(1)).persistCompletedTurn(command,"answer");
    }
    @Test void transportReadyWorkerRejectionImmediatelyCleansRegisteredRequest() {
        service.close();
        service=new ChatStreamService(handler,conversations,registry,properties,
                task -> { throw new RejectedExecutionException("dedicated test overload"); },timers,
                com.yizhaoqi.smartpai.config.ChatStreamingConfig.TransportSseEmitter::new);
        var emitter=service.open(command); service.transportReady(emitter);
        assertEquals(0,service.activeStreamCount()); assertEquals(0,registry.activeRequestCount());
        assertEquals(0,registry.conversationLeaseCount()); verifyNoInteractions(handler);
        assertEquals(FAILED,service.cancel("alice",command.requestId()).state());
    }    @Test void disconnectDuringCommitPreservesDurableFinishedState() throws Exception {
        CountDownLatch committing=new CountDownLatch(1), release=new CountDownLatch(1);
        when(handler.generateReply(command)).thenReturn(Flux.just(chunk("answer")));
        doAnswer(invocation -> { committing.countDown(); release.await(5,TimeUnit.SECONDS); return null; })
                .when(handler).persistCompletedTurn(command,"answer");
        service.open(command); assertTrue(committing.await(5,TimeUnit.SECONDS));
        recorded.get().disconnect(); assertEquals(COMPLETING,service.cancel("alice",command.requestId()).state());
        release.countDown(); await(() -> service.activeStreamCount()==0);
        assertEquals(FINISHED,service.cancel("alice",command.requestId()).state());
        verify(handler,times(1)).persistCompletedTurn(command,"answer");
        assertTrue(recorded.get().eventsOfType("completion").isEmpty());
    }    @Test void transportReadinessRejectionCompletesHealthyEmitter() {
        service.close(); AtomicInteger completions=new AtomicInteger();
        service=new ChatStreamService(handler,conversations,registry,properties,
                task -> { throw new RejectedExecutionException("dedicated test overload"); },timers,
                timeout -> new com.yizhaoqi.smartpai.config.ChatStreamingConfig.TransportSseEmitter(timeout) {
                    @Override public void complete() { completions.incrementAndGet(); super.complete(); }
                });
        var emitter=service.open(command); service.transportReady(emitter);
        assertEquals(1,completions.get(),"A healthy rejected HTTP transport must close immediately");
        assertEquals(0,registry.activeRequestCount()); assertEquals(0,service.activeStreamCount()); verifyNoInteractions(handler);
    }
    @Test void senderRejectionCompletesHealthyEmitterAndNeverWritesAgain() {
        service.close(); AtomicInteger dispatches=new AtomicInteger(), completions=new AtomicInteger();
        when(handler.generateReply(command)).thenReturn(Flux.never());
        service=new ChatStreamService(handler,conversations,registry,properties,
                task -> { if(dispatches.incrementAndGet()==1) task.run(); else throw new RejectedExecutionException("dedicated test overload"); },timers,
                timeout -> {
                    var emitter=new RecordingSseEmitter(timeout) {
                        @Override public void complete() { completions.incrementAndGet(); super.complete(); }
                    };
                    recorded.set(emitter); return emitter;
                });
        service.open(command);
        assertEquals(1,completions.get(),"A rejected serial sender must close its healthy HTTP transport");
        assertEquals(0,recorded.get().writes()); assertEquals(0,registry.activeRequestCount()); assertEquals(0,service.activeStreamCount());
        assertEquals(FAILED,service.cancel("alice",command.requestId()).state());
        verify(handler,never()).persistCompletedTurn(any(),anyString());
    }
    @Test void shutdownClosesHealthyEmitterEvenWhenQueuedTerminalSenderNeverRuns() {
        service.close(); timers.shutdownNow();
        ManualTimers manual=new ManualTimers(); timers=manual;
        ArrayDeque<Runnable> queued=new ArrayDeque<>(); AtomicInteger completions=new AtomicInteger(); AtomicBoolean cancelled=new AtomicBoolean();
        when(handler.generateReply(command)).thenReturn(Flux.<ChatOutput>never().doOnCancel(() -> cancelled.set(true)));
        service=new ChatStreamService(handler,conversations,registry,properties,queued::addLast,timers,
                timeout -> {
                    var emitter=new RecordingSseEmitter(timeout) {
                        @Override public void complete() { completions.incrementAndGet(); super.complete(); }
                    };
                    recorded.set(emitter); return emitter;
                });
        service.open(command);
        queued.removeFirst().run(); // subscribe
        queued.removeFirst().run(); // send meta; leave later terminal sender queued
        assertEquals(1,recorded.get().writes());
        service.close();
        assertEquals(1,completions.get(),"Shutdown must close a healthy transport without waiting for the sender");
        assertTrue(cancelled.get());
        assertEquals(CANCELLED,service.cancel("alice",command.requestId()).state());
        assertEquals(0,registry.activeRequestCount()); assertEquals(0,registry.conversationLeaseCount());
        assertEquals(0,service.activeStreamCount()); assertTrue(manual.deadlineFuture.isCancelled()); assertTrue(manual.heartbeatFuture.isCancelled());
        while(!queued.isEmpty()) queued.removeFirst().run();
        assertEquals(1,recorded.get().writes()); assertEquals(1,completions.get());
        verify(handler,never()).persistCompletedTurn(any(),anyString());
    }
    @Test void shutdownClosesHealthyEmitterWhileCommitKeepsItsOutcome() throws Exception {
        CountDownLatch committing=new CountDownLatch(1), release=new CountDownLatch(1); AtomicInteger completions=new AtomicInteger();
        when(handler.generateReply(command)).thenReturn(Flux.just(chunk("answer")));
        doAnswer(invocation -> { committing.countDown(); release.await(20,TimeUnit.SECONDS); return null; })
                .when(handler).persistCompletedTurn(command,"answer");
        service.close();
        service=new ChatStreamService(handler,conversations,registry,properties,workers,timers,
                timeout -> {
                    var emitter=new RecordingSseEmitter(timeout) {
                        @Override public void complete() { completions.incrementAndGet(); super.complete(); }
                    };
                    recorded.set(emitter); return emitter;
                });
        try {
            service.open(command); assertTrue(committing.await(5,TimeUnit.SECONDS));
            int writes=recorded.get().writes();
            Thread shutdown=new Thread(() -> { Thread.currentThread().interrupt(); service.close(); });
            shutdown.start(); shutdown.join(5000); assertFalse(shutdown.isAlive());
            assertEquals(1,completions.get(),"An unresolved commit must not leave its healthy HTTP transport open");
            assertEquals(COMPLETING,service.cancel("alice",command.requestId()).state());
            assertEquals(writes,recorded.get().writes());
        } finally { release.countDown(); }
        await(() -> registry.activeRequestCount()==0);
        assertEquals(FINISHED,service.cancel("alice",command.requestId()).state());
        assertEquals(1,completions.get()); verify(handler,times(1)).persistCompletedTurn(command,"answer");
    }
    @Test void shutdownNeverCompletesEmitterAfterIoFailure() {
        service.close(); timers.shutdownNow();
        ManualTimers manual=new ManualTimers(); timers=manual;
        ArrayDeque<Runnable> queued=new ArrayDeque<>(); AtomicInteger completions=new AtomicInteger();
        when(handler.generateReply(command)).thenReturn(Flux.never());
        service=new ChatStreamService(handler,conversations,registry,properties,queued::addLast,timers,
                timeout -> {
                    var emitter=new RecordingSseEmitter(timeout) {
                        @Override public void complete() { completions.incrementAndGet(); super.complete(); }
                    };
                    emitter.failAtWrite=1; recorded.set(emitter); return emitter;
                });
        service.open(command);
        queued.removeFirst().run(); // subscribe
        queued.removeFirst().run(); // meta fails with IOException
        assertEquals(0,registry.activeRequestCount());
        service.close();
        while(!queued.isEmpty()) queued.removeFirst().run();
        assertEquals(1,recorded.get().writes()); assertEquals(0,completions.get());
        assertTrue(manual.deadlineFuture.isCancelled()); assertTrue(manual.heartbeatFuture.isCancelled());
    }
}







