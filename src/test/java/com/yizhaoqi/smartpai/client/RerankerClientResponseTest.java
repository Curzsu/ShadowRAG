package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RerankerClientResponseTest {

    private final RerankerClient client = new RerankerClient(
            WebClient.builder().baseUrl("http://localhost").build(),
            new ObjectMapper(),
            true
    );

    @Test
    void parsesCurrentTeiTopLevelArrayWithScoreField() throws Exception {
        List<RerankerClient.RerankResult> results = parse(
                "[{\"index\":1,\"score\":0.91},{\"index\":0,\"score\":0.12}]"
        );

        assertEquals(List.of(
                new RerankerClient.RerankResult(1, 0.91),
                new RerankerClient.RerankResult(0, 0.12)
        ), results);
    }

    @Test
    void preservesLegacyWrappedResponseWithRelevanceScore() throws Exception {
        List<RerankerClient.RerankResult> results = parse(
                "{\"results\":[{\"index\":2,\"relevance_score\":0.77}]}"
        );

        assertEquals(
                List.of(new RerankerClient.RerankResult(2, 0.77)),
                results
        );
    }

    @SuppressWarnings("unchecked")
    private List<RerankerClient.RerankResult> parse(String response) throws Exception {
        Method method = RerankerClient.class.getDeclaredMethod("parseResponse", String.class);
        method.setAccessible(true);
        return (List<RerankerClient.RerankResult>) method.invoke(client, response);
    }
}
