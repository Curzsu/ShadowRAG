package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.ModelHttpProperties;
import com.yizhaoqi.smartpai.service.chat.ChatGenerationResources;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Ordinary stream reads on the caller's generation worker; the JDK handles network I/O separately. */
public final class BlockingModelHttpClient {
    private static final ScheduledThreadPoolExecutor DEADLINES;
    static {
        DEADLINES = new ScheduledThreadPoolExecutor(2, task -> {
            var thread = new Thread(task, "model-http-deadline"); thread.setDaemon(true); return thread;
        });
        DEADLINES.setRemoveOnCancelPolicy(true);
    }
    private final URI endpoint;
    private final String apiKey;
    private final ModelHttpProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public BlockingModelHttpClient(String apiUrl, String apiKey, String proxyUrl,
                                   ModelHttpProperties properties, ObjectMapper mapper) {
        properties.validate();
        this.properties = properties; this.mapper = mapper; this.apiKey = apiKey;
        URI base;
        try { base = URI.create(apiUrl); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid model API URL"); }
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null) throw new IllegalArgumentException("Invalid model API URL");
        this.endpoint = URI.create(apiUrl.replaceAll("/+$", "") + "/chat/completions");
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1);
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            var proxy = parseProxy(proxyUrl);
            builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxy.getHost(), proxy.getPort())));
        } else {
            builder.proxy(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            });
        }
        this.client = builder.build();
    }

    private static URI parseProxy(String configuredProxy) {
        String message = "deepseek.api.proxy-url must be an unauthenticated HTTP proxy with an explicit valid port";
        URI proxy;
        try { proxy = URI.create(configuredProxy); }
        catch (IllegalArgumentException malformed) { throw new IllegalArgumentException(message); }
        if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null
                || proxy.getRawUserInfo() != null || proxy.getPort() < 1 || proxy.getPort() > 65535
                || proxy.getRawQuery() != null || proxy.getRawFragment() != null
                || (proxy.getRawPath() != null && !proxy.getRawPath().isEmpty() && !"/".equals(proxy.getRawPath())))
            throw new IllegalArgumentException(message);
        return proxy;
    }

    public ModelRoundResult stream(Map<String,Object> request, ChatGenerationResources resources,
                                   Consumer<ModelDelta> onDelta) throws IOException, InterruptedException {
        var round = new Round(resources, onDelta);
        try {
            withBody(request, resources, "text/event-stream", response -> {
                requireType(response, "text/event-stream");
                try {
                    new ModelSseReader(properties).read(response.body(), data -> {
                        try {
                            resources.checkRunning();
                            if ("[DONE]".equals(data.trim())) { round.done = true; throw new Done(); }
                            round.accept(data);
                        } catch (IOException error) { throw new UncheckedIOException(error); }
                    });
                } catch (Done complete) { /* complete frame, close body immediately */ }
                catch (UncheckedIOException error) { throw error.getCause(); }
                if (!round.done) throw new IOException("Model stream ended before completion marker");
                resources.checkRunning();
                return null;
            });
        } catch (CancellationException e) { throw e; }
        catch (IllegalStateException e) { throw new IOException("Invalid model streaming response", e); }
        return new ModelRoundResult(round.content.toString(), round.id.toString(), round.arguments.toString(), round.finishReason);
    }

    public String postJson(Map<String,Object> request, Duration timeout) throws IOException, InterruptedException {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Model timeout must be positive");
        var resources = new ChatGenerationResources(System.nanoTime() + timeout.toNanos());
        try {
            return withBody(request, resources, "application/json", response -> {
                requireType(response, "application/json");
                byte[] bytes = readBounded(response.body(), properties.getMaxJsonResponseBytes(), true);
                resources.checkRunning();
                return new String(bytes, StandardCharsets.UTF_8);
            });
        } finally { resources.finishNormally(); }
    }

    private <T> T withBody(Map<String,Object> request, ChatGenerationResources resources, String accept,
                           BodyOperation<T> operation) throws IOException, InterruptedException {
        resources.checkRunning();
        var builder = HttpRequest.newBuilder(endpoint).timeout(resources.remaining())
                .header("Content-Type", "application/json").header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(request)));
        if (apiKey != null && !apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        var receivedBody = new AtomicReference<InputStream>();
        var requestFuture = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        resources.attachHttpRequest(requestFuture);
        // Always establish body ownership, even when cancellation wins before the waiting worker resumes.
        var ownedResponse = requestFuture.thenApply(response -> {
            var body = new CloseOnceBody(response.body());
            receivedBody.set(body); resources.attachResponseBody(body);
            return new OwnedResponse(response, body);
        });
        var deadline = DEADLINES.schedule(resources::stop, resources.remaining().toNanos(), TimeUnit.NANOSECONDS);
        try {
            var response = ownedResponse.get(resources.remaining().toNanos(), TimeUnit.NANOSECONDS);
            resources.clearHttpRequest(requestFuture);
            resources.checkRunning();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                readBounded(response.body(), properties.getMaxErrorResponseBytes(), false);
                throw new IOException("Model HTTP request failed (status " + response.statusCode() + ")");
            }
            return operation.apply(response);
        } catch (TimeoutException error) {
            resources.stop(); throw new HttpTimeoutException("Model request deadline exceeded");
        } catch (ExecutionException error) {
            checkFailureState(resources);
            if (error.getCause() instanceof IOException io) throw io;
            if (error.getCause() instanceof CancellationException cancelled) throw cancelled;
            throw new IOException("Model HTTP request failed");
        } catch (IOException | CancellationException error) {
            checkFailureState(resources); throw error;
        } catch (InterruptedException error) {
            resources.stop(); Thread.currentThread().interrupt(); throw error;
        } finally {
            deadline.cancel(false);
            requestFuture.cancel(true);
            resources.clearHttpRequest(requestFuture);
            InputStream body = receivedBody.get();
            if (body != null) { resources.clearResponseBody(body); body.close(); }
        }
    }

    private static void checkFailureState(ChatGenerationResources resources) throws HttpTimeoutException {
        if (resources.remaining().isZero()) throw new HttpTimeoutException("Model request deadline exceeded");
        resources.checkRunning();
    }

    private static void requireType(OwnedResponse response, String expected) throws IOException {
        String type = response.response().headers().firstValue("Content-Type").orElse("");
        if (!type.toLowerCase(Locale.ROOT).split(";", 2)[0].trim().equals(expected))
            throw new IOException("Unexpected model response format");
    }

    private static byte[] readBounded(InputStream input, int maximum, boolean failOnOverflow) throws IOException {
        var bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[Math.min(8192, maximum)];
        int count;
        while (bytes.size() < maximum && (count = input.read(buffer, 0, Math.min(buffer.length, maximum - bytes.size()))) != -1)
            bytes.write(buffer, 0, count);
        if (failOnOverflow && bytes.size() == maximum && input.read() != -1) throw new IOException("Model JSON response limit exceeded");
        return bytes.toByteArray();
    }

    private final class Round {
        final ChatGenerationResources resources; final Consumer<ModelDelta> output;
        final StringBuilder content = new StringBuilder(), id = new StringBuilder(), arguments = new StringBuilder();
        boolean done; String finishReason;
        Round(ChatGenerationResources resources, Consumer<ModelDelta> output) { this.resources = resources; this.output = output; }
        void accept(String frame) throws IOException {
            JsonNode root = mapper.readTree(frame);
            if (root == null || root.has("error") || !root.path("choices").isArray() || root.path("choices").isEmpty())
                throw new IOException("Invalid model streaming response");
            var choice = root.path("choices").get(0);
            var finish = choice.get("finish_reason");
            if (finish != null && !finish.isNull()) {
                if (!finish.isTextual()) throw new IOException("Invalid model completion reason");
                finishReason = finish.asText();
                if (!finishReason.isBlank() && !Set.of("stop", "tool_calls").contains(finishReason))
                    throw new IOException("Model response did not complete successfully");
            }
            var delta = choice.path("delta");
            if (!delta.isObject()) throw new IOException("Invalid model delta");
            append(delta.get("content"), ModelDelta.Kind.CONTENT);
            var calls = delta.get("tool_calls");
            if (calls != null && !calls.isNull()) {
                if (!calls.isArray() || calls.size() > 1) throw new IOException("Invalid single-search tool calls");
                if (!calls.isEmpty()) {
                    var call = calls.get(0);
                    if (!call.isObject() || call.path("index").asInt(0) != 0) throw new IOException("Invalid search tool call");
                    var name = call.path("function").get("name");
                    if (name != null && (!name.isTextual() || !name.asText().equals("search_knowledge_base")))
                        throw new IOException("Unsupported model tool");
                    append(call.get("id"), ModelDelta.Kind.TOOL_CALL_ID);
                    append(call.path("function").get("arguments"), ModelDelta.Kind.TOOL_CALL_ARGUMENTS);
                }
            }
        }
        void append(JsonNode node, ModelDelta.Kind kind) throws IOException {
            if (node == null || node.isNull()) return;
            if (!node.isTextual()) throw new IOException("Invalid model delta value");
            String text = node.textValue(); if (text.isEmpty()) return;
            switch (kind) {
                case CONTENT -> { resources.addContentCharacters(text.length(), properties.getMaxStreamContentChars()); content.append(text); }
                case TOOL_CALL_ID -> { if (id.length() + (long)text.length() > properties.getMaxToolArgumentsChars()) throw new IOException("Model tool ID limit exceeded"); id.append(text); }
                case TOOL_CALL_ARGUMENTS -> { if (arguments.length() + (long)text.length() > properties.getMaxToolArgumentsChars()) throw new IOException("Model tool arguments limit exceeded"); arguments.append(text); }
            }
            output.accept(new ModelDelta(kind, text)); resources.checkRunning();
        }
    }

    private record OwnedResponse(HttpResponse<InputStream> response, InputStream body) {
        int statusCode() { return response.statusCode(); }
    }
    @FunctionalInterface private interface BodyOperation<T> { T apply(OwnedResponse response) throws IOException; }
    private static final class Done extends RuntimeException { }
    private static final class CloseOnceBody extends FilterInputStream {
        final AtomicBoolean closed = new AtomicBoolean();
        CloseOnceBody(InputStream input) { super(input); }
        @Override public void close() throws IOException { if (closed.compareAndSet(false, true)) super.close(); }
    }
}
