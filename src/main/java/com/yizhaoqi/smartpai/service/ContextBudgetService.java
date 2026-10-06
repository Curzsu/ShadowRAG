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

    /** Protect the entire active user turn, including every assistant/tool protocol pair. */
    public List<Map<String,Object>> fitAgent(List<Map<String,Object>> messages,int reservedOutputTokens,int toolDefinitionTokens) {
        int currentUser=-1;
        for(int i=messages.size()-1;i>=0;i--) if("user".equals(messages.get(i).get("role"))) { currentUser=i; break; }
        if(currentUser<0) throw new IllegalArgumentException("Agent messages require the current user");
        return fit(messages,reservedOutputTokens,toolDefinitionTokens,messages.size()-currentUser);
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
            truncateToolResults(fitted, availableTokens);
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

        int end=2;
        while(end<removableEndExclusive && !"user".equals(messages.get(end).get("role"))) end++;
        messages.subList(1,Math.min(end,removableEndExclusive)).clear();
    }

    private List<Map<String, Object>> copyMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> copied = new ArrayList<>(messages.size());
        for (Map<String, Object> message : messages) {
            copied.add(new LinkedHashMap<>(message));
        }
        return copied;
    }

    private void truncateToolResults(List<Map<String, Object>> messages, int availableTokens) {
        List<Map<String,Object>> originals=copyMessages(messages);
        int low=0,high=originals.stream().filter(m -> "tool".equals(m.get("role")))
                .mapToInt(m -> String.valueOf(m.getOrDefault("content","")).length()).max().orElse(0);
        List<Map<String,Object>> best=null;
        while(low<=high) {
            int bodyLimit=(low+high)>>>1;
            var candidate=copyMessages(originals);
            for(var message:candidate) {
                if(!"tool".equals(message.get("role"))) continue;
                String original=String.valueOf(message.getOrDefault("content",""));
                int prefix=original.startsWith("[来源索引]") ? Math.max(0,original.indexOf('\n')+1) : 0;
                // Share an absolute body allowance: short results stay whole while larger results shrink together.
                int kept=prefix+Math.min(original.length()-prefix,bodyLimit);
                if(kept>prefix && kept<original.length() && Character.isHighSurrogate(original.charAt(kept-1))) kept--;
                if(kept<original.length()) message.put("content",original.substring(0,kept)+TRUNCATION_SUFFIX);
            }
            if(tokenEstimator.countMessages(candidate)<=availableTokens) { best=candidate; low=bodyLimit+1; }
            else high=bodyLimit-1;
        }
        if(best!=null) { messages.clear(); messages.addAll(best); }
    }
}
