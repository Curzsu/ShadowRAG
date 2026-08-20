package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBudgetServiceTest {

    private TokenEstimator tokenEstimator;
    private ContextBudgetService service;

    @BeforeEach
    void setUp() {
        AiProperties properties = new AiProperties();
        properties.getContext().setWindowTokens(300);
        properties.getContext().setSafetyMarginTokens(20);
        tokenEstimator = new TokenEstimator(new ObjectMapper());
        service = new ContextBudgetService(properties, tokenEstimator);
    }

    @Test
    void fit_shouldDropOldestHistoryAndPreserveSystemAndProtocolTail() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message("system", "rules"));
        messages.add(message("user", "old question ".repeat(300)));
        messages.add(message("assistant", "old answer ".repeat(300)));
        messages.add(message("user", "current question"));
        messages.add(toolCallMessage());
        messages.add(message("tool", "small result"));

        List<Map<String, Object>> fitted = service.fit(messages, 30, 0, 3);

        assertEquals(List.of("system", "user", "assistant", "tool"),
                fitted.stream().map(m -> String.valueOf(m.get("role"))).toList());
        assertTrue(tokenEstimator.countMessages(fitted) <= 250);
        assertEquals(6, messages.size(), "budgeting must not mutate the caller's message list");
    }

    @Test
    void fit_shouldDropACompleteTurnInsteadOfLeavingAnOrphanAssistantMessage() {
        List<Map<String, Object>> messages = List.of(
                message("system", "rules"),
                message("user", "old question ".repeat(300)),
                message("assistant", "short old answer"),
                message("user", "current question")
        );

        List<Map<String, Object>> fitted = service.fit(messages, 30, 0, 1);

        assertEquals(List.of("system", "user"),
                fitted.stream().map(m -> String.valueOf(m.get("role"))).toList());
        assertEquals("current question", fitted.get(1).get("content"));
    }

    @Test
    void fit_shouldTruncateOversizedToolResultWhenProtocolTailMustBePreserved() {
        List<Map<String, Object>> messages = List.of(
                message("system", "rules"),
                message("user", "current question"),
                toolCallMessage(),
                message("tool", "document result ".repeat(1000))
        );
        int originalLength = String.valueOf(messages.get(3).get("content")).length();

        List<Map<String, Object>> fitted = service.fit(messages, 30, 0, 3);

        assertEquals(4, fitted.size());
        assertTrue(String.valueOf(fitted.get(3).get("content")).length() < originalLength);
        assertTrue(String.valueOf(fitted.get(3).get("content")).contains("已按上下文预算截断"));
        assertTrue(tokenEstimator.countMessages(fitted) <= 250);
    }

    @Test
    void fit_shouldRejectWhenRequiredMessagesAloneExceedBudget() {
        List<Map<String, Object>> messages = List.of(
                message("system", "rules"),
                message("user", "untrimmable current question ".repeat(1000))
        );

        ContextWindowExceededException error = assertThrows(
                ContextWindowExceededException.class,
                () -> service.fit(messages, 30, 0, 1));

        assertTrue(error.getRequiredTokens() > error.getAvailableTokens());
        assertEquals(250, error.getAvailableTokens());
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", role);
        message.put("content", content);
        return message;
    }

    private static Map<String, Object> toolCallMessage() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("tool_calls", List.of(Map.of(
                "id", "call-1",
                "type", "function",
                "function", Map.of("name", "search_knowledge_base", "arguments", "{\"query\":\"x\"}")
        )));
        return message;
    }
}
