package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.support.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes=ChatStreamingAcceptanceFixture.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"jwt.secret-key="+ChatStreamingAcceptanceFixture.SECRET,"logging.level.com.yizhaoqi.smartpai=ERROR"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ChatReActHttpTest extends ChatStreamingAcceptanceSupport {
    @Test void realMvcStreamsTwoIntermediateRoundsAndPersistsOnlyFinalAnswer() throws Exception {
        int before=model.requests(); String conversation=UUID.randomUUID().toString();
        var result=answer(directUrl(),"alice",conversation,"react");
        assertEquals(3,model.requests()-before); assertEquals("收入由100增至120",result.text());
        assertEquals(List.of("report A","report B"),state.searchCalls.stream().map(call -> call.query()).toList());
        assertTrue(state.searchCalls.stream().allMatch(call -> "alice".equals(call.username()) && call.limit()==10));
        assertEquals(List.of("收入由100增至120"),state.persisted.get(conversation)); assertEquals(1,result.completions());
        assertEquals(3,result.chunkMillis().size()); awaitEmpty();
        saveMetrics("react-multiround-metrics",Map.of("modelCalls",3,"searchCalls",2,"persistedTurns",1,
                "rounds",3,"settledResources",resourceMetrics()));
    }
}
