package com.yizhaoqi.smartpai.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yizhaoqi.smartpai.config.AiProperties;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;
import java.net.URI;

@Service
public class DeepSeekClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);

    public DeepSeekClient(String apiUrl, String apiKey, String model,
                         AiProperties aiProperties, ObjectMapper objectMapper) {
        this(apiUrl, apiKey, model, aiProperties, objectMapper, "");
    }

    @Autowired
    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
                         @Value("${deepseek.api.key}") String apiKey,
                         @Value("${deepseek.api.model}") String model,
                         AiProperties aiProperties,
                         ObjectMapper objectMapper,
                         @Value("${deepseek.api.proxy-url:}") String proxyUrl) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);
        if (proxyUrl != null && !proxyUrl.isBlank()) {
            URI proxy = parseProxy(proxyUrl);
            HttpClient transport = HttpClient.create().proxy(spec -> spec.type(ProxyProvider.Proxy.HTTP)
                    .host(proxy.getHost()).port(proxy.getPort()));
            builder.clientConnector(new ReactorClientHttpConnector(transport));
        }

        // 只有当 API key 不为空时才添加 Authorization header
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        this.webClient = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    private static URI parseProxy(String configuredProxy) {
        String message = "deepseek.api.proxy-url must be an unauthenticated HTTP proxy with an explicit valid port";
        URI proxy;
        try { proxy = URI.create(configuredProxy); }
        catch (IllegalArgumentException malformed) { throw new IllegalArgumentException(message); }
        if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null
                || proxy.getRawUserInfo() != null || proxy.getPort() < 1 || proxy.getPort() > 65535
                || proxy.getRawQuery() != null || proxy.getRawFragment() != null
                || (proxy.getRawPath() != null && !proxy.getRawPath().isEmpty() && !"/".equals(proxy.getRawPath()))) {
            throw new IllegalArgumentException(message);
        }
        return proxy;
    }

    /** Cold publisher: cancelling it disposes the active supplier HTTP response. */
    public Flux<ModelDelta> streamWithTools(List<Map<String, Object>> messages,
                                            List<Map<String, Object>> tools) {
        return Flux.defer(() -> streamRequest(buildToolsRequest(messages, tools)));
    }

    public Flux<String> streamResponse(List<Map<String, Object>> messages) {
        return Flux.defer(() -> {
            Map<String, Object> request = buildToolsRequest(messages, List.of());
            request.remove("tools");
            return streamRequest(request)
                    .filter(delta -> delta.kind() == ModelDelta.Kind.CONTENT)
                    .map(ModelDelta::value);
        });
    }

    private Flux<ModelDelta> streamRequest(Map<String, Object> request) {
        return Flux.defer(() -> {
            AtomicBoolean receivedDone = new AtomicBoolean();
            return webClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToFlux(String.class)
                    // The provider may leave the HTTP connection open after this sentinel.
                    .takeWhile(frame -> {
                        if ("[DONE]".equals(frame.trim())) {
                            receivedDone.set(true);
                            return false;
                        }
                        return true;
                    })
                    .concatMapIterable(this::decodeModelDeltas)
                    // HTTP EOF is transport completion, not proof that the model finished.
                    .concatWith(Flux.defer(() -> receivedDone.get() ? Flux.empty()
                            : Flux.error(new IllegalStateException("Model stream ended before completion marker"))));
        });
    }

    private List<ModelDelta> decodeModelDeltas(String frame) {
        try {
            JsonNode root = objectMapper.readTree(frame);
            if (root == null || root.has("error") || !root.path("choices").isArray()
                    || root.path("choices").isEmpty()) {
                throw new IllegalArgumentException("Invalid model streaming response");
            }
            JsonNode delta = root.path("choices").get(0).path("delta");
            if (!delta.isObject()) throw new IllegalArgumentException("Invalid model delta");
            List<ModelDelta> result = new ArrayList<>(3);
            addTextDelta(result, delta.get("content"), ModelDelta.Kind.CONTENT);
            JsonNode toolCalls = delta.get("tool_calls");
            if (toolCalls != null && !toolCalls.isNull()) {
                if (!toolCalls.isArray()) throw new IllegalArgumentException("Invalid model tool calls");
                // Preserve the existing single-search-tool protocol.
                if (!toolCalls.isEmpty()) {
                    JsonNode toolCall = toolCalls.get(0);
                    if (!toolCall.isObject()) throw new IllegalArgumentException("Invalid model tool call");
                    addTextDelta(result, toolCall.get("id"), ModelDelta.Kind.TOOL_CALL_ID);
                    addTextDelta(result, toolCall.path("function").get("arguments"), ModelDelta.Kind.TOOL_CALL_ARGUMENTS);
                }
            }
            return result;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalStateException("Invalid model streaming response", error);
        }
    }

    private void addTextDelta(List<ModelDelta> deltas, JsonNode value, ModelDelta.Kind kind) {
        if (value == null || value.isNull()) return;
        if (!value.isTextual()) throw new IllegalArgumentException("Invalid model delta value");
        if (!value.textValue().isEmpty()) deltas.add(new ModelDelta(kind, value.textValue()));
    }

    private Map<String, Object> buildToolsRequest(List<Map<String, Object>> messages,
                                                   List<Map<String, Object>> tools) {
        logger.info("构建工具请求，消息数: {}, 工具数: {}", messages.size(), tools.size());

        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", true);
        request.put("tools", tools);

        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) request.put("temperature", gen.getTemperature());
        if (gen.getTopP() != null) request.put("top_p", gen.getTopP());
        if (gen.getMaxTokens() != null) request.put("max_tokens", gen.getMaxTokens());
        return request;
    }

    /**
     * Synchronous LLM call for compression summaries.
     * Blocks the calling thread until response is received.
     * Safe to call from the compression thread pool — NOT from Tomcat's request-handling threads.
     */
    public String callSync(String prompt, Duration timeout) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        requestBody.put("temperature", 0.1);
        requestBody.put("max_tokens", 1024);

        return webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(timeout)
                .map(response -> {
                    try {
                        JsonNode node = objectMapper.readTree(response);
                        return node.path("choices").path(0).path("message").path("content").asText();
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException("Failed to parse LLM response", e);
                    }
                })
                .block(timeout.multipliedBy(2));
    }
}
