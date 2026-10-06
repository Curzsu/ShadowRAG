package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextBudgetAgentTest {
    Map<String,Object> message(String role,String content) { return Map.of("role",role,"content",content); }
    Map<String,Object> calls(String...ids) {
        return Map.of("role","assistant","tool_calls",Arrays.stream(ids).map(id -> Map.of("id",id,"type","function",
                "function",Map.of("name","search_knowledge_base","arguments","{\"query\":\"资料\"}"))).toList());
    }
    @Test void oldTurnsAreRemovedButAllCurrentCallsAndToolSourcesSurviveSharedTruncation() {
        var properties = new AiProperties(); properties.getContext().setWindowTokens(600); properties.getContext().setSafetyMarginTokens(20);
        var estimator=new TokenEstimator(new ObjectMapper()); var budget=new ContextBudgetService(properties,estimator);
        var messages = List.of(message("system","rules"),message("user","old ".repeat(1000)),message("assistant","old reply"),
                message("user","current"),calls("a","b"),
                Map.<String,Object>of("role","tool","tool_call_id","a","content","[来源索引] A:1\n"+"资料甲 ".repeat(2000)),
                Map.<String,Object>of("role","tool","tool_call_id","b","content","[来源索引] B:2\n"+"资料乙 ".repeat(2000)),
                calls("c"),Map.<String,Object>of("role","tool","tool_call_id","c","content","small"));
        var fitted=budget.fitAgent(messages,50,20);
        assertEquals(List.of("system","user","assistant","tool","tool","assistant","tool"),fitted.stream().map(m -> m.get("role")).toList());
        assertEquals("current",fitted.get(1).get("content"));
        assertEquals(List.of("a","b","c"),fitted.stream().filter(m -> "tool".equals(m.get("role"))).map(m -> m.get("tool_call_id")).toList());
        assertTrue(fitted.get(3).get("content").toString().startsWith("[来源索引] A:1\n"));
        assertTrue(fitted.get(4).get("content").toString().startsWith("[来源索引] B:2\n"));
        assertTrue(fitted.get(3).get("content").toString().contains("已按上下文预算截断"));
        assertTrue(fitted.get(4).get("content").toString().contains("已按上下文预算截断"));
        assertEquals("small",fitted.get(6).get("content"),"Small results should remain complete while oversized results share truncation");
        assertTrue(estimator.countMessages(fitted)<=510); assertEquals(9,messages.size());
    }
    @Test void oversizedCurrentUserOrProtocolSkeletonFailsRatherThanDroppingMessages() {
        var properties=new AiProperties(); properties.getContext().setWindowTokens(100); properties.getContext().setSafetyMarginTokens(10);
        var budget=new ContextBudgetService(properties,new TokenEstimator(new ObjectMapper()));
        assertThrows(ContextWindowExceededException.class,() -> budget.fitAgent(List.of(message("system","rules"),message("user","current ".repeat(500)),calls("a")),20,10));
    }
}
