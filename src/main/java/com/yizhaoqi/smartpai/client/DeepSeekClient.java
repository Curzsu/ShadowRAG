package com.yizhaoqi.smartpai.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yizhaoqi.smartpai.config.AiProperties;

@Service
public class DeepSeekClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;
    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);

    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
                         @Value("${deepseek.api.key}") String apiKey,
                         @Value("${deepseek.api.model}") String model,
                         AiProperties aiProperties,
                         ObjectMapper objectMapper) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);

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
    
    public void streamResponse(String userMessage,
                             String context,
                             List<Map<String, String>> history,
                             Consumer<String> onChunk,
                             Consumer<Throwable> onError,
                             Runnable onComplete) {

        Map<String, Object> request = buildRequest(userMessage, context, history);

        webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                    chunk -> processChunk(chunk, onChunk),
                    onError,
                    onComplete
                );
    }
    
    private Map<String, Object> buildRequest(String userMessage, 
                                           String context,
                                           List<Map<String, String>> history) {
        logger.info("构建请求，用户消息：{}，上下文长度：{}，历史消息数：{}", 
                   userMessage, 
                   context != null ? context.length() : 0, 
                   history != null ? history.size() : 0);
        
        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessages(userMessage, context, history));
        request.put("stream", true);
        // 生成参数
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }
    
    private List<Map<String, String>> buildMessages(String userMessage,
                                                  String context,
                                                  List<Map<String, String>> history) {
        List<Map<String, String>> messages = new ArrayList<>();

        AiProperties.Prompt promptCfg = aiProperties.getPrompt();

        // 1. 构建统一的 system 指令（规则 + 参考信息）
        StringBuilder sysBuilder = new StringBuilder();
        String rules = promptCfg.getRules();
        if (rules != null) {
            sysBuilder.append(rules).append("\n\n");
        }

        String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
        String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
        sysBuilder.append(refStart).append("\n");

        if (context != null && !context.isEmpty()) {
            sysBuilder.append(context);
        } else {
            String noResult = promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）";
            sysBuilder.append(noResult).append("\n");
        }

        sysBuilder.append(refEnd);

        String systemContent = sysBuilder.toString();
        messages.add(Map.of(
            "role", "system",
            "content", systemContent
        ));
        logger.debug("添加了系统消息，长度: {}", systemContent.length());

        // 2. 追加历史消息（若有）
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 3. 当前用户问题
        messages.add(Map.of(
            "role", "user",
            "content", userMessage
        ));

        return messages;
    }
    
    private void processChunk(String chunk, Consumer<String> onChunk) {
        try {
            // 检查是否是结束标记
            if ("[DONE]".equals(chunk)) {
                logger.debug("对话结束");
                return;
            }

            // 直接解析 JSON
            JsonNode node = objectMapper.readTree(chunk);
            String content = node.path("choices")
                               .path(0)
                               .path("delta")
                               .path("content")
                               .asText("");

            if (!content.isEmpty()) {
                onChunk.accept(content);
            }
        } catch (Exception e) {
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }

    public void streamWithTools(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            Consumer<String> onContentDelta,
            Consumer<String> onToolCallId,
            Consumer<String> onToolCallArgs,
            Consumer<Throwable> onError,
            Runnable onComplete) {

        Map<String, Object> request = buildToolsRequest(messages, tools);

        webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                    chunk -> processToolChunk(chunk, onContentDelta, onToolCallId, onToolCallArgs),
                    onError,
                    onComplete
                );
    }

    public void streamResponse(List<Map<String, Object>> messages,
                               Consumer<String> onChunk,
                               Consumer<Throwable> onError,
                               Runnable onComplete) {

        Map<String, Object> request = new HashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", true);
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) request.put("temperature", gen.getTemperature());
        if (gen.getTopP() != null) request.put("top_p", gen.getTopP());
        if (gen.getMaxTokens() != null) request.put("max_tokens", gen.getMaxTokens());

        webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                    chunk -> processChunk(chunk, onChunk),
                    onError,
                    onComplete
                );
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

    private void processToolChunk(String chunk, Consumer<String> onContentDelta,
                                  Consumer<String> onToolCallId, Consumer<String> onToolCallArgs) {
        try {
            if ("[DONE]".equals(chunk)) {
                logger.debug("工具流式对话结束");
                return;
            }

            JsonNode node = objectMapper.readTree(chunk);
            JsonNode delta = node.path("choices").path(0).path("delta");

            // 1. Handle text content (LLM answering directly)
            String content = delta.path("content").asText("");
            if (!content.isEmpty()) {
                onContentDelta.accept(content);
            }

            // 2. Handle tool calls (arguments arrive as fragments)
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray() && !toolCalls.isEmpty()) {
                JsonNode tc = toolCalls.get(0);
                JsonNode function = tc.path("function");

                String id = tc.path("id").asText("");
                if (!id.isEmpty()) {
                    onToolCallId.accept(id);
                }

                String args = function.path("arguments").asText("");
                if (!args.isEmpty()) {
                    onToolCallArgs.accept(args);
                }
            }
        } catch (Exception e) {
            logger.error("处理工具数据块时出错: {}", e.getMessage(), e);
        }
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