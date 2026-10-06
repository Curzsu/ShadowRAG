package com.yizhaoqi.smartpai.support;

import com.yizhaoqi.smartpai.model.chat.*;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.chat.ChatRequestContext;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** Controlled external-generation double; all lifecycle and emitter behavior stays real. */
@FunctionalInterface
public interface GenerationScript {
    void run(ChatRequestContext context, Consumer<ChatOutput> output) throws Exception;
    static GenerationScript just(ChatOutput... outputs) { return (context,out) -> { for(var output:outputs) out.accept(output); }; }
    static GenerationScript empty() { return just(); }
    static GenerationScript error(RuntimeException error) { return (context,out) -> { throw error; }; }
    static Controlled never() { return new Controlled(); }
    static void stub(ChatHandler handler, ChatCommand command, GenerationScript script) {
        doAnswer(call -> { script.run(call.getArgument(1),call.getArgument(2)); return null; })
                .when(handler).generateReply(eq(command),any(),any());
    }
    static void stubAny(ChatHandler handler, GenerationScript script) {
        doAnswer(call -> { script.run(call.getArgument(1),call.getArgument(2)); return null; })
                .when(handler).generateReply(any(),any(),any());
    }
    final class Controlled implements GenerationScript {
        private record Signal(ChatOutput output, RuntimeException error, boolean complete) { }
        private final BlockingQueue<Signal> signals=new LinkedBlockingQueue<>();
        private Runnable started=() -> {}, cancelled=() -> {};
        public Controlled onStart(Runnable callback) { started=callback; return this; }
        public Controlled onCancel(Runnable callback) { cancelled=callback; return this; }
        public void tryEmitNext(ChatOutput output) { signals.add(new Signal(output,null,false)); }
        public void tryEmitComplete() { signals.add(new Signal(null,null,true)); }
        public void tryEmitError(RuntimeException error) { signals.add(new Signal(null,error,false)); }
        @Override public void run(ChatRequestContext context, Consumer<ChatOutput> output) throws Exception {
            var notified = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable notify = () -> { if(notified.compareAndSet(false,true)) cancelled.run(); };
            context.onCancel(notify);
            started.run();
            try {
                while(true) {
                    context.generationResources().checkRunning();
                    var signal=signals.take();
                    if(signal.error()!=null) throw signal.error();
                    if(signal.complete()) return;
                    output.accept(signal.output());
                }
            } finally { if(context.generationResources().isStopped() || Thread.currentThread().isInterrupted()) notify.run(); }
        }
    }
}
