package com.yizhaoqi.smartpai.observability;

import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.model.chat.ChatCommand;
import com.yizhaoqi.smartpai.service.chat.ChatRequestContext;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class LangfuseTracingTest {
    @Test void firstNonemptyContentSetsNativeTimestampOnceAndNoContentIsUploaded() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracing = new LangfuseTracing(provider.get("test"), provider, "development");
            var request = new ChatRequestContext(command());
            request.setTraceContext(tracing.startChat(request.command()));
            Instant beforeLaterContent;
            try (var observation = tracing.startModel(request, 1, "test-model", new AiProperties().getGeneration())) {
                observation.firstContent(null); observation.firstContent("");
                observation.firstContent("PRIVATE ANSWER");
                beforeLaterContent=Instant.now();
                observation.firstContent("later");
            }
            var data = exporter.getFinishedSpanItems().get(0);
            var time = data.getAttributes().get(AttributeKey.stringKey("langfuse.observation.completion_start_time"));
            assertNotNull(time);
            assertTrue(Instant.parse(time).getEpochSecond() > 0);
            assertFalse(Instant.parse(time).isAfter(beforeLaterContent),"Later chunks overwrote the first-content timestamp");
            assertEquals("generation", data.getAttributes().get(AttributeKey.stringKey("langfuse.observation.type")));
            assertEquals(io.opentelemetry.api.trace.Span.fromContext(request.traceContext()).getSpanContext().getSpanId(), data.getParentSpanId());
            assertFalse(data.getAttributes().toString().contains("PRIVATE"));
            assertTrue(data.getEvents().isEmpty());
            io.opentelemetry.api.trace.Span.fromContext(request.traceContext()).end();
        }
    }

    @Test void toolsOnlyRoundHasNoNativeAnswerTimestamp() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracing = new LangfuseTracing(provider.get("test"), provider, "development");
            var request = new ChatRequestContext(command());
            request.setTraceContext(tracing.startChat(request.command()));
            try (var observation = tracing.startModel(request, 1, "test-model", new AiProperties().getGeneration())) {
                observation.firstContent("");
            }
            assertNull(exporter.getFinishedSpanItems().get(0).getAttributes()
                    .get(AttributeKey.stringKey("langfuse.observation.completion_start_time")));
            io.opentelemetry.api.trace.Span.fromContext(request.traceContext()).end();
        }
    }

    @Test void rootsIgnoreAmbientContextAndWorkerScopesRestoreAfterEachRequest() throws Exception {
        var exporter = InMemorySpanExporter.create();
        var worker = Executors.newSingleThreadExecutor();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracing = new LangfuseTracing(provider.get("test"), provider, "development");
            var first = tracing.startChat(command());
            Context second;
            try (var ignored = first.makeCurrent()) { second = tracing.startChat(command()); }
            var secondRoot = second;
            worker.submit(() -> {
                try (var ignored = first.makeCurrent()) { tracing.start("child-one", "span").end(); }
                assertFalse(io.opentelemetry.api.trace.Span.current().getSpanContext().isValid());
                try (var ignored = secondRoot.makeCurrent()) { tracing.start("child-two", "span").end(); }
                assertFalse(io.opentelemetry.api.trace.Span.current().getSpanContext().isValid());
            }).get();
            io.opentelemetry.api.trace.Span.fromContext(first).end();
            io.opentelemetry.api.trace.Span.fromContext(second).end();
            var spans = exporter.getFinishedSpanItems();
            assertNotEquals(spans.get(0).getTraceId(), spans.get(1).getTraceId());
            assertFalse(spans.get(2).getParentSpanContext().isValid());
            assertFalse(spans.get(3).getParentSpanContext().isValid());
        } finally { worker.shutdownNow(); }
    }

    static ChatCommand command() {
        return new ChatCommand("PRIVATE USER", UUID.randomUUID().toString(), UUID.randomUUID(), "PRIVATE QUESTION");
    }
}
