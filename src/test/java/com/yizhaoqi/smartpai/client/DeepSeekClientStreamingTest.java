package com.yizhaoqi.smartpai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.support.MockModelSseServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.yizhaoqi.smartpai.support.ChatGenerationProbe;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekClientStreamingTest {
    private MockModelSseServer server;
    private DeepSeekClient client;
    private final List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "test question"));
    @BeforeEach void setup() throws Exception {
        server = new MockModelSseServer();
        client = new DeepSeekClient(server.url(), "test-model-token", "test-model", new AiProperties(), new ObjectMapper());
    }
    @AfterEach void close() { server.close(); }

    @Test void ordinaryCallStartsOnlyWhenInvoked() {
        server.enqueue(s -> { s.content("hello"); s.data("[DONE]"); });
        ChatGenerationProbe.Generation<ModelDelta> reply = (context, out) -> client.streamWithTools(messages, List.of(), context, out);
        assertEquals(0, server.requests());
        ChatGenerationProbe.create(reply).expectNext(new ModelDelta(ModelDelta.Kind.CONTENT, "hello"))
                .expectComplete().verify(Duration.ofSeconds(5));
        assertEquals(1, server.requests());
    }
    @Test void doneEndsModelStream() {
        server.enqueue(s -> { s.content("answer"); s.data("[DONE]"); s.probeUntilDisconnected(); });
        ChatGenerationProbe.<String>create((context, out) -> client.streamResponse(messages, context, out)).expectNext("answer")
                .expectComplete().verify(Duration.ofSeconds(5));
    }
    @Test void missingDoneIsAnErrorInsteadOfNormalCompletion() {
        server.enqueue(s -> s.content("partial"));
        ChatGenerationProbe.<String>create((context, out) -> client.streamResponse(messages, context, out)).expectNext("partial")
                .expectError(IllegalStateException.class).verify(Duration.ofSeconds(5));
    }
    @Test void finishReasonWithoutDoneDoesNotAuthorizeCompletion() {
        server.enqueue(s -> s.data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"));
        ChatGenerationProbe.<String>create((context, out) -> client.streamResponse(messages, context, out)).expectError(IllegalStateException.class)
                .verify(Duration.ofSeconds(5));
    }
    @Test void cancelClosesUpstreamHttp() throws Exception {
        server.enqueue(s -> { s.content("first"); s.probeUntilDisconnected(); });
        ChatGenerationProbe.<String>create((context, out) -> client.streamResponse(messages, context, out)).expectNext("first").thenCancel().verify(Duration.ofSeconds(5));
        assertTrue(server.awaitDisconnect(Duration.ofSeconds(1)));
        assertEquals(1, server.disconnects());
    }
    @Test void fragmentedUnicodeAndToolArgumentsAreDecodedInOrder() {
        server.enqueue(s -> {
            s.fragmentedData("{\"choices\":[{\"delta\":{\"content\":\"中文😀\"}}]}");
            s.tool("call-1", "{\"query\":"); s.tool("", "\"文档\"}"); s.data("[DONE]");
        });
        ChatGenerationProbe.<ModelDelta>create((context, out) -> client.streamWithTools(messages, List.of(), context, out))
                .expectNext(new ModelDelta(ModelDelta.Kind.CONTENT, "中文😀"),
                        new ModelDelta(ModelDelta.Kind.TOOL_CALL_ID, "call-1"),
                        new ModelDelta(ModelDelta.Kind.TOOL_CALL_ARGUMENTS, "{\"query\":"),
                        new ModelDelta(ModelDelta.Kind.TOOL_CALL_ARGUMENTS, "\"文档\"}"))
                .expectComplete().verify(Duration.ofSeconds(5));
    }
    @Test void malformedJsonAndProviderErrorsAreStreamErrors() {
        server.enqueue(s -> s.data("not-json"));
        ChatGenerationProbe.<String>create((context, out) -> client.streamResponse(messages, context, out)).expectError().verify(Duration.ofSeconds(5));
        server.enqueue(s -> s.data("{\"error\":{\"message\":\"internal supplier details\"}}"));
        ChatGenerationProbe.<String>create((context, out) -> client.streamResponse(messages, context, out)).expectError().verify(Duration.ofSeconds(5));
    }
    @Test void httpFailureIsStreamError() {
        server.enqueue(503, s -> s.data("{\"error\":\"unavailable\"}"));
        ChatGenerationProbe.<String>create((context, out) -> client.streamResponse(messages, context, out)).expectError().verify(Duration.ofSeconds(5));
    }
}
