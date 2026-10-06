package com.yizhaoqi.smartpai.support;

import com.yizhaoqi.smartpai.model.chat.ChatCommand;
import com.yizhaoqi.smartpai.service.chat.ChatRequestContext;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only observation of a real ordinary callback producer on a worker. */
public final class ChatGenerationProbe<T> {
    @FunctionalInterface public interface Generation<T> { void run(ChatRequestContext context, Consumer<T> output); }
    private record Event(Object value, Throwable error, boolean complete) { }
    private final Generation<T> generation;
    private final List<Consumer<Event>> expectations = new ArrayList<>();
    private boolean cancel;
    private ChatGenerationProbe(Generation<T> generation) { this.generation = generation; }
    public static <T> ChatGenerationProbe<T> create(Generation<T> generation) { return new ChatGenerationProbe<>(generation); }
    public ChatGenerationProbe<T> assertNext(Consumer<T> assertion) {
        expectations.add(event -> { assertNull(event.error()); assertFalse(event.complete(), "Expected another output");
            @SuppressWarnings("unchecked") T value=(T)event.value(); assertion.accept(value); }); return this;
    }
    @SafeVarargs public final ChatGenerationProbe<T> expectNext(T... values) {
        for (T value:values) assertNext(actual -> assertEquals(value,actual)); return this;
    }
    public ChatGenerationProbe<T> expectNextCount(int count) { for(int i=0;i<count;i++) assertNext(ignored -> {}); return this; }
    public ChatGenerationProbe<T> expectComplete() { expectations.add(event -> { assertNull(event.error()); assertTrue(event.complete()); }); return this; }
    public ChatGenerationProbe<T> expectError(Class<? extends Throwable> type) { return expectErrorSatisfies(error -> assertInstanceOf(type,error)); }
    public ChatGenerationProbe<T> expectError() { return expectErrorSatisfies(ignored -> {}); }
    public ChatGenerationProbe<T> expectErrorSatisfies(Consumer<Throwable> assertion) {
        expectations.add(event -> { assertNotNull(event.error(), "Expected a generation failure"); assertion.accept(event.error()); }); return this;
    }
    public ChatGenerationProbe<T> thenCancel() { cancel=true; return this; }
    public void verify(Duration timeout) {
        var events = new LinkedBlockingQueue<Event>();
        var running = start(value -> events.add(new Event(value,null,false)),
                error -> events.add(new Event(null,error,false)), () -> events.add(new Event(null,null,true)));
        long deadline=System.nanoTime()+timeout.toNanos();
        try {
            for(var expectation:expectations) {
                var event=events.poll(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                assertNotNull(event,"Timed out waiting for callback/termination"); expectation.accept(event);
            }
            if(cancel) running.cancel();
            assertTrue(running.exited.await(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS),"Worker did not exit");
        } catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        finally { running.cancel(); }
    }
    public Running start(Consumer<T> output) { return start(output, ignored -> {}, () -> {}); }
    private Running start(Consumer<T> output, Consumer<Throwable> errors, Runnable complete) {
        var context=new ChatRequestContext(new ChatCommand("test", "test", UUID.randomUUID(), "test"));
        var exited=new CountDownLatch(1);
        var task=new FutureTask<Void>(() -> {
            try { generation.run(context,output); complete.run(); }
            catch(Throwable error) { errors.accept(error); }
            finally { exited.countDown(); }
            return null;
        });
        context.generationResources().attachGenerationTask(task);
        var thread=new Thread(task,"test-chat-worker-probe"); thread.setDaemon(true); thread.start();
        return new Running(context,exited);
    }
    public static final class Running {
        private final ChatRequestContext context;
        public final CountDownLatch exited;
        Running(ChatRequestContext context,CountDownLatch exited) { this.context=context; this.exited=exited; }
        public void cancel() { context.generationResources().stop(); }
    }
}
