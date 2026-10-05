package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cross-Encoder 精排客户端
 * 调用 HuggingFace TEI 的 /rerank 接口，对 RRF 融合结果进行精排
 */
@Component
public class RerankerClient {

    private static final Logger logger = LoggerFactory.getLogger(RerankerClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public RerankerClient(WebClient rerankerWebClient,
                          ObjectMapper objectMapper,
                          @Value("${reranker.api.enabled:true}") boolean enabled) {
        this.webClient = rerankerWebClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /**
     * 对候选文档进行 Cross-Encoder 精排
     *
     * @param query     用户查询
     * @param documents 候选文档文本列表
     * @param topN      返回前 N 个结果
     * @return 按相关性降序排列的索引-分数对列表，失败时返回 null
     */
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (!enabled) {
            logger.debug("Reranker 未启用，跳过精排");
            return null;
        }
        if (documents == null || documents.isEmpty()) {
            return null;
        }
        try {
            Map<String, Object> request = Map.of(
                    "query", query,
                    "texts", documents,
                    "top_n", Math.min(topN, documents.size())
            );

            logger.debug("调用 TEI rerank，文档数: {}, topN: {}", documents.size(), topN);

            String response = webClient.post()
                    .uri("/rerank")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));

            List<RerankResult> results = parseResponse(response);
            logger.debug("Rerank 完成，返回 {} 个结果", results.size());
            return results;
        } catch (Exception e) {
            logger.warn("Rerank 调用失败，将使用 RRF 原始排序，异常类型: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 解析 TEI /rerank 响应。
     * 当前 TEI 返回顶层数组和 score 字段；同时兼容旧的 results 包装与 relevance_score 字段。
     */
    private List<RerankResult> parseResponse(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        JsonNode resultsNode = root.isArray() ? root : root.get("results");
        if (resultsNode == null || !resultsNode.isArray()) {
            throw new RuntimeException("TEI rerank 响应格式错误: 预期顶层数组或 results 数组");
        }

        List<RerankResult> results = new ArrayList<>();
        for (JsonNode node : resultsNode) {
            int index = node.get("index").asInt();
            JsonNode scoreNode = node.has("score") ? node.get("score") : node.get("relevance_score");
            if (scoreNode == null || !scoreNode.isNumber()) {
                throw new RuntimeException("TEI rerank 响应格式错误: 缺少 score 字段");
            }
            double score = scoreNode.asDouble();
            results.add(new RerankResult(index, score));
        }
        return results;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Rerank 结果：原始文档在候选列表中的索引 + Cross-Encoder 相关性分数
     */
    public record RerankResult(int index, double score) {
    }
}
