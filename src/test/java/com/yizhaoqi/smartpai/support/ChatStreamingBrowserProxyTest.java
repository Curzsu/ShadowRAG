package com.yizhaoqi.smartpai.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.service.chat.ChatRequestContext;
import com.yizhaoqi.smartpai.model.chat.ChatCommand;
import java.util.*;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Configuration-only checks: no environment, model key, sockets or paid calls. */
class ChatStreamingBrowserProxyTest {
    @Test void browserClientUsesTheOrdinaryTransportForLoopbackStreams() throws Exception {
        try (var server = new MockModelSseServer()) {
            server.enqueue(session -> { session.content("browser answer"); session.data("[DONE]"); });
            var client = ChatStreamingBrowserApplication.browserModelClient(server.url(), "", "test",
                    new AiProperties(), new ObjectMapper(), "");
            var output = new ArrayList<String>();
            client.streamResponse(List.of(Map.of("role","user","content","test")),
                    new ChatRequestContext(new ChatCommand("test","test",UUID.randomUUID(),"test")), output::add);
            assertThat(output).containsExactly("browser answer");
            assertThat(server.requests()).isEqualTo(1);
        }
    }
    @Test
    void missingOptInKeepsTheTransportDirect() {
        assertThat(ChatStreamingBrowserApplication.liveProxyUrl(null)).isEmpty();
        assertThat(ChatStreamingBrowserApplication.liveProxyUrl("  ")).isEmpty();
    }

    @Test
    void explicitHttpProxyConfiguresConnectToTheRequestedHostAndPort() {
        var proxy = java.net.URI.create(ChatStreamingBrowserApplication.liveProxyUrl("http://127.0.0.1:7890"));
        assertThat(proxy.getHost()).isEqualTo("127.0.0.1");
        assertThat(proxy.getPort()).isEqualTo(7890);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://127.0.0.1:7890", "socks5://127.0.0.1:7890", "http://user:password@127.0.0.1:7890",
            "http://127.0.0.1", "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://127.0.0.1:7890/private",
            "http://127.0.0.1:7890?secret=value", "http://127.0.0.1:7890#secret", "not a uri"})
    void malformedOrCredentialBearingProxyFailsWithASanitizedMessage(String configuredProxy) {
        assertThatIllegalArgumentException().isThrownBy(() -> ChatStreamingBrowserApplication.liveProxyUrl(configuredProxy))
                .withMessage("CHAT_BROWSER_HTTPS_PROXY must be an unauthenticated HTTP proxy with an explicit valid port")
                .withNoCause();
    }
}
