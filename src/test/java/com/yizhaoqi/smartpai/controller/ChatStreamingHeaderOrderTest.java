package com.yizhaoqi.smartpai.controller;
import com.yizhaoqi.smartpai.support.GenerationScript;

import com.yizhaoqi.smartpai.service.ChatHandler;
import com.yizhaoqi.smartpai.support.ChatStreamingTestApplication;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriter;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Reproduces Servlet/security headers racing the first asynchronous SSE write. */
@SpringBootTest(classes = ChatStreamingTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jwt.secret-key=dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdG9rZW4tZ2VuZXJhdGlvbi1hbmQtdmVyaWZpY2F0aW9u"})
@Import(ChatStreamingHeaderOrderTest.DelayedServletReturn.class)
class ChatStreamingHeaderOrderTest {
    @LocalServerPort int port;
    @Autowired SecurityFilterChain security;
    @Autowired ChatHandler handler;
    @Autowired JwtUtils jwt;

    @Test @SuppressWarnings("unchecked")
    void securityHeadersAreWrittenOnServletThreadBeforeSseWorkerCanFlush() throws Exception {
        HeaderWriterFilter filter = security.getFilters().stream().filter(HeaderWriterFilter.class::isInstance)
                .map(HeaderWriterFilter.class::cast).findFirst().orElseThrow();
        List<HeaderWriter> writers = (List<HeaderWriter>) ReflectionTestUtils.getField(filter, "headerWriters");
        assertNotNull(writers);
        List<String> headerThreads = new CopyOnWriteArrayList<>();
        HeaderWriter observe = (request, response) -> {
            headerThreads.add(Thread.currentThread().getName());
            response.setHeader("X-Header-Order-Probe", "written");
        };
        writers.add(observe);
        try {
            GenerationScript.stubAny(handler, GenerationScript.empty());
            String body = "{\"conversationId\":\"" + UUID.randomUUID() + "\",\"requestId\":\""
                    + UUID.randomUUID() + "\",\"message\":\"header-order-probe\"}";
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/chat/stream"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + jwt.generateToken("header-order-user"))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"status\":\"finished\""));
            assertEquals("written", response.headers().firstValue("X-Header-Order-Probe").orElseThrow());
            assertFalse(headerThreads.isEmpty());
            assertTrue(headerThreads.stream().noneMatch(name -> name.startsWith("chat-stream-worker-")),
                    "Security headers must precede asynchronous writes, observed " + headerThreads);
        } finally {
            writers.remove(observe);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DelayedServletReturn {
        @Bean WebMvcConfigurer delayedInitialDispatchReturn() {
            return new WebMvcConfigurer() {
                @Override public void addInterceptors(InterceptorRegistry registry) {
                    // Async callbacks run in reverse order: let production signal readiness,
                    // then hold the Servlet thread so its deferred headers cannot win by luck.
                    registry.addInterceptor(new AsyncHandlerInterceptor() {
                        @Override public void afterConcurrentHandlingStarted(HttpServletRequest request,
                                HttpServletResponse response, Object handler) throws Exception {
                            Thread.sleep(250);
                        }
                    }).addPathPatterns("/api/v1/chat/stream").order(Ordered.HIGHEST_PRECEDENCE);
                }
            };
        }
    }
}
