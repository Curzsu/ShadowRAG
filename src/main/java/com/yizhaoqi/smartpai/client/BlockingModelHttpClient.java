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
        return round.result();
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
        final StringBuilder content = new StringBuilder(), reasoning = new StringBuilder();
        final SortedMap<Integer, CallParts> calls = new TreeMap<>();
        long argumentCharacters, metadataCharacters, signatureCharacters;
        boolean done, unindexedBatch; String finishReason;
        Round(ChatGenerationResources resources, Consumer<ModelDelta> output) { this.resources = resources; this.output = output; }
        void accept(String frame) throws IOException {
            JsonNode root = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(frame);
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
            String text = text(delta.get("content"));
            if (!text.isEmpty()) {
                resources.addContentCharacters(text.length(), properties.getMaxStreamContentChars()); content.append(text);
                output.accept(new ModelDelta(ModelDelta.Kind.CONTENT,text)); resources.checkRunning();
            }
            String thought = text(delta.get("reasoning_content"));
            if (reasoning.length() + (long) thought.length() > properties.getMaxReasoningChars()) throw new IOException("Model reasoning limit exceeded");
            reasoning.append(thought);
            var fragments = delta.get("tool_calls");
            if (fragments != null && !fragments.isNull()) {
                if (!fragments.isArray() || fragments.size() > properties.getMaxToolCallsPerRound()) throw new IOException("Invalid model tool calls");
                if (!fragments.isEmpty() && unindexedBatch) throw new IOException("Ambiguous tool batch continuation");
                // Some compatible providers send one complete call batch instead of indexed deltas.
                boolean wholeBatch=!fragments.isEmpty();
                for(var fragment:fragments) wholeBatch=wholeBatch && fragment.isObject() && !fragment.has("index");
                if(wholeBatch) {
                    if(!calls.isEmpty()) throw new IOException("Mixed model tool protocols");
                    long characters=0;
                    for(var fragment:fragments) {
                        String arguments=completeArguments(fragment);
                        characters+=arguments.length();
                        if(characters>properties.getMaxToolArgumentsChars()) throw new IOException("Model tool arguments limit exceeded");
                        JsonNode parsed=mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(arguments);
                        if(parsed==null || !parsed.isObject()) throw new IOException("Incomplete unindexed tool arguments");
                    }
                    unindexedBatch=true;
                }
                int ordinal=0;
                for (var fragment : fragments) {
                    var indexNode = fragment.get("index");
                    if (!wholeBatch && (!fragment.isObject() || indexNode == null || !indexNode.isIntegralNumber() || !indexNode.canConvertToInt()
                            || indexNode.intValue()<0 || indexNode.intValue()>=properties.getMaxToolCallsPerRound())) throw new IOException("Invalid tool index");
                    if (fragment.has("type") && !"function".equals(fragment.path("type").asText())) throw new IOException("Invalid tool type");
                    int index=wholeBatch ? ordinal++ : indexNode.intValue(); var parts=calls.computeIfAbsent(index, ignored -> new CallParts());
                    retainSignature(parts,fragment);
                    appendMetadata(parts.id, text(fragment.get("id")),index,ModelDelta.Kind.TOOL_CALL_ID);
                    var function=fragment.get("function");
                    if (function!=null && !function.isObject()) throw new IOException("Invalid tool function");
                    if (function!=null) {
                        appendName(parts.name,text(function.get("name")),index);
                        String arguments=text(function.get("arguments"));
                        argumentCharacters+=arguments.length();
                        if(argumentCharacters>properties.getMaxToolArgumentsChars()) throw new IOException("Model tool arguments limit exceeded");
                        parts.arguments.append(arguments);
                        if(!arguments.isEmpty()) output.accept(new ModelDelta(ModelDelta.Kind.TOOL_CALL_ARGUMENTS,arguments,index));
                    }
                    resources.checkRunning();
                }
            }
        }
        String completeArguments(JsonNode call) throws IOException {
            var function=call.path("function");
            if(!call.path("id").isTextual() || call.path("id").asText().isBlank()
                    || !"function".equals(call.path("type").asText()) || !function.isObject()
                    || !function.path("name").isTextual() || function.path("name").asText().isBlank()
                    || !function.path("arguments").isTextual()) throw new IOException("Incomplete unindexed tool call");
            return function.path("arguments").asText();
        }
        void retainSignature(CallParts parts,JsonNode call) throws IOException {
            var extra=call.get("extra_content");
            if(extra==null || extra.isNull()) return;
            if(!extra.isObject()) throw new IOException("Invalid tool extension metadata");
            var google=extra.get("google");
            if(google==null) return;
            if(!google.isObject()) throw new IOException("Invalid Google tool metadata");
            var signature=google.get("thought_signature");
            if(signature==null) return;
            if(!signature.isTextual() || signature.asText().isBlank()) throw new IOException("Invalid tool thought signature");
            String value=signature.textValue();
            if(parts.signature!=null) {
                if(!parts.signature.equals(value)) throw new IOException("Conflicting tool thought signatures");
                return;
            }
            signatureCharacters+=value.length();
            if(signatureCharacters>properties.getMaxToolSignatureChars()) throw new IOException("Model tool signature limit exceeded");
            parts.signature=value;
        }
        String text(JsonNode node) throws IOException {
            if(node==null || node.isNull()) return "";
            if(!node.isTextual()) throw new IOException("Invalid model delta value");
            return node.textValue();
        }
        void appendName(StringBuilder target,String fragment,int index) throws IOException {
            if(fragment.isEmpty() || target.toString().equals(fragment)) return;
            String addition=fragment.startsWith(target.toString()) ? fragment.substring(target.length()) : fragment;
            appendMetadata(target,addition,index,null);
        }
        void appendMetadata(StringBuilder target,String addition,int index,ModelDelta.Kind kind) throws IOException {
            if(addition.isEmpty()) return;
            metadataCharacters+=addition.length();
            if(metadataCharacters>4096) throw new IOException("Model tool metadata limit exceeded");
            target.append(addition);
            if(kind!=null && !addition.isEmpty()) output.accept(new ModelDelta(kind,addition,index));
        }
        ModelRoundResult result() throws IOException {
            var result=new ArrayList<ModelToolCall>(); var ids=new HashSet<String>();
            for(var entry:calls.entrySet()) {
                var parts=entry.getValue();
                if(parts.id.toString().isBlank() || parts.name.toString().isBlank() || !ids.add(parts.id.toString()))
                    throw new IOException("Missing or duplicate model tool identity");
                result.add(new ModelToolCall(entry.getKey(),parts.id.toString(),parts.name.toString(),parts.arguments.toString(),parts.signature));
            }
            if("tool_calls".equals(finishReason) && result.isEmpty()) throw new IOException("Missing model tool calls");
            return new ModelRoundResult(content.toString(),reasoning.toString(),result,finishReason);
        }
    }
    private static final class CallParts {
        final StringBuilder id=new StringBuilder(),name=new StringBuilder(),arguments=new StringBuilder();
        String signature;
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
