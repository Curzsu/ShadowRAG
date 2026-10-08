package com.yizhaoqi.smartpai.service.chat;

import com.yizhaoqi.smartpai.config.ChatStreamingProperties;
import com.yizhaoqi.smartpai.model.chat.*;
import com.yizhaoqi.smartpai.service.*;
import com.yizhaoqi.smartpai.support.RecordingSseEmitter;
import com.yizhaoqi.smartpai.observability.LangfuseTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.*;
import com.yizhaoqi.smartpai.support.GenerationScript;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.yizhaoqi.smartpai.service.chat.ChatRequestContext.State.*;

class ChatStreamServiceTest {
    InMemorySpanExporter exporter; SdkTracerProvider provider;
    ChatHandler handler; ConversationService conversations; ChatRequestRegistry registry;
    ChatStreamingProperties properties; ChatStreamService service; ExecutorService workers; ExecutorService generators; ScheduledExecutorService timers;
    final AtomicReference<RecordingSseEmitter> recorded = new AtomicReference<>();
    ChatCommand command;
    @BeforeEach void setup() {
        exporter=InMemorySpanExporter.create();
        provider=SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        handler=mock(ChatHandler.class); conversations=mock(ConversationService.class); properties=new ChatStreamingProperties();
        registry=new ChatRequestRegistry(properties); workers=Executors.newFixedThreadPool(4); generators=Executors.newFixedThreadPool(4); timers=Executors.newSingleThreadScheduledExecutor();
        service=new ChatStreamService(handler,conversations,registry,properties,workers,generators,timers,timeout -> { var emitter=new RecordingSseEmitter(timeout); recorded.set(emitter); return emitter; },new LangfuseTracing(provider.get("test"),provider,"development"));
        command=new ChatCommand("alice",UUID.randomUUID().toString(),UUID.randomUUID(),"dedicated test question");
    }
    @AfterEach void close() { service.close(); workers.shutdownNow(); generators.shutdownNow(); timers.shutdownNow(); provider.close(); }
    ChatOutput chunk(String value) { return new ChatOutput("chunk",Map.of("chunk",value)); }
    void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(5); assertTrue(condition.getAsBoolean());
    }
    void assertRoot(String state, boolean committed, boolean notified) throws Exception {
        await(() -> !exporter.getFinishedSpanItems().isEmpty());
        var root=exporter.getFinishedSpanItems().get(0);
        assertEquals("chat.request",root.getName());
        assertEquals(state,root.getAttributes().get(AttributeKey.stringKey("langfuse.observation.metadata.state")));
        assertEquals(committed,root.getAttributes().get(AttributeKey.booleanKey("langfuse.observation.metadata.durable_committed")));
        assertEquals(notified,root.getAttributes().get(AttributeKey.booleanKey("langfuse.observation.metadata.network_terminal_delivered")));
    }

    @Test void reusedGenerationThreadDoesNotMixRequestTracesOrRetainScope() throws Exception {
        service.close(); generators.shutdownNow(); generators=Executors.newSingleThreadExecutor();
        var emitters=new CopyOnWriteArrayList<RecordingSseEmitter>();
        service=new ChatStreamService(handler,conversations,registry,properties,workers,generators,timers,
                timeout -> { var e=new RecordingSseEmitter(timeout); emitters.add(e); return e; },
                new LangfuseTracing(provider.get("test"),provider,"development"));
        doAnswer(invocation -> {
            var request=invocation.getArgument(1,ChatRequestContext.class);
            assertEquals(io.opentelemetry.api.trace.Span.fromContext(request.traceContext()).getSpanContext().getSpanId(),
                    io.opentelemetry.api.trace.Span.current().getSpanContext().getSpanId());
            var child=provider.get("test").spanBuilder("generation.probe").startSpan();
            child.end();
            invocation.<java.util.function.Consumer<ChatOutput>>getArgument(2).accept(chunk("answer"));
            return null;
        }).when(handler).generateReply(any(),any(),any());
        service.open(command);
        service.open(new ChatCommand("alice",UUID.randomUUID().toString(),UUID.randomUUID(),"second"));
        for(var emitter:emitters) emitter.awaitTerminal();
        await(() -> exporter.getFinishedSpanItems().size()==4);
        assertFalse(generators.submit(() -> io.opentelemetry.api.trace.Span.current().getSpanContext().isValid()).get());
        var roots=exporter.getFinishedSpanItems().stream().filter(s -> s.getName().equals("chat.request")).toList();
        assertEquals(2,roots.size()); assertNotEquals(roots.get(0).getTraceId(),roots.get(1).getTraceId());
        for(var child:exporter.getFinishedSpanItems().stream().filter(s -> s.getName().equals("generation.probe")).toList()) {
            assertTrue(roots.stream().anyMatch(root -> root.getSpanId().equals(child.getParentSpanId()) && root.getTraceId().equals(child.getTraceId())));
        }
    }
    @Test void metaPrecedesContent() throws Exception {
        GenerationScript.stub(handler, command, GenerationScript.just(chunk("one"),chunk("two")));
        service.open(command); recorded.get().awaitTerminal();
        assertEquals(List.of("meta","chunk","chunk","completion"),recorded.get().events().stream().map(ChatEventEnvelope::type).toList());
        for(int i=0;i<4;i++) assertEquals(i+1,recorded.get().events().get(i).seq());
        verify(handler).persistCompletedTurn(command,"onetwo");
    }
    @Test void simultaneousRequestsKeepTheirOwnTraceParents() throws Exception {
        service.close();
        var emitters=new CopyOnWriteArrayList<RecordingSseEmitter>();
        var bothGenerating=new CountDownLatch(2);
        var release=new CountDownLatch(1);
        var tracing=new LangfuseTracing(provider.get("test"),provider,"development");
        service=new ChatStreamService(handler,conversations,registry,properties,workers,generators,timers,
                timeout -> { var emitter=new RecordingSseEmitter(timeout); emitters.add(emitter); return emitter; },tracing);
        doAnswer(call -> {
            var context=call.getArgument(1,ChatRequestContext.class);
            bothGenerating.countDown();
            assertTrue(release.await(5,TimeUnit.SECONDS));
            try(var observation=tracing.startModel(context,1,"local-stub",new com.yizhaoqi.smartpai.config.AiProperties().getGeneration())) {
                observation.firstContent("answer");
                call.<java.util.function.Consumer<ChatOutput>>getArgument(2).accept(chunk("answer"));
            }
            return null;
        }).when(handler).generateReply(any(),any(),any());
        var other=new ChatCommand("bob",UUID.randomUUID().toString(),UUID.randomUUID(),"other question");
        try {
            service.open(command); service.open(other);
            assertTrue(bothGenerating.await(5,TimeUnit.SECONDS));
        } finally { release.countDown(); }
        for(var emitter:emitters) emitter.awaitTerminal();
        await(() -> exporter.getFinishedSpanItems().size()==4);
        var roots=exporter.getFinishedSpanItems().stream().filter(span -> "chat.request".equals(span.getName())).toList();
        assertEquals(2,roots.size()); assertNotEquals(roots.get(0).getTraceId(),roots.get(1).getTraceId());
        for(var root:roots) {
            var children=exporter.getFinishedSpanItems().stream().filter(span -> "llm.round".equals(span.getName()) && span.getTraceId().equals(root.getTraceId())).toList();
            assertEquals(1,children.size()); assertEquals(root.getSpanId(),children.get(0).getParentSpanId());
            assertEquals(root.getAttributes().get(AttributeKey.stringKey("langfuse.observation.metadata.request_id")),
                    children.get(0).getAttributes().get(AttributeKey.stringKey("langfuse.observation.metadata.request_id")));
        }
        verify(handler).persistCompletedTurn(command,"answer"); verify(handler).persistCompletedTurn(other,"answer");
    }
    @Test void rejectedCloudExportDoesNotChangeCompletionOrPersistence() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var uploads=new AtomicInteger();
        server.createContext("/",exchange -> {
            exchange.getRequestBody().readAllBytes(); uploads.incrementAndGet();
            exchange.sendResponseHeaders(401,-1); exchange.close();
        });
        server.start();
        var props=new com.yizhaoqi.smartpai.config.LangfuseProperties();
        props.setEnabled(true); props.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        props.setPublicKey("fake-public"); props.setSecretKey("fake-secret");
        try(var tracing=new com.yizhaoqi.smartpai.config.LangfuseConfiguration().langfuseTracing(props)) {
            service.close();
            service=new ChatStreamService(handler,conversations,registry,properties,workers,generators,timers,
                    timeout -> { var emitter=new RecordingSseEmitter(timeout); recorded.set(emitter); return emitter; },tracing);
            GenerationScript.stub(handler,command,GenerationScript.just(chunk("answer")));
            service.open(command); recorded.get().awaitTerminal();
            assertEquals(FINISHED,service.cancel("alice",command.requestId()).state());
            verify(handler,times(1)).persistCompletedTurn(command,"answer");
            assertEquals("finished",recorded.get().events().get(recorded.get().events().size()-1).data().get("status"));
            service.close(); // Existing cleanup completes before the provider is flushed/shut down.
            tracing.forceFlush().join(5,TimeUnit.SECONDS);
            await(() -> uploads.get()>0);
            assertEquals(0,registry.activeRequestCount());
        } finally { server.stop(0); }
    }
    @Test void intermediateRoundsAreStreamedButOnlyConfirmedFinalAnswerIsSaved() throws Exception {
        GenerationScript.stub(handler,command,GenerationScript.just(
                new ChatOutput("chunk",Map.of("roundId",1,"chunk","先查报告A")),
                new ChatOutput("round_end",Map.of("roundId",1,"kind","intermediate")),
                new ChatOutput("chunk",Map.of("roundId",2,"chunk","再查报告B")),
                new ChatOutput("round_end",Map.of("roundId",2,"kind","intermediate")),
                new ChatOutput("chunk",Map.of("roundId",3,"chunk","最终")),
                new ChatOutput("chunk",Map.of("roundId",3,"chunk","答案")),
                new ChatOutput("round_end",Map.of("roundId",3,"kind","final"))));
        service.open(command); recorded.get().awaitTerminal(); verify(handler).persistCompletedTurn(command,"最终答案");
        verify(handler,never()).persistCompletedTurn(command,"先查报告A再查报告B最终答案");
        assertEquals(List.of("meta","chunk","round_end","chunk","round_end","chunk","chunk","round_end","completion"),
                recorded.get().events().stream().map(ChatEventEnvelope::type).toList());
        for(int i=0;i<9;i++) assertEquals(i+1,recorded.get().events().get(i).seq());
    }
    @Test void missingFinalConfirmationAndRepeatedOrRegressedRoundsNeverPersist() throws Exception {
        var invalid=List.of(
                List.of(new ChatOutput("chunk",Map.of("roundId",1,"chunk","draft"))),
                List.of(new ChatOutput("round_end",Map.of("roundId",1,"kind","intermediate")),new ChatOutput("round_end",Map.of("roundId",1,"kind","final"))),
                List.of(new ChatOutput("chunk",Map.of("roundId",2,"chunk","skipped")),new ChatOutput("round_end",Map.of("roundId",2,"kind","final"))),
                List.of(new ChatOutput("round_end",Map.of("roundId",1,"kind","final")),new ChatOutput("chunk",Map.of("roundId",2,"chunk","after final"))));
        for(var outputs:invalid) {
            var cmd=new ChatCommand("alice",UUID.randomUUID().toString(),UUID.randomUUID(),"test");
            GenerationScript.stub(handler,cmd,GenerationScript.just(outputs.toArray(ChatOutput[]::new)));
            service.open(cmd); recorded.get().awaitTerminal(); verify(handler,never()).persistCompletedTurn(eq(cmd),anyString());
            assertEquals("failed",recorded.get().eventsOfType("completion").get(0).data().get("status"));
        }
    }
    @Test void mysqlCommitPrecedesFinishedEvent() throws Exception {
        GenerationScript.stub(handler, command, GenerationScript.just(chunk("answer")));
        doAnswer(invocation -> { assertTrue(recorded.get().eventsOfType("completion").isEmpty()); return null; }).when(handler).persistCompletedTurn(command,"answer");
        service.open(command); recorded.get().awaitTerminal(); assertEquals("finished",recorded.get().eventsOfType("completion").get(0).data().get("status"));
    }
    @Test void cancelWinsAndNeverPersists() throws Exception {
        GenerationScript.Controlled sink=new GenerationScript.Controlled(); CountDownLatch subscribed=new CountDownLatch(1);
        GenerationScript.stub(handler, command, sink.onStart(() -> subscribed.countDown()));
        service.open(command); assertTrue(subscribed.await(5,TimeUnit.SECONDS));
        assertEquals(CANCELLED,service.cancel("alice",command.requestId()).state()); sink.tryEmitComplete(); recorded.get().awaitTerminal();
        verify(handler,never()).persistCompletedTurn(any(),anyString()); assertEquals(1,recorded.get().eventsOfType("completion").size());
        assertRoot("CANCELLED",false,true);
    }
    @Test void completingWinsAndCancelReturnsCompleting() throws Exception {
        CountDownLatch committing=new CountDownLatch(1), release=new CountDownLatch(1);
        GenerationScript.stub(handler, command, GenerationScript.just(chunk("answer")));
        doAnswer(i -> { committing.countDown(); release.await(5,TimeUnit.SECONDS); return null; }).when(handler).persistCompletedTurn(any(),anyString());
        service.open(command); assertTrue(committing.await(5,TimeUnit.SECONDS)); assertEquals(COMPLETING,service.cancel("alice",command.requestId()).state());
        release.countDown(); recorded.get().awaitTerminal(); assertEquals("finished",recorded.get().eventsOfType("completion").get(0).data().get("status"));
    }
    @Test void repeatedCompletionDoesNotSaveAgain() throws Exception {
        GenerationScript.stub(handler, command, GenerationScript.just(chunk("answer")));
        service.open(command); recorded.get().awaitTerminal(); recorded.get().disconnect(); service.cancel("alice",command.requestId());
        verify(handler,times(1)).persistCompletedTurn(command,"answer"); assertEquals(1,recorded.get().eventsOfType("completion").size());
        await(() -> !exporter.getFinishedSpanItems().isEmpty());
        assertEquals(1,exporter.getFinishedSpanItems().size());
        var root=exporter.getFinishedSpanItems().get(0);
        assertEquals("FINISHED",root.getAttributes().get(AttributeKey.stringKey("langfuse.observation.metadata.state")));
        assertEquals(true,root.getAttributes().get(AttributeKey.booleanKey("langfuse.observation.metadata.durable_committed")));
        assertFalse(root.getAttributes().toString().contains("dedicated test question"));
        assertFalse(root.getAttributes().toString().contains("alice"));
    }
    @Test void disconnectCancelsUpstream() throws Exception {
        AtomicBoolean cancelled=new AtomicBoolean(); CountDownLatch started=new CountDownLatch(1);
        GenerationScript.stub(handler, command, GenerationScript.never().onStart(started::countDown).onCancel(() -> cancelled.set(true)));
        service.open(command); await(() -> registry.activeRequestCount()==1); await(() -> recorded.get().eventsOfType("meta").size()==1);
        assertTrue(started.await(5,TimeUnit.SECONDS));
        recorded.get().disconnect(); await(() -> registry.activeRequestCount()==0); await(cancelled::get); verify(handler,never()).persistCompletedTurn(any(),anyString());
    }
    @Test void disconnectBeforeGenerationStartsRemovesQueuedTaskWithoutInvokingUpstream() throws Exception {
        CountDownLatch occupied=new CountDownLatch(4),release=new CountDownLatch(1);
        var pool=(ThreadPoolExecutor)generators;
        try {
            for(int i=0;i<4;i++) pool.execute(() -> {
                occupied.countDown();
                try { release.await(); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(occupied.await(5,TimeUnit.SECONDS));
            service.open(command); await(() -> recorded.get().eventsOfType("meta").size()==1);
            assertEquals(1,pool.getQueue().size());
            recorded.get().disconnect(); await(() -> registry.activeRequestCount()==0);
            assertEquals(0,pool.getQueue().size()); assertEquals(0,registry.conversationLeaseCount());
            assertEquals(0,service.activeStreamCount());
        } finally { release.countDown(); }
        await(() -> pool.getActiveCount()==0);
        verify(handler,never()).generateReply(any(),any(),any()); verify(handler,never()).persistCompletedTurn(any(),anyString());
    }
    @Test void ioFailureDoesNotWriteAgain() throws Exception {
        GenerationScript.Controlled sink=new GenerationScript.Controlled(); GenerationScript.stub(handler, command, sink);
        service.open(command); await(() -> recorded.get().eventsOfType("meta").size()==1); recorded.get().failAtWrite=2;
        sink.tryEmitNext(chunk("answer")); await(() -> registry.activeRequestCount()==0); int count=recorded.get().writes(); sink.tryEmitError(new RuntimeException("test"));
        assertEquals(2,count); assertEquals(count,recorded.get().writes()); verify(handler,never()).persistCompletedTurn(any(),anyString());
    }
    @Test void terminalRaceHasOneWinner() throws Exception {
        for(int round=0;round<200;round++) {
            ChatCommand cmd=new ChatCommand("alice",command.conversationId(),UUID.randomUUID(),"test");
            GenerationScript.Controlled sink=new GenerationScript.Controlled(); CountDownLatch subscribed=new CountDownLatch(1);
            GenerationScript.stub(handler, cmd, sink.onStart(() -> subscribed.countDown()));
            service.open(cmd); RecordingSseEmitter emitter=recorded.get(); assertTrue(subscribed.await(5,TimeUnit.SECONDS));
            CompletableFuture<Void> cancel=CompletableFuture.runAsync(() -> service.cancel("alice",cmd.requestId())); sink.tryEmitComplete(); cancel.get(5,TimeUnit.SECONDS); emitter.awaitTerminal();
            assertEquals(1,emitter.eventsOfType("completion").size()); verify(handler,atMostOnce()).persistCompletedTurn(eq(cmd),anyString());
        }
    }
    @Test void persistenceFailureSendsSafeError() throws Exception {
        GenerationScript.stub(handler, command, GenerationScript.just(chunk("answer")));
        doThrow(new IllegalStateException("private database credentials")).when(handler).persistCompletedTurn(any(),anyString());
        service.open(command); recorded.get().awaitTerminal(); assertEquals("PERSISTENCE_ERROR",recorded.get().eventsOfType("error").get(0).data().get("code"));
        assertEquals("failed",recorded.get().eventsOfType("completion").get(0).data().get("status")); assertFalse(recorded.get().events().toString().contains("credentials"));
        assertRoot("FAILED",false,true);
        assertFalse(exporter.getFinishedSpanItems().get(0).getAttributes().toString().contains("private database"));
    }
    @Test void cancelBeforeSubscriptionDisposesLateHandle() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1); AtomicBoolean cancelled=new AtomicBoolean();
        GenerationScript.stub(handler, command, (context, out) -> {
            context.onCancel(() -> cancelled.set(true)); entered.countDown();
            try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        service.open(command); assertTrue(entered.await(5,TimeUnit.SECONDS));
        assertEquals(CANCELLED,service.cancel("alice",command.requestId()).state()); release.countDown(); await(cancelled::get);
        recorded.get().awaitTerminal(); verify(handler,never()).persistCompletedTurn(any(),anyString());
    }
    @Test void overflowCancelsModelAndCancelDoesNotWaitForSendLock() throws Exception {
        GenerationScript.Controlled sink=new GenerationScript.Controlled(); AtomicBoolean cancelled=new AtomicBoolean();
        GenerationScript.stub(handler, command, sink.onCancel(() -> cancelled.set(true)));
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
        service=new ChatStreamService(handler,conversations,registry,properties,workers,generators,timers,timeout -> { var emitter=new RecordingSseEmitter(timeout); recorded.set(emitter); return emitter; },new LangfuseTracing(provider.get("test"),provider,"development"));
        AtomicBoolean cancelled=new AtomicBoolean(); GenerationScript.stub(handler, command, GenerationScript.never().onCancel(() -> cancelled.set(true)));
        service.open(command); await(() -> recorded.get().eventsOfType("meta").size()==1);
        for(int i=1;i<=3;i++) { manual.heartbeat.run(); int expected=i; await(() -> recorded.get().heartbeats()==expected); }
        manual.deadline.run(); recorded.get().awaitTerminal(); await(cancelled::get);
        assertEquals("STREAM_TIMEOUT",recorded.get().eventsOfType("error").get(0).data().get("code"));
        assertEquals("timed_out",recorded.get().eventsOfType("completion").get(0).data().get("status")); verify(handler,never()).persistCompletedTurn(any(),anyString());
        assertTrue(manual.deadlineFuture.isCancelled()); assertTrue(manual.heartbeatFuture.isCancelled());
        assertRoot("TIMED_OUT",false,true);
    }
    @Test void cleanupStopsAllResources() throws Exception {
        GenerationScript.stub(handler, command, GenerationScript.just(chunk("answer"))); service.open(command); recorded.get().awaitTerminal();
        assertEquals(0,registry.activeRequestCount()); assertEquals(0,registry.conversationLeaseCount());
        assertEquals(0,service.activeStreamCount()); assertEquals(0,service.pendingEventCount());
    }
    @Test void shutdownCancelsRunningButDoesNotCancelCommittedTurn() throws Exception {
        CountDownLatch started=new CountDownLatch(1), commit=new CountDownLatch(1), release=new CountDownLatch(1); AtomicBoolean cancelled=new AtomicBoolean();
        GenerationScript.stub(handler, command, GenerationScript.never().onStart(started::countDown).onCancel(() -> cancelled.set(true)));
        ChatCommand committing=new ChatCommand("bob",UUID.randomUUID().toString(),UUID.randomUUID(),"test");
        GenerationScript.stub(handler, committing, GenerationScript.just(chunk("committed")));
        doAnswer(i -> { commit.countDown(); release.await(5,TimeUnit.SECONDS); return null; }).when(handler).persistCompletedTurn(committing,"committed");
        // Finish stubbing before worker threads call the shared mock.
        service.open(command); assertTrue(started.await(5,TimeUnit.SECONDS)); await(() -> recorded.get().eventsOfType("meta").size()==1);
        service.open(committing); RecordingSseEmitter completed=recorded.get(); assertTrue(commit.await(5,TimeUnit.SECONDS));
        var shutdown=CompletableFuture.runAsync(service::close); await(cancelled::get); release.countDown(); shutdown.get(5,TimeUnit.SECONDS); completed.awaitTerminal();
        // Shutdown may close transport before the queued terminal write; the committed outcome is authoritative.
        assertEquals(FINISHED,service.cancel("bob",committing.requestId()).state());
        verify(handler,times(1)).persistCompletedTurn(committing,"committed");
        var terminalEvents=completed.eventsOfType("completion");
        assertTrue(terminalEvents.size()<=1);
        if(!terminalEvents.isEmpty()) assertEquals("finished",terminalEvents.get(0).data().get("status"));
        verify(handler,never()).persistCompletedTurn(eq(command),anyString());
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
            GenerationScript.stub(handler, cmd, GenerationScript.error(new ChatHandler.GenerationException(code,"safe message",new IllegalStateException("private credentials"))));
            service.open(cmd); recorded.get().awaitTerminal();
            assertEquals(code,recorded.get().eventsOfType("error").get(0).data().get("code"));
            assertEquals("failed",recorded.get().eventsOfType("completion").get(0).data().get("status"));
            assertFalse(recorded.get().events().toString().contains("credentials")); verify(handler,never()).persistCompletedTurn(eq(cmd),anyString());
        }
    }    @Test void productionEmitterWaitsForMvcTransportReadiness() {
        service.close();
        service=new ChatStreamService(handler,conversations,registry,properties,workers,generators,timers,
                com.yizhaoqi.smartpai.config.ChatStreamingConfig.TransportSseEmitter::new);
        var emitter=service.open(command);
        verifyNoInteractions(handler);
        assertEquals(REGISTERED,registry.activeSnapshot().get(0).state());
        assertEquals(0,service.pendingEventCount());
        assertEquals(CANCELLED,service.cancel("alice",command.requestId()).state());
        verifyNoInteractions(handler);
    }    @Test void runtimeTerminalWriteFailureNeverRetriesOrLeaks() throws Exception {
        GenerationScript.Controlled sink=new GenerationScript.Controlled();
        GenerationScript.stub(handler, command, sink);
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
                task -> { throw new RejectedExecutionException("dedicated test overload"); },generators,timers,
                com.yizhaoqi.smartpai.config.ChatStreamingConfig.TransportSseEmitter::new);
        var emitter=service.open(command); service.transportReady(emitter);
        assertEquals(0,service.activeStreamCount()); assertEquals(0,registry.activeRequestCount());
        assertEquals(0,registry.conversationLeaseCount()); verifyNoInteractions(handler);
        assertEquals(FAILED,service.cancel("alice",command.requestId()).state());
    }    @Test void disconnectDuringCommitPreservesDurableFinishedState() throws Exception {
        CountDownLatch committing=new CountDownLatch(1), release=new CountDownLatch(1);
        GenerationScript.stub(handler, command, GenerationScript.just(chunk("answer")));
        doAnswer(invocation -> { committing.countDown(); release.await(5,TimeUnit.SECONDS); return null; })
                .when(handler).persistCompletedTurn(command,"answer");
        service.open(command); assertTrue(committing.await(5,TimeUnit.SECONDS));
        recorded.get().disconnect(); assertEquals(COMPLETING,service.cancel("alice",command.requestId()).state());
        release.countDown(); await(() -> service.activeStreamCount()==0);
        assertEquals(FINISHED,service.cancel("alice",command.requestId()).state());
        verify(handler,times(1)).persistCompletedTurn(command,"answer");
        assertTrue(recorded.get().eventsOfType("completion").isEmpty());
        assertRoot("FINISHED",true,false);
    }    @Test void transportReadinessRejectionCompletesHealthyEmitter() {
        service.close(); AtomicInteger completions=new AtomicInteger();
        service=new ChatStreamService(handler,conversations,registry,properties,
                task -> { throw new RejectedExecutionException("dedicated test overload"); },generators,timers,
                timeout -> new com.yizhaoqi.smartpai.config.ChatStreamingConfig.TransportSseEmitter(timeout) {
                    @Override public void complete() { completions.incrementAndGet(); super.complete(); }
                });
        var emitter=service.open(command); service.transportReady(emitter);
        assertEquals(1,completions.get(),"A healthy rejected HTTP transport must close immediately");
        assertEquals(0,registry.activeRequestCount()); assertEquals(0,service.activeStreamCount()); verifyNoInteractions(handler);
    }
    @Test void senderRejectionCompletesHealthyEmitterAndNeverWritesAgain() {
        service.close(); AtomicInteger dispatches=new AtomicInteger(), completions=new AtomicInteger();
        GenerationScript.stub(handler, command, GenerationScript.never());
        service=new ChatStreamService(handler,conversations,registry,properties,
                task -> { throw new RejectedExecutionException("dedicated test overload"); },generators,timers,
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
    @Test void senderRejectionAfterGenerationStartsClosesOwnedHttpResources() throws Exception {
        service.close();
        AtomicInteger dispatches = new AtomicInteger(), closes = new AtomicInteger();
        var emitChunk = new CountDownLatch(1); var entered = new CountDownLatch(1); var exited = new CountDownLatch(1);
        var http = new CompletableFuture<Void>();
        GenerationScript.stub(handler,command,(context,out) -> {
            context.generationResources().attachHttpRequest(http);
            context.generationResources().attachResponseBody(new java.io.ByteArrayInputStream(new byte[0]) {
                @Override public void close() { closes.incrementAndGet(); }
            });
            entered.countDown();
            try {
                emitChunk.await(); out.accept(chunk("answer")); context.generationResources().checkRunning();
            } finally { exited.countDown(); }
        });
        service = new ChatStreamService(handler,conversations,registry,properties,
                task -> { if (dispatches.incrementAndGet()==1) task.run(); else throw new RejectedExecutionException("test sender overload"); },
                generators,timers,timeout -> { var e = new RecordingSseEmitter(timeout); recorded.set(e); return e; });
        try {
            service.open(command); assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals(1,recorded.get().writes()); emitChunk.countDown(); recorded.get().awaitTerminal();
            assertTrue(exited.await(2,TimeUnit.SECONDS)); assertTrue(http.isCancelled()); assertEquals(1,closes.get());
            assertEquals(0,registry.activeRequestCount()); assertEquals(0,service.activeStreamCount());
            assertEquals(FAILED,service.cancel("alice",command.requestId()).state()); assertEquals(1,recorded.get().writes());
            verify(handler,never()).persistCompletedTurn(any(),anyString());
        } finally { emitChunk.countDown(); }
    }
    @Test void shutdownClosesHealthyEmitterEvenWhenQueuedTerminalSenderNeverRuns() throws Exception {
        service.close(); timers.shutdownNow();
        ManualTimers manual=new ManualTimers(); timers=manual;
        ArrayDeque<Runnable> queued=new ArrayDeque<>(); AtomicInteger completions=new AtomicInteger(); AtomicBoolean cancelled=new AtomicBoolean();
        CountDownLatch entered=new CountDownLatch(1);
        GenerationScript.stub(handler, command, GenerationScript.never().onStart(entered::countDown).onCancel(() -> cancelled.set(true)));
        service=new ChatStreamService(handler,conversations,registry,properties,queued::addLast,generators,timers,
                timeout -> {
                    var emitter=new RecordingSseEmitter(timeout) {
                        @Override public void complete() { completions.incrementAndGet(); super.complete(); }
                    };
                    recorded.set(emitter); return emitter;
                });
        service.open(command);
        assertTrue(entered.await(5,TimeUnit.SECONDS));
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
        GenerationScript.stub(handler, command, GenerationScript.just(chunk("answer")));
        doAnswer(invocation -> { committing.countDown(); release.await(20,TimeUnit.SECONDS); return null; })
                .when(handler).persistCompletedTurn(command,"answer");
        service.close();
        service=new ChatStreamService(handler,conversations,registry,properties,workers,generators,timers,
                timeout -> {
                    var emitter=new RecordingSseEmitter(timeout) {
                        @Override public void complete() { completions.incrementAndGet(); super.complete(); }
                    };
                    recorded.set(emitter); return emitter;
                },new LangfuseTracing(provider.get("test"),provider,"development"));
        try {
            service.open(command); assertTrue(committing.await(5,TimeUnit.SECONDS));
            int writes=recorded.get().writes();
            Thread shutdown=new Thread(() -> { Thread.currentThread().interrupt(); service.close(); });
            shutdown.start(); shutdown.join(5000); assertFalse(shutdown.isAlive());
            assertEquals(1,completions.get(),"An unresolved commit must not leave its healthy HTTP transport open");
            assertEquals(COMPLETING,service.cancel("alice",command.requestId()).state());
            assertEquals(writes,recorded.get().writes());
            assertEquals(1,exporter.getFinishedSpanItems().size(),"Pending root must end before SDK shutdown");
            var root=exporter.getFinishedSpanItems().get(0);
            assertEquals("COMPLETING",root.getAttributes().get(AttributeKey.stringKey("langfuse.observation.metadata.state")));
            assertEquals("unknown",root.getAttributes().get(AttributeKey.stringKey("langfuse.observation.metadata.durable_commit_outcome")));
            assertNull(root.getAttributes().get(AttributeKey.booleanKey("langfuse.observation.metadata.durable_committed")));
            provider.shutdown().join(4,TimeUnit.SECONDS);
        } finally { release.countDown(); }
        await(() -> registry.activeRequestCount()==0);
        assertEquals(FINISHED,service.cancel("alice",command.requestId()).state());
        assertEquals(1,completions.get()); verify(handler,times(1)).persistCompletedTurn(command,"answer");
    }
    @Test void shutdownNeverCompletesEmitterAfterIoFailure() throws Exception {
        service.close(); timers.shutdownNow();
        ManualTimers manual=new ManualTimers(); timers=manual;
        ArrayDeque<Runnable> queued=new ArrayDeque<>(); AtomicInteger completions=new AtomicInteger();
        GenerationScript.stub(handler, command, GenerationScript.never());
        service=new ChatStreamService(handler,conversations,registry,properties,queued::addLast,generators,timers,
                timeout -> {
                    var emitter=new RecordingSseEmitter(timeout) {
                        @Override public void complete() { completions.incrementAndGet(); super.complete(); }
                    };
                    emitter.failAtWrite=1; recorded.set(emitter); return emitter;
                });
        service.open(command);
        await(() -> registry.activeSnapshot().stream().anyMatch(c -> c.state()==RUNNING));
        queued.removeFirst().run(); // meta fails with IOException
        assertEquals(0,registry.activeRequestCount());
        service.close();
        while(!queued.isEmpty()) queued.removeFirst().run();
        assertEquals(1,recorded.get().writes()); assertEquals(0,completions.get());
        assertTrue(manual.deadlineFuture.isCancelled()); assertTrue(manual.heartbeatFuture.isCancelled());
    }
}







