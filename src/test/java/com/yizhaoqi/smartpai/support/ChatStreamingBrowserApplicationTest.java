package com.yizhaoqi.smartpai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts the actual browser fixture, catches inherited mock injection, and never enables paid calls. */
@SpringBootTest(classes = {ChatStreamingBrowserApplication.class, ChatStreamingBrowserApplicationTest.LocalOnly.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=optional:classpath:/chat-browser-fixture-absent.yml",
        "server.address=127.0.0.1",
        "jwt.secret-key=dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdG9rZW4tZ2VuZXJhdGlvbi1hbmQtdmVyaWZpY2F0aW9u",
        "logging.level.root=WARN", "logging.level.com.yizhaoqi.smartpai=WARN",
        "logging.level.com.yizhaoqi.smartpai.utils.JwtUtils=OFF",
        "logging.level.com.yizhaoqi.smartpai.business=OFF", "logging.level.com.yizhaoqi.smartpai.performance=OFF"})
class ChatStreamingBrowserApplicationTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired ApplicationContext context;
    @Autowired ChatStreamingBrowserApplication.BrowserState state;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TestConfiguration(proxyBeanMethods = false)
    static class LocalOnly {
        @Bean @Primary ChatStreamingBrowserApplication.BrowserState localOnlyBrowserState() {
            return new ChatStreamingBrowserApplication.BrowserState(false);
        }
    }

    @Test @Timeout(30)
    void startsWithNoExternalInfrastructureAndPersistsOneOwnedNormalStream() throws Exception {
        assertThat(state.live).isFalse();
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBean(ChatStreamingBrowserApplication.class).getClass().getProtectionDomain()
                .getCodeSource().getLocation().toString()).contains("test-classes");
        assertThat(send("GET", "/static/test.html", null, null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/browser-fixture/stats", null, null).statusCode()).isEqualTo(401);

        String token = login(ChatStreamingBrowserApplication.USERNAME);
        String otherToken = login(ChatStreamingBrowserApplication.OTHER_USERNAME);
        String conversationId = mapper.readTree(send("POST", "/api/v1/chat/conversation/new", token, Map.of()).body())
                .path("data").path("conversationId").asText();
        HttpResponse<String> stream = send("POST", "/api/v1/chat/stream", token, Map.of(
                "conversationId", conversationId, "requestId", UUID.randomUUID().toString(), "message", "浏览器夹具启动检查"));
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.headers().firstValue("content-type").orElse("")).contains("text/event-stream");
        assertThat(stream.body()).contains("event:meta", "event:chunk", "event:completion", "\"status\":\"finished\"");
        assertThat(stream.body().split("event:chunk", -1)).hasSize(19);
        assertThat(stream.body().split("event:completion", -1)).hasSize(2);
        JsonNode history = mapper.readTree(send("POST", "/api/v1/chat/conversation/" + conversationId + "/switch", token, Map.of()).body())
                .path("data").path("messages");
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("role").asText()).isEqualTo("user");
        assertThat(history.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(history.get(1).path("content").asText()).isEqualTo("你好，" + "这是流式联调响应。".repeat(17));
        assertThat(mapper.readTree(send("GET", "/api/v1/chat/conversation/list", token, null).body()).path("data").size()).isEqualTo(1);
        assertThat(mapper.readTree(send("GET", "/api/v1/chat/conversation/list", otherToken, null).body()).path("data").size()).isZero();
        assertThat(send("POST", "/api/v1/chat/stream", otherToken, Map.of("conversationId", conversationId,
                "requestId", UUID.randomUUID().toString(), "message", "其他用户不可读取该会话")).statusCode()).isEqualTo(403);
        assertThat(state.persistedTurns.get()).isEqualTo(1);
        assertThat(state.supplierRequests.get()).isEqualTo(1);
        send("POST", "/api/v1/users/logout", token, Map.of());
        assertThat(send("GET", "/api/v1/users/me", token, null).statusCode()).isEqualTo(401);
    }

    private String login(String username) throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/users/login", null,
                Map.of("username", username, "password", ChatStreamingBrowserApplication.PASSWORD));
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data").path("token").asText();
    }

    private HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
