package com.yizhaoqi.smartpai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import com.yizhaoqi.smartpai.config.ChatStreamingProperties;
import com.yizhaoqi.smartpai.model.chat.ChatCommand;
import com.yizhaoqi.smartpai.model.chat.ChatOutput;
import com.yizhaoqi.smartpai.service.*;
import com.yizhaoqi.smartpai.service.chat.ChatRequestRegistry;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.yizhaoqi.smartpai.service.chat.ChatRequestContext;
import java.util.function.Consumer;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Local supplier and external storage doubles around the real MVC/security/RAG/SSE chain. */
@TestComponent
@Configuration(proxyBeanMethods = false)
@Import(ChatStreamingTestApplication.class)
public class ChatStreamingAcceptanceFixture {
    public static final String SECRET = "dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdG9rZW4tZ2VuZXJhdGlvbi1hbmQtdmVyaWZpY2F0aW9u";

    public static final class AdjustableClock extends Clock {
        private final AtomicLong now = new AtomicLong(System.currentTimeMillis());
        public void advance(long millis) { now.addAndGet(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        @Override public long millis() { return now.get(); }
    }

    public static final class State {
        public final Map<String, List<String>> persisted = new ConcurrentHashMap<>();
        public final AtomicInteger subscriptions = new AtomicInteger();
        public final AtomicInteger peakSubscriptions = new AtomicInteger();
        public volatile long toolGapMillis;
    }

    @Bean State acceptanceState() { return new State(); }
    @Bean AdjustableClock acceptanceClock() { return new AdjustableClock(); }
    @Bean @Primary ChatRequestRegistry acceptanceRegistry(ChatStreamingProperties properties, AdjustableClock clock) {
        return new ChatRequestRegistry(properties, clock);
    }
    @Bean(destroyMethod = "close") MockModelSseServer acceptanceModel() throws Exception {
        MockModelSseServer server = new MockModelSseServer();
        ObjectMapper mapper = new ObjectMapper();
        server.fallback(session -> {
            var request = mapper.readTree(session.requestBody());
            String marker = "";
            boolean secondRound = false;
            for (var message : request.path("messages")) {
                if ("user".equals(message.path("role").asText())) marker = message.path("content").asText();
                if ("tool".equals(message.path("role").asText())) secondRound = true;
            }
            if (marker.startsWith("gap:") && !secondRound) {
                session.tool("acceptance-search", "{\"query\":\"local acceptance documents\"}");
            } else {
                int count = marker.startsWith("load:") ? 200 : 1;
                for (int i = 0; i < count; i++) {
                    session.content(marker + "/" + i + ";");
                    if (count > 1) Thread.sleep(20);
                }
            }
            session.data("[DONE]");
        });
        return server;
    }
    @Bean @Primary ChatHandler acceptanceHandler(MockModelSseServer model, State state,
                                                 ConversationService conversations) {
        when(conversations.loadHistoryForChat(anyString(), anyString())).thenReturn(List.of());
        ConversationMessageService messages = mock(ConversationMessageService.class);
        when(messages.appendTurn(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            String conversation = call.getArgument(0), answer = call.getArgument(2);
            state.persisted.computeIfAbsent(conversation, ignored -> new CopyOnWriteArrayList<>()).add(answer);
            return List.of(Map.of("role", "user", "content", call.getArgument(1)),
                    Map.of("role", "assistant", "content", answer));
        });
        HybridSearchService search = mock(HybridSearchService.class);
        when(search.searchWithPermission(anyString(), anyString(), eq(10))).thenAnswer(call -> {
            Thread.sleep(state.toolGapMillis);
            return List.of();
        });
        ObjectMapper mapper = new ObjectMapper();
        AiProperties ai = new AiProperties();
        TokenEstimator estimator = new TokenEstimator(mapper);
        return new ChatHandler(mock(StringRedisTemplate.class), search,
                new DeepSeekClient(model.url(), "dedicated-acceptance-key", "local-stub", ai, mapper),
                mapper, ai, mock(ConversationCompressionService.class), messages,
                new ContextBudgetService(ai, estimator), estimator, conversations) {
            @Override public void generateReply(ChatCommand command, ChatRequestContext context, Consumer<ChatOutput> output) {
                state.peakSubscriptions.accumulateAndGet(state.subscriptions.incrementAndGet(), Math::max);
                try { super.generateReply(command, context, output); }
                finally { state.subscriptions.decrementAndGet(); }
            }
        };
    }
}
