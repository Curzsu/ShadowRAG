package com.yizhaoqi.smartpai.service.chat;

import com.yizhaoqi.smartpai.config.ChatStreamingProperties;
import com.yizhaoqi.smartpai.config.ChatStreamingConfig;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.chat.ChatCommand;
import com.yizhaoqi.smartpai.model.chat.ChatEventEnvelope;
import com.yizhaoqi.smartpai.model.chat.ChatOutput;
import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.service.ConversationService;
import com.yizhaoqi.smartpai.observability.LangfuseTracing;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

import static com.yizhaoqi.smartpai.service.chat.ChatRequestContext.State.*;

/** Owns the generation task and serialized downstream writer for each request. */
@Service
public class ChatStreamService implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ChatStreamService.class);
    private final ChatHandler handler;
    private final ConversationService conversations;
    private final ChatRequestRegistry registry;
    private final ChatStreamingProperties properties;
    private final Executor workers;
    private final Executor generators;
    private final ScheduledExecutorService timers;
    private final LongFunction<SseEmitter> emitterFactory;
    private final LangfuseTracing tracing;
    private final ConcurrentHashMap<ChatRequestContext, Stream> streams = new ConcurrentHashMap<>();
    private final Object admission = new Object();
    private boolean accepting = true;

    public ChatStreamService(ChatHandler handler, ConversationService conversations,
                             ChatRequestRegistry registry, ChatStreamingProperties properties,
                             Executor workers, Executor generators, ScheduledExecutorService timers,
                             LongFunction<SseEmitter> emitterFactory) {
        this(handler, conversations, registry, properties, workers, generators, timers, emitterFactory, LangfuseTracing.noop());
    }

    @Autowired
    public ChatStreamService(ChatHandler handler, ConversationService conversations,
                             ChatRequestRegistry registry, ChatStreamingProperties properties,
                             @Qualifier("chatStreamingExecutor") Executor workers,
                             @Qualifier("chatGenerationExecutor") Executor generators,
                             @Qualifier("chatStreamingTimers") ScheduledExecutorService timers,
                             @Qualifier("chatSseEmitterFactory") LongFunction<SseEmitter> emitterFactory,
                             LangfuseTracing tracing) {
        this.handler = handler;
        this.conversations = conversations;
        this.registry = registry;
        this.properties = properties;
        this.workers = workers;
        this.generators = generators;
        this.timers = timers;
        this.emitterFactory = emitterFactory;
        this.tracing = tracing;
    }

    public SseEmitter open(ChatCommand command) {
        validate(command);
        try {
            conversations.requireOwnedConversation(command.username(), command.conversationId());
        } catch (CustomException error) {
            if (error.getStatus() == HttpStatus.FORBIDDEN) {
                throw new ChatRequestException("CONVERSATION_FORBIDDEN", HttpStatus.FORBIDDEN, "无权访问此会话");
            }
            if (error.getStatus() == HttpStatus.NOT_FOUND) {
                throw new ChatRequestException("CONVERSATION_NOT_FOUND", HttpStatus.NOT_FOUND, "会话不存在");
            }
            throw startFailed();
        } catch (ChatRequestException error) {
            throw error;
        } catch (RuntimeException error) {
            throw startFailed();
        }

        synchronized (admission) {
            if (!accepting) {
                throw new ChatRequestException("CHAT_CAPACITY_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS, "聊天服务正在关闭");
            }
            ChatRequestContext context = registry.register(command);
            context.setTraceContext(tracing.startChat(command));
            Stream stream = null;
            try {
                stream = new Stream(context, emitterFactory.apply(properties.getEmitterTimeoutMs()));
                streams.put(context, stream);
                stream.initialize();
                if (stream.transportReady.get()) stream.start();
                return stream.emitter;
            } catch (RuntimeException error) {
                if (stream != null) {
                    stream.writable.set(false);
                    stream.terminate(FAILED, "INTERNAL_ERROR");
                    stream.remove();
                } else {
                    context.tryTransition(REGISTERED, FAILED);
                    registry.finish(context, FAILED);
                    tracing.finishChat(context, "INTERNAL_ERROR", -1, 0, false, false);
                }
                throw startFailed();
            }
        }
    }

    public ChatRequestRegistry.CancelResult cancel(String username, UUID requestId) {
        return registry.cancel(username, requestId);
    }

    /** Called only by MVC after its async return-value handler initialized the emitter. */
    public void transportReady(SseEmitter emitter) {
        streams.values().stream().filter(stream -> stream.emitter == emitter).findFirst().ifPresent(stream -> {
            if (stream.transportReady.compareAndSet(false, true)) {
                try {
                    stream.start();
                } catch (RejectedExecutionException error) {
                    stream.closeRejectedTransport();
                }
            }
        });
    }

    public static void validate(ChatCommand command) {
        if (command == null || command.username() == null || command.username().isBlank()) {
            throw new ChatRequestException("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "请先登录");
        }
        String message = command.message();
        boolean blank = message == null || message.codePoints()
                .allMatch(character -> Character.isWhitespace(character) || Character.isSpaceChar(character));
        if (command.requestId() == null || !canonicalUuid(command.conversationId()) || blank || message.length() > 16000) {
            throw new ChatRequestException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "聊天请求参数无效");
        }
    }

    private static boolean canonicalUuid(String value) {
        if (value == null || value.length() != 36) return false;
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static ChatRequestException startFailed() {
        return new ChatRequestException("CHAT_START_FAILED", HttpStatus.INTERNAL_SERVER_ERROR, "聊天请求启动失败");
    }

    int activeStreamCount() { return streams.size(); }

    int pendingEventCount() {
        return streams.values().stream().mapToInt(Stream::pendingCount).sum();
    }

    @Override
    @PreDestroy
    public void close() {
        List<Stream> closing;
        synchronized (admission) {
            if (!accepting) return;
            accepting = false;
            closing = List.copyOf(streams.values());
        }
        for (Stream stream : closing) stream.terminate(CANCELLED, null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        for (Stream stream : closing) {
            if (stream.context.state() != COMPLETING) continue;
            try {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) stream.removed.await(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Stream stream : closing) {
            if (stream.context.state() != COMPLETING) {
                stream.closeHealthyTransport(stream::remove);
                continue;
            }
            if (stream.context.state() == COMPLETING) {
                // The transaction owns its result; shutdown only closes downstream resources.
                stream.closeHealthyTransport(stream::stopResources);
                stream.finishObservation(true);
            }
        }
    }

    private final class Stream {
        final ChatRequestContext context;
        final SseEmitter emitter;
        final ArrayDeque<ChatOutput> pending = new ArrayDeque<>();
        final AtomicBoolean scheduled = new AtomicBoolean();
        final AtomicBoolean transportReady;
        final AtomicBoolean writable = new AtomicBoolean(true);
        final AtomicBoolean resourcesStopped = new AtomicBoolean();
        final AtomicBoolean removedOnce = new AtomicBoolean();
        final AtomicBoolean observationEnded = new AtomicBoolean();
        final AtomicReference<ScheduledFuture<?>> deadline = new AtomicReference<>();
        final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();
        final CountDownLatch removed = new CountDownLatch(1);
        final ChatRoundAccumulator delivered = new ChatRoundAccumulator();
        final long startedAt = System.nanoTime();
        volatile boolean modelDone;
        volatile boolean heartbeatPending;
        volatile String errorCode;
        volatile boolean terminalReady;
        volatile long firstChunkAt = -1;
        volatile long deliveredCharacters;
        volatile boolean durableCommitted;
        volatile boolean networkTerminalDelivered;
        boolean metaSent;
        boolean terminalSent;
        long sequence;

        Stream(ChatRequestContext context, SseEmitter emitter) {
            this.context = context;
            this.emitter = emitter;
            this.transportReady = new AtomicBoolean(!(emitter instanceof ChatStreamingConfig.TransportSseEmitter));
        }

        void start() {
            var task = new FutureTask<Void>(() -> { generate(); return null; }) {
                @Override protected void done() {
                    if (isCancelled()) removeFromGenerationQueue(this);
                }
            };
            context.generationResources().attachGenerationTask(task);
            scheduleDrain();
            if (!context.isTerminal()) {
                generators.execute(task);
                // Cancellation may win between the admission check and the executor enqueue.
                if (task.isCancelled()) removeFromGenerationQueue(task);
            }
        }

        void removeFromGenerationQueue(Runnable task) {
            if (generators instanceof ThreadPoolExecutor executor) executor.remove(task);
        }

        void initialize() {
            emitter.onCompletion(this::disconnected);
            emitter.onError(error -> disconnected());
            emitter.onTimeout(() -> {
                writable.set(false);
                terminate(TIMED_OUT, "STREAM_TIMEOUT");
                if (context.state() == COMPLETING) stopResources();
                else remove();
            });
            context.onCancel(() -> terminalWon(null));
            install(deadline, timers.schedule(() -> terminate(TIMED_OUT, "STREAM_TIMEOUT"),
                    context.generationResources().remaining().toNanos(), TimeUnit.NANOSECONDS));
            install(heartbeat, timers.scheduleAtFixedRate(() -> {
                if (resourcesStopped.get()) return;
                heartbeatPending = true;
                scheduleDrain();
            }, properties.getHeartbeatIntervalMs(), properties.getHeartbeatIntervalMs(), TimeUnit.MILLISECONDS));
        }

        void install(AtomicReference<ScheduledFuture<?>> slot, ScheduledFuture<?> task) {
            slot.set(task);
            if (resourcesStopped.get()) task.cancel(false);
        }

        void generate() {
            if (!context.tryTransition(REGISTERED, RUNNING)) return;
            try (var ignored = context.traceContext().makeCurrent()) {
                context.generationResources().checkRunning();
                handler.generateReply(context.command(), context, this::offer);
                context.generationResources().checkRunning();
                modelDone = true;
                scheduleDrain();
            } catch (java.net.http.HttpTimeoutException error) {
                terminate(TIMED_OUT, "STREAM_TIMEOUT");
            } catch (RuntimeException error) {
                if (context.generationResources().remaining().isZero()
                        || error instanceof ChatHandler.GenerationException generation && "STREAM_TIMEOUT".equals(generation.getErrorCode()))
                    terminate(TIMED_OUT, "STREAM_TIMEOUT");
                else terminate(FAILED, error instanceof ChatHandler.GenerationException generation ? generation.getErrorCode() : "INTERNAL_ERROR");
            }
        }

        void offer(ChatOutput output) {
            boolean overflow;
            synchronized (pending) {
                if (context.state() != RUNNING || resourcesStopped.get()) return;
                overflow = pending.size() >= properties.getMaxPendingEvents();
                if (!overflow) pending.addLast(output);
            }
            if (overflow) terminate(FAILED, "STREAM_OVERFLOW");
            else scheduleDrain();
        }

        int pendingCount() {
            synchronized (pending) { return pending.size(); }
        }

        void scheduleDrain() {
            if (!transportReady.get() || removedOnce.get() || !scheduled.compareAndSet(false, true)) return;
            try {
                workers.execute(this::drain);
            } catch (RejectedExecutionException error) {
                scheduled.set(false);
                closeRejectedTransport();
            }
        }

        /** No sender can make further progress, so close a healthy transport without another write. */
        void closeRejectedTransport() {
            closeHealthyTransport(() -> {
                terminate(FAILED, "INTERNAL_ERROR");
                stopResources();
                remove();
            });
        }

        void closeHealthyTransport(Runnable cleanup) {
            boolean healthy = writable.compareAndSet(true, false);
            try {
                cleanup.run();
            } finally {
                // A send IOException already handed this emitter to Spring's error path.
                if (healthy) emitter.complete();
            }
        }

        void drain() {
            try {
                if (!writable.get()) return;
                if (!metaSent) {
                    send("meta", Map.of());
                    metaSent = true;
                }
                while (writable.get() && !removedOnce.get()) {
                    if (context.isTerminal()) {
                        if (!terminalReady) return;
                        sendTerminal();
                        return;
                    }
                    ChatOutput next;
                    synchronized (pending) { next = pending.pollFirst(); }
                    if (next != null) {
                        if (context.state() != RUNNING) continue;
                        delivered.accept(next);
                        send(next.type(), next.data());
                        if ("chunk".equals(next.type())) {
                            deliveredCharacters += ((String) next.data().get("chunk")).length();
                            if (firstChunkAt < 0) firstChunkAt = System.nanoTime();
                        }
                        continue;
                    }
                    if (modelDone) delivered.requireFinal();
                    if (modelDone && context.tryTransition(RUNNING, COMPLETING)) {
                        cancel(deadline);
                        context.releaseResources();
                        persist();
                        continue;
                    }
                    if (heartbeatPending) {
                        heartbeatPending = false;
                        write(SseEmitter.event().comment(" ping"));
                    }
                    return;
                }
            } catch (IOException error) {
                // Spring owns error dispatch after a send IOException; never write/complete again.
                disconnected();
            } catch (RuntimeException error) {
                terminate(FAILED, "INTERNAL_ERROR");
            } finally {
                scheduled.set(false);
                if (hasWork()) scheduleDrain();
            }
        }

        boolean hasWork() {
            return writable.get() && !removedOnce.get()
                    && (!metaSent || pendingCount() > 0 || heartbeatPending || (context.isTerminal() && terminalReady)
                    || (modelDone && context.state() == RUNNING));
        }

        void send(String type, Map<String, Object> data) throws IOException {
            ChatCommand command = context.command();
            long seq = ++sequence;
            write(SseEmitter.event().name(type).id(Long.toString(seq))
                    .data(new ChatEventEnvelope(type, command.requestId(), command.conversationId(), seq, data),
                            MediaType.APPLICATION_JSON));
        }

        void write(SseEmitter.SseEventBuilder event) throws IOException {
            try {
                emitter.send(event);
            } catch (RuntimeException error) {
                // Conversion/transport failures are equally unwritable; retrying a terminal loops forever.
                throw new IOException("SSE transport unavailable", error);
            }
        }

        void persist() {
            try {
                handler.persistCompletedTurn(context.command(), delivered.answer());
                durableCommitted = true;
                context.tryTransition(COMPLETING, FINISHED);
            } catch (RuntimeException error) {
                errorCode = "PERSISTENCE_ERROR";
                context.tryTransition(COMPLETING, FAILED);
            }
            terminalWon(errorCode);
        }

        void terminate(ChatRequestContext.State target, String code) {
            boolean won = context.tryTransition(REGISTERED, target) || context.tryTransition(RUNNING, target);
            if (won) terminalWon(code);
        }

        void terminalWon(String code) {
            if (code != null) errorCode = code;
            stopResources();
            if (context.isTerminal()) registry.finish(context, context.state());
            terminalReady = true;
            if (writable.get()) scheduleDrain();
            else if (context.state() != COMPLETING) remove();
        }

        void stopResources() {
            if (!resourcesStopped.compareAndSet(false, true)) return;
            cancel(deadline);
            cancel(heartbeat);
            heartbeatPending = false;
            synchronized (pending) { pending.clear(); }
            context.releaseResources();
        }

        void disconnected() {
            writable.set(false);
            terminate(CANCELLED, null);
            stopResources();
            if (context.state() != COMPLETING) remove();
        }

        void sendTerminal() throws IOException {
            if (terminalSent || !writable.get()) return;
            terminalSent = true;
            if (errorCode != null) send("error", Map.of("code", errorCode, "message", safeMessage(errorCode)));
            send("completion", Map.of("status", context.state().name().toLowerCase(Locale.ROOT)));
            networkTerminalDelivered = true;
            closeHealthyTransport(this::remove);
        }

        void remove() {
            if (!removedOnce.compareAndSet(false, true)) return;
            stopResources();
            streams.remove(context, this);
            finishObservation(false);
            removed.countDown();
            ChatCommand command = context.command();
            logger.info("Chat stream ended username={} chatRequestId={} conversationId={} state={} errorCode={} "
                            + "firstChunkMs={} elapsedMs={} writtenCharacters={} resourcesReleased={} "
                            + "durableCommitted={} networkTerminalDelivered={}",
                    command.username(), command.requestId(), command.conversationId(), context.state(),
                    errorCode == null ? "none" : errorCode,
                    firstChunkAt < 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(firstChunkAt - startedAt),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt), deliveredCharacters,
                    context.resourcesReleased(), durableCommitted, networkTerminalDelivered);
        }

        void finishObservation(boolean shutdown) {
            if (!observationEnded.compareAndSet(false, true)) return;
            long firstChunkMs = firstChunkAt < 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(firstChunkAt - startedAt);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (shutdown && context.state() == COMPLETING && !durableCommitted) {
                tracing.finishPendingChat(context, firstChunkMs, elapsedMs);
            } else {
                tracing.finishChat(context, errorCode, firstChunkMs, elapsedMs, durableCommitted, networkTerminalDelivered);
            }
        }

        void cancel(AtomicReference<ScheduledFuture<?>> task) {
            ScheduledFuture<?> future = task.get();
            if (future != null) future.cancel(false);
        }
    }

    private static String safeMessage(String code) {
        return switch (code) {
            case "MODEL_ERROR" -> "模型服务暂时不可用，请稍后重试";
            case "TOOL_ERROR" -> "知识库检索失败，请稍后重试";
            case "HISTORY_ERROR" -> "会话历史加载失败，请稍后重试";
            case "PERSISTENCE_ERROR" -> "回答保存失败，请稍后重试";
            case "STREAM_TIMEOUT" -> "回答生成超时，请稍后重试";
            case "STREAM_OVERFLOW" -> "连接接收过慢，请重新尝试";
            default -> "回答生成失败，请稍后重试";
        };
    }
}
