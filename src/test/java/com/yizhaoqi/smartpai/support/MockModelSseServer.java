package com.yizhaoqi.smartpai.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Local, controllable supplier stub; never contacts a paid model. */
public final class MockModelSseServer implements AutoCloseable {
    @FunctionalInterface
    public interface Script { void run(Session session) throws Exception; }
    private record Response(int status, Script script) {}
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Queue<Response> responses = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger disconnects = new AtomicInteger();
    private final AtomicLong firstDisconnectNanos = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peakActive = new AtomicInteger();
    private volatile Script fallback;
    private final CountDownLatch disconnected = new CountDownLatch(1);

    public MockModelSseServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Response response = responses.poll();
            if (response == null && fallback != null) response = new Response(200, fallback);
            if (response == null) response = new Response(500, session -> session.raw("unexpected request"));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream;charset=UTF-8");
            exchange.sendResponseHeaders(response.status(), 0);
            peakActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try (OutputStream output = exchange.getResponseBody()) {
                response.script().run(new Session(output, requestBody));
            } catch (IOException error) {
                firstDisconnectNanos.compareAndSet(0, System.nanoTime());
                disconnects.incrementAndGet();
                disconnected.countDown();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                throw new RuntimeException(error);
            } finally {
                active.decrementAndGet();
                exchange.close();
            }
        });
        server.start();
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public int requests() { return requests.get(); }
    public int secondModelCalls() { return Math.max(0, requests.get() - 1); }
    public int disconnects() { return disconnects.get(); }
    public long firstDisconnectNanos() { return firstDisconnectNanos.get(); }
    public int activeConnections() { return active.get(); }
    public int peakActiveConnections() { return peakActive.get(); }
    public void fallback(Script script) { fallback = script; }
    public void enqueue(Script script) { enqueue(200, script); }
    public void enqueue(int status, Script script) { responses.add(new Response(status, script)); }
    public boolean awaitDisconnect(Duration timeout) throws InterruptedException {
        return disconnected.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    public static final class Session {
        private final OutputStream output;
        private final String requestBody;
        private Session(OutputStream output, String requestBody) { this.output = output; this.requestBody = requestBody; }
        public String requestBody() { return requestBody; }
        public void data(String json) throws IOException { raw("data: " + json + "\n\n"); }
        public void content(String value) throws IOException {
            data("{\"choices\":[{\"delta\":{\"content\":\"" + value + "\"}}]}");
        }
        public void tool(String id, String arguments) throws IOException {
            String escaped = arguments.replace("\\", "\\\\").replace("\"", "\\\"");
            data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"" + id
                    + "\",\"function\":{\"name\":\"search_knowledge_base\",\"arguments\":\"" + escaped + "\"}}]}}]}");
        }
        public void raw(String text) throws IOException {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        public void fragmentedData(String json) throws IOException {
            byte[] frame = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
            for (byte value : frame) { output.write(value); output.flush(); }
        }
        public void pause(CountDownLatch release) throws InterruptedException {
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test script was not released");
        }
        /** Probe writes make a disposed real HTTP connection observable by this server. */
        public void probeUntilDisconnected() throws IOException, InterruptedException {
            for (int i = 0; i < 200; i++) { raw(": probe\n\n"); Thread.sleep(10); }
        }
    }
}
