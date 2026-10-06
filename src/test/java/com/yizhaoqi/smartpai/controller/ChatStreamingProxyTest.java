package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.support.ChatStreamingAcceptanceFixture;
import com.yizhaoqi.smartpai.support.ChatStreamingAcceptanceSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Requires an independently running real Nginx pointed at chat.acceptance.backend-port. */
@EnabledIfSystemProperty(named = "chat.acceptance.proxy-url", matches = "https?://.+")
@SpringBootTest(classes = ChatStreamingAcceptanceFixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"server.port=${chat.acceptance.backend-port:0}",
                "jwt.secret-key=" + ChatStreamingAcceptanceFixture.SECRET,
                "logging.level.com.yizhaoqi.smartpai=ERROR"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatStreamingProxyTest extends ChatStreamingAcceptanceSupport {
    private String proxyUrl() { return System.getProperty("chat.acceptance.proxy-url").replaceAll("/$", ""); }

    // Regression caught: Nginx batching all SSE chunks, delaying first text or corrupting frames.
    @Test @Order(1) @Timeout(60)
    void nginxDeliversTwentyMillisFramesBeforeModelCompletionWithFirstTextP95UnderOneSecond() throws Exception {
        for (int i = 0; i < 3; i++) answer(proxyUrl(), "acceptance-warmup", UUID.randomUUID().toString(), "short");
        awaitEmpty();
        List<Answer> answers = concurrentAnswers(proxyUrl(), 16, "load");
        for (Answer answer : answers) {
            assertEquals(200, answer.chunkMillis().size());
            assertEquals(expectedText(answer.marker(), 200), answer.text());
            assertTrue(answer.chunkMillis().get(199) - answer.chunkMillis().get(0) >= 3000,
                    "Multiple frames must arrive before the final frame");
            assertTrue(answer.chunkMillis().get(20) - answer.chunkMillis().get(0) >= 200,
                    "Early frames must arrive across time, not in one completed-response batch");
        }
        assertTrue(p95(answers) <= 1000, "Nginx first-body p95 was " + p95(answers) + "ms");
        awaitEmpty();
        saveMetrics("task-7-proxy-frame-metrics", Map.of("proxy", "real-nginx", "requests", 16,
                "chunksPerRequest", 200, "upstreamIntervalMillis", 20, "firstTextP95Millis", p95(answers),
                "firstTextMillis", answers.stream().map(Answer::firstTextMillis).sorted().toList(),
                "settledResources", resourceMetrics()));
    }

    // Regression caught: default heartbeat missing while real blocking tool search outlasts proxy idle timeout.
    @Test @Order(2) @Timeout(150)
    void defaultHeartbeatKeepsRealNginxAliveAcrossNinetyFiveSecondToolGapAndLaterBody() throws Exception {
        assertEquals(15000, properties.getHeartbeatIntervalMs());
        assertEquals(300000, properties.getGenerationTimeoutMs());
        assertEquals(320000, properties.getEmitterTimeoutMs());
        state.toolGapMillis = 95000;
        int modelsBefore = model.requests();
        Answer answer;
        try { answer = answer(proxyUrl(), "acceptance-gap", UUID.randomUUID().toString(), "gap"); }
        finally { state.toolGapMillis = 0; }
        assertEquals(2, model.requests() - modelsBefore, "Both RAG model rounds must run");
        assertEquals(expectedText(answer.marker(), 1), answer.text());
        assertTrue(answer.firstTextMillis() >= 95000, "Tool wait must exceed 90 seconds");
        assertTrue(answer.heartbeats().size() >= 6, "Default 15-second heartbeats must remain visible through Nginx");
        long previous = 0;
        for (long heartbeat : answer.heartbeats()) {
            assertTrue(heartbeat - previous <= 20000, "Proxy must pass individual heartbeats, not batch them");
            previous = heartbeat;
        }
        assertTrue(answer.firstTextMillis() - previous <= 20000);
        awaitEmpty();
        saveMetrics("task-7-proxy-heartbeat-metrics", Map.of("proxy", "real-nginx", "toolGapMillis", 95000,
                "heartbeatIntervalMillis", 15000, "generationTimeoutMillis", 300000, "emitterTimeoutMillis", 320000,
                "observedHeartbeatMillis", answer.heartbeats(), "laterBodyMillis", answer.firstTextMillis(),
                "modelRounds", model.requests() - modelsBefore, "settledResources", resourceMetrics()));
    }
}
