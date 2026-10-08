package com.yizhaoqi.smartpai.observability;

import com.yizhaoqi.smartpai.config.LangfuseConfiguration;
import com.yizhaoqi.smartpai.config.LangfuseProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LangfuseConfigurationTest {
    @Test void disabledAndMissingCredentialsUseNonRecordingTracer() {
        var config = new LangfuseConfiguration();
        var props = new LangfuseProperties();
        try (var tracing = config.langfuseTracing(props)) {
            assertFalse(tracing.start("disabled", "span").isRecording());
        }
        props.setEnabled(true);
        try (var tracing = config.langfuseTracing(props)) {
            assertFalse(tracing.start("missing", "span").isRecording());
        }
    }

    @Test void otlpUsesConfiguredEndpointAndHeadersWithoutBlockingSpanEnd() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var path = new AtomicReference<String>();
        var authMatches = new AtomicReference<Boolean>();
        var version = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        var calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authMatches.set(("Basic " + Base64.getEncoder().encodeToString("fake-public:fake-secret".getBytes(StandardCharsets.UTF_8)))
                    .equals(exchange.getRequestHeaders().getFirst("Authorization")));
            version.set(exchange.getRequestHeaders().getFirst("x-langfuse-ingestion-version"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            assertTrue(exchange.getRequestBody().readAllBytes().length > 0);
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        var props = enabled("http://127.0.0.1:" + server.getAddress().getPort());
        try (var tracing = new LangfuseConfiguration().langfuseTracing(props)) {
            tracing.start("integration.smoke", "span").end();
            assertTrue(tracing.forceFlush().join(5, TimeUnit.SECONDS).isSuccess());
            assertEquals(1, calls.get());
            assertEquals("/api/public/otel/v1/traces", path.get());
            assertTrue(authMatches.get(), "OTLP authentication header mismatch");
            assertEquals("4", version.get());
            assertEquals("application/x-protobuf", contentType.get());
        } finally { server.stop(0); }
    }

    @Test void authorizationFailureDoesNotEscapeToCaller() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.incrementAndGet();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try (var tracing = new LangfuseConfiguration().langfuseTracing(
                enabled("http://127.0.0.1:" + server.getAddress().getPort()))) {
            assertDoesNotThrow(() -> tracing.start("failed-export", "span").end());
            assertDoesNotThrow(() -> tracing.forceFlush().join(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test void invalidEndpointDisablesTracingInsteadOfFailingApplicationStartup() {
        try (var tracing = new LangfuseConfiguration().langfuseTracing(enabled("bad://endpoint"))) {
            assertFalse(tracing.start("invalid", "span").isRecording());
        }
    }

    @Test void externalHttpsUsesExistingProxyButLocalMockBypassesIt() {
        var external=java.net.URI.create("https://jp.cloud.langfuse.com");
        var proxy=new LangfuseConfiguration().proxyFor(external,"http://127.0.0.1:7890");
        assertNotNull(proxy);
        assertEquals(new InetSocketAddress("127.0.0.1",7890),proxy.address());
        assertNull(new LangfuseConfiguration().proxyFor(java.net.URI.create("http://127.0.0.1:1234"),"http://127.0.0.1:7890"));
        assertNull(new LangfuseConfiguration().proxyFor(external,"not a proxy URL"));
    }

    @Test void exportFailureDiagnosticsDoNotEchoResponse() throws Exception {
        var messages=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var logger=java.util.logging.Logger.getLogger("io.opentelemetry.exporter.otlp.internal.HttpExporter");
        var capture=new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord record) {
                messages.add(record.getMessage());
                if(record.getThrown()!=null) messages.add(record.getThrown().toString());
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(capture);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body="{\"message\":\"PRIVATE_RESPONSE_ECHO\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(401,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try(var tracing=new LangfuseConfiguration().langfuseTracing(enabled("http://127.0.0.1:"+server.getAddress().getPort()))) {
            tracing.start("privacy","span").end(); tracing.forceFlush().join(5,TimeUnit.SECONDS);
            assertFalse(messages.isEmpty(),"Expected failed-export diagnostic");
            assertTrue(messages.stream().noneMatch(text -> text.contains("PRIVATE_RESPONSE_ECHO")),"Exporter echoed untrusted response");
        } finally { server.stop(0); logger.removeHandler(capture); }
    }

    static LangfuseProperties enabled(String url) {
        var props = new LangfuseProperties();
        props.setEnabled(true); props.setBaseUrl(url);
        props.setPublicKey("fake-public"); props.setSecretKey("fake-secret");
        return props;
    }
}
