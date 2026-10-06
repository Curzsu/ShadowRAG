package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.config.ModelHttpProperties;
import com.yizhaoqi.smartpai.service.chat.ChatGenerationResources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class BlockingModelHttpClientProxyTest {
    final Map<String,Object> request = Map.of("model", "test", "stream", true);
    ChatGenerationResources resources() { return new ChatGenerationResources(System.nanoTime()+TimeUnit.SECONDS.toNanos(5)); }

    @Test void configuredHttpProxyAvoidsModelDnsAndCarriesAuthorization() throws Exception {
        var listener = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var target = new AtomicReference<String>(); var auth = new AtomicReference<String>();
        listener.createContext("/", exchange -> {
            target.set(exchange.getRequestURI().toString()); auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"proxy answer\"}}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            } finally { exchange.close(); }
        });
        listener.start();
        try {
            var client = new BlockingModelHttpClient("http://model.invalid:80/v1", "test-key",
                    "http://127.0.0.1:"+listener.getAddress().getPort(), new ModelHttpProperties(), new ObjectMapper());
            assertEquals("proxy answer", client.stream(request, resources(), ignored -> {}).content());
            assertEquals("http://model.invalid:80/v1/chat/completions", target.get());
            assertEquals("Bearer test-key", auth.get());
        } finally { listener.stop(0); }
    }

    @Test void httpsConnectIsCancelledBeforeTlsResponseHeaders() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var entered = new CountDownLatch(1); var disconnected = new CountDownLatch(1);
        try (var listener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))) {
            var requestLine = new AtomicReference<String>();
            var tunnel = pool.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(4000);
                    var header = new StringBuilder();
                    while (!header.toString().endsWith("\r\n\r\n") && header.length()<16384) {
                        int value = socket.getInputStream().read(); if (value == -1) break; header.append((char)value);
                    }
                    requestLine.set(header.toString().split("\r\n")[0]);
                    socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush(); entered.countDown();
                    while (socket.getInputStream().read() != -1) { }
                    disconnected.countDown();
                }
                return null;
            });
            var resources = resources();
            var client = new BlockingModelHttpClient("https://model.invalid:443", "",
                    "http://127.0.0.1:"+listener.getLocalPort(), new ModelHttpProperties(), new ObjectMapper());
            var generation = pool.submit(() -> assertThrows(CancellationException.class,
                    () -> client.stream(request, resources, ignored -> {})));
            assertTrue(entered.await(2, TimeUnit.SECONDS)); resources.stop(); generation.get(2, TimeUnit.SECONDS);
            assertEquals("CONNECT model.invalid:443 HTTP/1.1", requestLine.get());
            assertTrue(disconnected.await(2, TimeUnit.SECONDS)); tunnel.get(2, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings={"https://127.0.0.1:7890", "socks5://127.0.0.1:7890", "http://secret:password@127.0.0.1:7890",
            "http://127.0.0.1", "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://127.0.0.1:7890/private",
            "http://127.0.0.1:7890?secret=value", "http://127.0.0.1:7890#secret", "not a uri"})
    void invalidProxyFailsWithoutExposingItsValue(String proxy) {
        var error = assertThrows(IllegalArgumentException.class, () -> new BlockingModelHttpClient(
                "http://model.invalid", "", proxy, new ModelHttpProperties(), new ObjectMapper()));
        assertEquals("deepseek.api.proxy-url must be an unauthenticated HTTP proxy with an explicit valid port", error.getMessage());
    }
}
