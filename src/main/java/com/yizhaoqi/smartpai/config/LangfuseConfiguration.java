package com.yizhaoqi.smartpai.config;

import com.yizhaoqi.smartpai.observability.LangfuseTracing;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.URI;
import java.net.Proxy;
import java.net.InetSocketAddress;
import io.opentelemetry.sdk.common.export.ProxyOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Configuration
@EnableConfigurationProperties(LangfuseProperties.class)
public class LangfuseConfiguration {
    private static final Logger log = LoggerFactory.getLogger(LangfuseConfiguration.class);

    @Bean(destroyMethod = "close")
    public LangfuseTracing langfuseTracing(LangfuseProperties props) {
        if (!props.isEnabled()) return LangfuseTracing.noop();
        if (props.getPublicKey() == null || props.getPublicKey().isBlank()
                || props.getSecretKey() == null || props.getSecretKey().isBlank()) {
            log.warn("Langfuse disabled: credentials missing");
            return LangfuseTracing.noop();
        }
        try {
            var base = URI.create(props.getBaseUrl().replaceAll("/+$", ""));
            if (!(base.getScheme().equals("https") || base.getScheme().equals("http"))
                    || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
                    || base.getFragment() != null || !(base.getPath().isEmpty() || base.getPath().equals("/"))) {
                throw new IllegalArgumentException("Invalid Langfuse base URL");
            }
            var exporterBuilder = OtlpHttpSpanExporter.builder()
                    .setEndpoint(base + "/api/public/otel/v1/traces")
                    .setTimeout(Duration.ofSeconds(3))
                    .setConnectTimeout(Duration.ofSeconds(3))
                    .addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                            (props.getPublicKey() + ":" + props.getSecretKey()).getBytes(StandardCharsets.UTF_8)))
                    .addHeader("x-langfuse-ingestion-version", "4");
            String proxyUrl = System.getenv("HTTPS_PROXY");
            if (proxyUrl == null || proxyUrl.isBlank()) proxyUrl = System.getenv("HTTP_PROXY");
            var proxy = proxyFor(base, proxyUrl);
            if (proxy != null) exporterBuilder.setProxy(ProxyOptions.create((InetSocketAddress) proxy.address()));
            var exporter = exporterBuilder.build();
            // SDK error diagnostics may echo an untrusted response body. Keep only a fixed safe message.
            java.util.logging.Logger.getLogger("io.opentelemetry.exporter.otlp.internal.HttpExporter").setFilter(record -> {
                record.setMessage("Langfuse OTLP export failed; check connection and credentials");
                record.setParameters(null);
                record.setThrown(null);
                return true;
            });
            var provider = SdkTracerProvider.builder()
                    .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), "shadowrag")))
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).setExporterTimeout(Duration.ofSeconds(3)).build())
                    .build();
            return new LangfuseTracing(provider.get("shadowrag.langfuse"), provider, props.getEnvironment());
        } catch (RuntimeException failure) {
            log.warn("Langfuse disabled: invalid exporter configuration ({})", failure.getClass().getSimpleName());
            return LangfuseTracing.noop();
        }
    }

    /** Reuses the machine's existing HTTP CONNECT proxy, only for this exporter. */
    public Proxy proxyFor(URI target, String proxyUrl) {
        if ("localhost".equalsIgnoreCase(target.getHost()) || "127.0.0.1".equals(target.getHost())
                || "[::1]".equals(target.getHost()) || proxyUrl == null || proxyUrl.isBlank()) return null;
        try {
            var proxy = URI.create(proxyUrl);
            if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null
                    || proxy.getUserInfo() != null || proxy.getPort() < 1) {
                log.warn("Langfuse proxy ignored: expected HTTP proxy without credentials");
                return null;
            }
            return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy.getHost(), proxy.getPort()));
        } catch (RuntimeException invalid) {
            log.warn("Langfuse proxy ignored: invalid proxy configuration");
            return null;
        }
    }
}
