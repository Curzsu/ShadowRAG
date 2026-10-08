package com.yizhaoqi.smartpai.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.model.chat.ChatCommand;
import com.yizhaoqi.smartpai.service.chat.ChatRequestContext;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Application-local tracer; never installs a global SDK or propagates identity to suppliers. */
public final class LangfuseTracing implements AutoCloseable {
    private static final ContextKey<ChatCommand> CHAT_COMMAND = ContextKey.named("shadowrag.chat.command");
    private final Tracer tracer;
    private final SdkTracerProvider provider;
    private final String environment;
    private final AtomicBoolean closed = new AtomicBoolean();

    public LangfuseTracing(Tracer tracer, SdkTracerProvider provider, String environment) {
        this.tracer = tracer;
        this.provider = provider;
        this.environment = environment == null || environment.isBlank() ? "development" : environment;
    }

    public static LangfuseTracing noop() {
        return new LangfuseTracing(OpenTelemetry.noop().getTracer("shadowrag.langfuse"), null, "development");
    }

    public Span start(String name, String type) {
        return experimentAttributes(tracer.spanBuilder(name).startSpan()
                .setAttribute("langfuse.observation.type", type)
                .setAttribute("langfuse.environment", environment));
    }

    public Context startChat(ChatCommand command) {
        Span span = common(tracer.spanBuilder("chat.request").setNoParent().startSpan(), command)
                .setAttribute("langfuse.observation.type", "span");
        return Context.root().with(CHAT_COMMAND, command).with(span);
    }

    public Span startSearchStage(String name) {
        Context parent = Context.current();
        // Standalone searches and disabled tracing must not create disconnected telemetry roots.
        if (!Span.fromContext(parent).isRecording()) return Span.getInvalid();
        Span span = tracer.spanBuilder(name).setParent(parent).startSpan()
                .setAttribute("langfuse.observation.type", "span")
                .setAttribute("langfuse.environment", environment);
        ChatCommand command = parent.get(CHAT_COMMAND);
        return experimentAttributes(command == null ? span : common(span, command));
    }

    // Only evaluation correlation IDs are copied; never propagate arbitrary baggage or content.
    private Span experimentAttributes(Span span) {
        Baggage baggage=Baggage.current();
        if (baggage.getEntryValue("langfuse.experiment.id") == null) return span;
        for (String key : java.util.List.of("langfuse.experiment.id", "langfuse.experiment.name",
                "langfuse.experiment.dataset.id", "langfuse.experiment.item.id",
                "langfuse.experiment.item.root_observation_id", "langfuse.experiment.item.version",
                "langfuse.environment")) {
            String value=baggage.getEntryValue(key);
            if (value != null) span.setAttribute(key,value);
        }
        return span;
    }

    private Span common(Span span, ChatCommand command) {
        String release = LangfuseTracing.class.getPackage().getImplementationVersion();
        span.setAttribute("langfuse.environment", environment)
                .setAttribute("langfuse.release", release == null ? "development" : release)
                .setAttribute("langfuse.session.id", command.conversationId())
                .setAttribute("langfuse.observation.metadata.request_id", command.requestId().toString());
        return span;
    }

    public ModelObservation startModel(ChatRequestContext request, int round, String model, AiProperties.Generation params) {
        Span span = common(tracer.spanBuilder("llm.round").setParent(request.traceContext()).startSpan(), request.command())
                .setAttribute("langfuse.observation.type", "generation")
                .setAttribute("langfuse.observation.metadata.round_id", round);
        if (model != null) span.setAttribute("langfuse.observation.model.name", model);
        if (params.getTemperature() != null) span.setAttribute("gen_ai.request.temperature", params.getTemperature());
        if (params.getTopP() != null) span.setAttribute("gen_ai.request.top_p", params.getTopP());
        if (params.getMaxTokens() != null) span.setAttribute("gen_ai.request.max_tokens", params.getMaxTokens());
        return new ModelObservation(span);
    }

    public Span startSearch(ChatRequestContext request, String callId) {
        return common(tracer.spanBuilder("tool.knowledge_search").setParent(request.traceContext()).startSpan(), request.command())
                .setAttribute("langfuse.observation.type", "tool")
                .setAttribute("langfuse.observation.metadata.call_id", callId)
                .setAttribute("langfuse.observation.metadata.rerank_status", "skipped");
    }

    public void finishChat(ChatRequestContext request, String error, long firstChunkMs, long elapsedMs,
                           boolean committed, boolean delivered) {
        var span = Span.fromContext(request.traceContext());
        span.setAttribute("langfuse.observation.metadata.state", request.state().name())
                .setAttribute("langfuse.observation.metadata.elapsed_ms", elapsedMs)
                .setAttribute("langfuse.observation.metadata.durable_committed", committed)
                .setAttribute("langfuse.observation.metadata.network_terminal_delivered", delivered);
        if (firstChunkMs >= 0) span.setAttribute("langfuse.observation.metadata.server_first_chunk_ms", firstChunkMs);
        if (error != null) error(span, error);
        span.end();
    }

    public void finishPendingChat(ChatRequestContext request, long firstChunkMs, long elapsedMs) {
        var span = Span.fromContext(request.traceContext());
        span.setAttribute("langfuse.observation.metadata.state", "COMPLETING")
                .setAttribute("langfuse.observation.metadata.elapsed_ms", elapsedMs)
                .setAttribute("langfuse.observation.metadata.shutdown_unresolved", true)
                .setAttribute("langfuse.observation.metadata.durable_commit_outcome", "unknown");
        if (firstChunkMs >= 0) span.setAttribute("langfuse.observation.metadata.server_first_chunk_ms", firstChunkMs);
        // SDK shutdown cannot wait indefinitely for a database transaction; do not invent its outcome.
        span.end();
    }

    public static void metadata(Span span, String key, String value) {
        if (value != null) span.setAttribute("langfuse.observation.metadata." + key, value);
    }

    public static void error(Span span, String code) {
        // Callers pass fixed internal error codes, never exception messages or stack traces.
        metadata(span, "error_code", code);
        span.setStatus(StatusCode.ERROR, code).setAttribute("langfuse.observation.level", "ERROR");
    }

    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class ModelObservation implements AutoCloseable {
        private final Span span;
        private final AtomicBoolean firstContent = new AtomicBoolean();
        private ModelObservation(Span span) { this.span = span; }
        public Span span() { return span; }
        public void firstContent(String content) {
            if (content != null && !content.isEmpty() && firstContent.compareAndSet(false, true)) {
                span.setAttribute("langfuse.observation.completion_start_time", Instant.now().toString());
            }
        }
        @Override public void close() { span.end(); }
    }

    public CompletableResultCode forceFlush() {
        return provider == null ? CompletableResultCode.ofSuccess() : provider.forceFlush();
    }

    @Override public void close() {
        if (provider != null && closed.compareAndSet(false, true)) {
            provider.shutdown().join(4, TimeUnit.SECONDS);
        }
    }
}
