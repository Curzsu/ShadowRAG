package com.yizhaoqi.smartpai.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.LangfuseConfiguration;
import com.yizhaoqi.smartpai.config.LangfuseProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in only. Creates a connectivity record, never simulates an LLM request. */
@EnabledIfEnvironmentVariable(named = "LANGFUSE_SMOKE_TEST", matches = "true")
class LangfuseConnectionSmokeTest {
    @Test void cloudReceivesConnectivityRecord() throws Exception {
        var props = new LangfuseProperties();
        props.setEnabled(Boolean.parseBoolean(System.getenv("LANGFUSE_ENABLED")));
        props.setBaseUrl(System.getenv("LANGFUSE_BASE_URL"));
        props.setPublicKey(System.getenv("LANGFUSE_PUBLIC_KEY"));
        props.setSecretKey(System.getenv("LANGFUSE_SECRET_KEY"));
        props.setEnvironment("development");
        var mapper = new ObjectMapper();
        String traceId;
        try (var tracing = new LangfuseConfiguration().langfuseTracing(props)) {
            var span = tracing.start("integration.smoke", "span");
            assertTrue(span.isRecording(), "Smoke test tracing is disabled");
            traceId = span.getSpanContext().getTraceId();
            span.end();
            tracing.forceFlush().join(5, TimeUnit.SECONDS);
            String authorization = "Basic " + Base64.getEncoder().encodeToString(
                    (props.getPublicKey() + ":" + props.getSecretKey()).getBytes(StandardCharsets.UTF_8));
            var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
            var proxy = new LangfuseConfiguration().proxyFor(URI.create(props.getBaseUrl()), System.getenv("HTTPS_PROXY"));
            if (proxy != null) builder.proxy(java.net.ProxySelector.of((java.net.InetSocketAddress) proxy.address()));
            var client = builder.build();
            var projects = client.send(HttpRequest.newBuilder(URI.create(props.getBaseUrl() + "/api/public/projects"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", authorization).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, projects.statusCode(), "Langfuse project authentication failed");
            String projectId = mapper.readTree(projects.body()).path("data").get(0).path("id").asText();
            var from = java.net.URLEncoder.encode(java.time.Instant.now().minusSeconds(120).toString(), StandardCharsets.UTF_8);
            var to = java.net.URLEncoder.encode(java.time.Instant.now().plusSeconds(120).toString(), StandardCharsets.UTF_8);
            var request = HttpRequest.newBuilder(URI.create(props.getBaseUrl() + "/api/public/v2/observations?traceId=" + traceId
                    + "&fields=core,basic&fromStartTime=" + from + "&toStartTime=" + to))
                    .timeout(Duration.ofSeconds(10)).header("Authorization", authorization).GET().build();
            HttpResponse<String> response = null;
            for (int attempt = 0; attempt < 15; attempt++) {
                response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode(), "Unexpected Langfuse observations lookup status");
                if (!mapper.readTree(response.body()).path("data").isEmpty()) break;
                Thread.sleep(2000);
            }
            assertNotNull(response);
            assertEquals(200, response.statusCode(), "Cloud record not visible within polling window");
            var trace = mapper.readTree(response.body()).path("data").get(0);
            assertNotNull(trace, "Cloud observation not visible within polling window");
            assertEquals(traceId, trace.path("traceId").asText());
            assertEquals("integration.smoke", trace.path("name").asText());
            assertEquals("development", trace.path("environment").asText());
            var output = Path.of("target/langfuse-work/cloud-smoke.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "traceId", traceId, "traceUrl", props.getBaseUrl() + "/project/" + projectId + "/traces/" + traceId,
                    "verified", true, "kind", "connectivity-only")));
        }
    }
}
