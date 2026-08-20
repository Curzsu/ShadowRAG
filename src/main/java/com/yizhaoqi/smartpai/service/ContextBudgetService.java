package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.config.AiProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContextBudgetService {

    private static final String TRUNCATION_SUFFIX = "\n\n[检索结果已按上下文预算截断]";

    private final AiProperties properties;
    private final TokenEstimator tokenEstimator;

    public ContextBudgetService(AiProperties properties, TokenEstimator tokenEstimator) {
        this.properties = properties;
        this.tokenEstimator = tokenEstimator;
    }

    public List<Map<String, Object>> fit(List<Map<String, Object>> messages,
                                         int reservedOutputTokens,
                                         int extraTokens,
                                         int protectedTailCount) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        if (reservedOutputTokens < 0 || extraTokens < 0 || protectedTailCount < 0) {
            throw new IllegalArgumentException("Token reservations and protectedTailCount must be non-negative");
        }

        int availableTokens = properties.getContext().getWindowTokens()
                - reservedOutputTokens
                - properties.getContext().getSafetyMarginTokens()
                - extraTokens;
        if (availableTokens <= 0) {
            throw new ContextWindowExceededException(tokenEstimator.countMessages(messages), availableTokens);
        }

        List<Map<String, Object>> fitted = copyMessages(messages);
        int tailCount = Math.min(protectedTailCount, Math.max(0, fitted.size() - 1));

        while (tokenEstimator.countMessages(fitted) > availableTokens
                && fitted.size() > tailCount + 1) {
            removeOldestHistoryUnit(fitted, tailCount);
        }

        if (tokenEstimator.countMessages(fitted) > availableTokens) {
            truncateLastToolResult(fitted, availableTokens);
        }

        int requiredTokens = tokenEstimator.countMessages(fitted);
        if (requiredTokens > availableTokens) {
            throw new ContextWindowExceededException(requiredTokens, availableTokens);
        }
        return List.copyOf(fitted);
    }

    private void removeOldestHistoryUnit(List<Map<String, Object>> messages, int protectedTailCount) {
        int removableEndExclusive = messages.size() - protectedTailCount;
        if (removableEndExclusive <= 1) {
            return;
        }

        String firstRole = String.valueOf(messages.get(1).get("role"));
        if ("user".equals(firstRole) && removableEndExclusive > 2
                && "assistant".equals(messages.get(2).get("role"))) {
            messages.remove(2);
        }
        messages.remove(1);
    }

    private List<Map<String, Object>> copyMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> copied = new ArrayList<>(messages.size());
        for (Map<String, Object> message : messages) {
            copied.add(new LinkedHashMap<>(message));
        }
        return copied;
    }

    private void truncateLastToolResult(List<Map<String, Object>> messages, int availableTokens) {
        int toolIndex = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("tool".equals(messages.get(i).get("role"))) {
                toolIndex = i;
                break;
            }
        }
        if (toolIndex < 0) {
            return;
        }

        String original = String.valueOf(messages.get(toolIndex).getOrDefault("content", ""));
        int low = 0;
        int high = original.length();
        Map<String, Object> best = null;

        while (low <= high) {
            int midpoint = (low + high) >>> 1;
            Map<String, Object> candidate = new LinkedHashMap<>(messages.get(toolIndex));
            candidate.put("content", original.substring(0, midpoint) + TRUNCATION_SUFFIX);
            messages.set(toolIndex, candidate);

            if (tokenEstimator.countMessages(messages) <= availableTokens) {
                best = candidate;
                low = midpoint + 1;
            } else {
                high = midpoint - 1;
            }
        }

        if (best != null) {
            messages.set(toolIndex, best);
        }
    }
}
