package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.support.MockModelSseServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.yizhaoqi.smartpai.support.ChatGenerationProbe;
import com.yizhaoqi.smartpai.config.ModelHttpProperties;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekClientProxyTest {
    private final List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "proxy test"));

    private ApplicationContextRunner context(String proxyUrl) {
        return new ApplicationContextRunner()
                .withBean(AiProperties.class).withBean(ObjectMapper.class).withBean(ModelHttpProperties.class).withBean(DeepSeekClient.class)
                .withPropertyValues("deepseek.api.url=http://model.invalid:80", "deepseek.api.key=test-token",
                        "deepseek.api.model=test-model", "deepseek.api.proxy-url=" + proxyUrl);
    }

    @Test void configuredProxyCarriesTheColdStreamWithoutResolvingTheModelHost() throws Exception {
        try (var model = new MockModelSseServer(); var proxy = new ConnectProxy(model.url())) {
            model.enqueue(session -> { session.content("代理正常"); session.data("[DONE]"); });
            context(proxy.url()).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(proxy.connections.get()).isZero();
                ChatGenerationProbe.<String>create((context, out) -> ctx.getBean(DeepSeekClient.class).streamResponse(messages, context, out))
                        .expectNext("代理正常").expectComplete().verify(Duration.ofSeconds(5));
                assertThat(proxy.connectLine).isEqualTo("POST http://model.invalid:80/chat/completions HTTP/1.1");
                assertThat(model.requests()).isEqualTo(1);
            });
        }
    }

    @Test void cancellingTheProxiedStreamClosesTheModelConnection() throws Exception {
        try (var model = new MockModelSseServer(); var proxy = new ConnectProxy(model.url())) {
            model.enqueue(session -> { session.content("first"); session.probeUntilDisconnected(); });
            context(proxy.url()).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                ChatGenerationProbe.<String>create((context, out) -> ctx.getBean(DeepSeekClient.class).streamResponse(messages, context, out))
                        .expectNext("first").thenCancel().verify(Duration.ofSeconds(5));
                try { assertThat(model.awaitDisconnect(Duration.ofSeconds(2))).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://127.0.0.1:7890", "socks5://127.0.0.1:7890",
            "http://secret:password@127.0.0.1:7890", "http://127.0.0.1", "http://127.0.0.1:0",
            "http://127.0.0.1:65536", "http://127.0.0.1:7890/private", "http://127.0.0.1:7890?secret=value",
            "http://127.0.0.1:7890#secret", "not a uri"})
    void invalidProxyFailsAtStartupWithoutEchoingItsValue(String proxyUrl) {
        context(proxyUrl).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("deepseek.api.proxy-url must be an unauthenticated HTTP proxy with an explicit valid port");
        });
    }

    /** A real CONNECT tunnel; all traffic is restricted to the local model stub. */
    private static final class ConnectProxy implements AutoCloseable {
        private final ServerSocket listener;
        private final int modelPort;
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final AtomicInteger connections = new AtomicInteger();
        private volatile String connectLine;

        ConnectProxy(String modelUrl) throws IOException {
            modelPort = URI.create(modelUrl).getPort();
            listener = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
            executor.submit(() -> {
                while (!listener.isClosed()) {
                    try { Socket downstream = listener.accept(); sockets.add(downstream); executor.submit(() -> tunnel(downstream)); }
                    catch (IOException ignored) { break; }
                }
            });
        }

        String url() { return "http://127.0.0.1:" + listener.getLocalPort(); }

        private void tunnel(Socket downstream) {
            try {
                downstream.setSoTimeout(5000);
                var headers = new StringBuilder();
                while (headers.length() < 16384 && !headers.toString().endsWith("\r\n\r\n")) {
                    int value = downstream.getInputStream().read();
                    if (value < 0) throw new IOException("Incomplete CONNECT");
                    headers.append((char) value);
                }
                connectLine = headers.toString().split("\r\n", 2)[0];
                boolean connect = connectLine.equals("CONNECT model.invalid:80 HTTP/1.1");
                if (!connect && !connectLine.equals("POST http://model.invalid:80/chat/completions HTTP/1.1"))
                    throw new IOException("Unexpected proxy target");
                Socket upstream = new Socket("127.0.0.1", modelPort);
                sockets.add(upstream);
                connections.incrementAndGet();
                if (connect) {
                    downstream.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    downstream.getOutputStream().flush();
                } else {
                    upstream.getOutputStream().write(headers.toString().replace(connectLine, "POST /chat/completions HTTP/1.1").getBytes(StandardCharsets.US_ASCII));
                    upstream.getOutputStream().flush();
                }
                downstream.setSoTimeout(0);
                executor.submit(() -> relay(upstream, downstream));
                relay(downstream, upstream);
            } catch (IOException ignored) { closeSocket(downstream); }
        }

        private void relay(Socket from, Socket to) {
            try { from.getInputStream().transferTo(to.getOutputStream()); }
            catch (IOException ignored) { }
            finally { closeSocket(from); closeSocket(to); }
        }

        private void closeSocket(Socket socket) {
            try { socket.close(); } catch (IOException ignored) { }
            sockets.remove(socket);
        }

        @Override public void close() throws IOException {
            listener.close();
            sockets.forEach(this::closeSocket);
            executor.shutdownNow();
        }
    }
}
