package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.config.ModelHttpProperties;
import com.yizhaoqi.smartpai.service.chat.ChatRequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

@Service
public class DeepSeekClient {
    private final BlockingModelHttpClient http;
    private final String model;
    private final AiProperties ai;
    private final ObjectMapper mapper;

    public DeepSeekClient(String url, String key, String model, AiProperties ai, ObjectMapper mapper) {
        this(url, key, model, ai, mapper, "", new ModelHttpProperties());
    }
    public DeepSeekClient(String url, String key, String model, AiProperties ai, ObjectMapper mapper, String proxy) {
        this(url, key, model, ai, mapper, proxy, new ModelHttpProperties());
    }
    @Autowired
    public DeepSeekClient(@Value("${deepseek.api.url}") String url, @Value("${deepseek.api.key}") String key,
                          @Value("${deepseek.api.model}") String model, AiProperties ai, ObjectMapper mapper,
                          @Value("${deepseek.api.proxy-url:}") String proxy, ModelHttpProperties properties) {
        this.http = new BlockingModelHttpClient(url, key, proxy, properties, mapper);
        this.model = model; this.ai = ai; this.mapper = mapper;
    }

    public ModelRoundResult streamWithTools(List<Map<String,Object>> messages, List<Map<String,Object>> tools,
                                           ChatRequestContext context, Consumer<ModelDelta> onDelta) {
        try { return http.stream(request(messages, tools), context.generationResources(), onDelta); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("Model request interrupted"); }
        catch (IOException e) { throw new IllegalStateException("Model service unavailable", e); }
    }

    public ModelRoundResult streamResponse(List<Map<String,Object>> messages, ChatRequestContext context,
                                          Consumer<String> onContent) {
        var response = streamWithTools(messages, List.of(), context, delta -> {
            if (delta.kind() == ModelDelta.Kind.CONTENT) onContent.accept(delta.value());
        });
        if (!response.toolCalls().isEmpty())
            throw new IllegalStateException("Model returned a tool call when tools were disabled");
        return response;
    }

    private Map<String,Object> request(List<Map<String,Object>> messages, List<Map<String,Object>> tools) {
        var request = new HashMap<String,Object>();
        request.put("model", model); request.put("messages", messages); request.put("stream", true);
        if (!tools.isEmpty()) request.put("tools", tools);
        var generation = ai.getGeneration();
        if (generation.getTemperature() != null) request.put("temperature", generation.getTemperature());
        if (generation.getTopP() != null) request.put("top_p", generation.getTopP());
        if (generation.getMaxTokens() != null) request.put("max_tokens", generation.getMaxTokens());
        return request;
    }

    /** Summary generation on the existing compression worker, including bounded body reads. */
    public String callSync(String prompt, Duration timeout) {
        var request = Map.<String,Object>of("model", model, "messages", List.of(Map.of("role", "user", "content", prompt)),
                "temperature", 0.1, "max_tokens", 1024);
        try {
            var node = mapper.readTree(http.postJson(request, timeout));
            var content = node == null ? null : node.path("choices").path(0).path("message").get("content");
            if (node == null || node.has("error") || content == null || !content.isTextual())
                throw new IOException("Invalid model summary response");
            return content.textValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new CancellationException("Summary request interrupted");
        } catch (IOException e) { throw new IllegalStateException("Model summary unavailable", e); }
    }
}
