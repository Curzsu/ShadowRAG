package com.yizhaoqi.smartpai.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Configuration-only checks: no environment, model key, sockets or paid calls. */
class ChatStreamingBrowserProxyTest {
    @Test
    void missingOptInKeepsTheTransportDirect() {
        assertThat(ChatStreamingBrowserApplication.liveHttpClient(null).configuration().hasProxy()).isFalse();
        assertThat(ChatStreamingBrowserApplication.liveHttpClient("  ").configuration().hasProxy()).isFalse();
    }

    @Test
    void explicitHttpProxyConfiguresConnectToTheRequestedHostAndPort() {
        HttpClient client = ChatStreamingBrowserApplication.liveHttpClient("http://127.0.0.1:7890");
        assertThat(client.configuration().hasProxy()).isTrue();
        ProxyProvider provider = client.configuration().proxyProvider();
        if (provider == null) provider = client.configuration().proxyProviderSupplier().get();
        assertThat(provider.getType()).isEqualTo(ProxyProvider.Proxy.HTTP);
        InetSocketAddress address = (InetSocketAddress) provider.getProxyAddress();
        assertThat(address.getHostString()).isEqualTo("127.0.0.1");
        assertThat(address.getPort()).isEqualTo(7890);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://127.0.0.1:7890", "socks5://127.0.0.1:7890", "http://user:password@127.0.0.1:7890",
            "http://127.0.0.1", "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://127.0.0.1:7890/private",
            "http://127.0.0.1:7890?secret=value", "http://127.0.0.1:7890#secret", "not a uri"})
    void malformedOrCredentialBearingProxyFailsWithASanitizedMessage(String configuredProxy) {
        assertThatIllegalArgumentException().isThrownBy(() -> ChatStreamingBrowserApplication.liveHttpClient(configuredProxy))
                .withMessage("CHAT_BROWSER_HTTPS_PROXY must be an unauthenticated HTTP proxy with an explicit valid port")
                .withNoCause();
    }
}
