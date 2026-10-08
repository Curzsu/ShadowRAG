package com.yizhaoqi.smartpai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.*;
import com.yizhaoqi.smartpai.controller.ChatController;
import com.yizhaoqi.smartpai.controller.ChatStreamingExceptionHandler;
import com.yizhaoqi.smartpai.controller.ConversationController;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.model.Conversation;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.ConversationRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.service.*;
import com.yizhaoqi.smartpai.service.chat.ChatRequestRegistry;
import com.yizhaoqi.smartpai.service.chat.ChatStreamService;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.dao.PersistenceExceptionTranslationAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Explicitly launched browser acceptance fixture. Never component-scans the production app,
 * never enters the production jar, and defaults to a loopback supplier with no paid calls.
 * Set CHAT_BROWSER_LIVE_GEMINI=true and inject GEMINI_API_KEY to opt into the live supplier.
 */
@TestComponent
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class, RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class,
        PersistenceExceptionTranslationAutoConfiguration.class, KafkaAutoConfiguration.class}, excludeName = {
        "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration",
        "org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration",
        "org.springframework.boot.autoconfigure.elasticsearch.ReactiveElasticsearchClientAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration"})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, OrgTagAuthorizationFilter.class, JwtUtils.class,
        ChatController.class, ConversationController.class, ChatStreamingExceptionHandler.class,
        ChatStreamService.class, ChatStreamingConfig.class, ChatStreamingProperties.class, ChatRequestRegistry.class,
        WebConfig.class, LoggingInterceptor.class, LangfuseConfiguration.class,
        ChatStreamingBrowserApplication.BrowserFixtureController.class})
public class ChatStreamingBrowserApplication {
    public static final String USERNAME = "fixture-browser";
    public static final String OTHER_USERNAME = "fixture-other";
    public static final String PASSWORD = "BrowserTest123";

    public static void main(String[] args) {
        byte[] signingKey = new byte[64];
        new SecureRandom().nextBytes(signingKey);
        Map<String, Object> properties = new HashMap<>();
        // Deliberately skip application*.yml: no real infrastructure credentials are needed here.
        properties.put("spring.config.location", "optional:classpath:/chat-browser-fixture-absent.yml");
        properties.put("spring.main.web-application-type", "servlet");
        properties.put("spring.main.banner-mode", "off");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", env("CHAT_BROWSER_PORT", "18083"));
        properties.put("jwt.secret-key", Base64.getEncoder().encodeToString(signingKey));
        properties.put("logging.level.root", "WARN");
        properties.put("logging.level.com.yizhaoqi.smartpai", "WARN");
        properties.put("logging.level.com.yizhaoqi.smartpai.business", "OFF");
        properties.put("logging.level.com.yizhaoqi.smartpai.performance", "OFF");
        properties.put("logging.level.com.yizhaoqi.smartpai.utils.JwtUtils", "OFF");
        properties.put("ai.generation.max-tokens", "1024");
        properties.put("chat.streaming.heartbeat-interval-ms", env("CHAT_BROWSER_HEARTBEAT_MS", "1000"));
        properties.put("chat.streaming.generation-timeout-ms", env("CHAT_BROWSER_TIMEOUT_MS", "120000"));
        properties.put("chat.streaming.emitter-timeout-ms", env("CHAT_BROWSER_EMITTER_TIMEOUT_MS", "140000"));
        properties.put("chat.streaming.terminal-retention-ms", "300000");
        properties.put("chat.streaming.worker-threads", "8");
        properties.put("chat.streaming.worker-queue-capacity", "64");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("isolatedBrowserFixture", properties));
        SpringApplication application = new SpringApplication(ChatStreamingBrowserApplication.class);
        application.setEnvironment(environment);
        application.setAddCommandLineProperties(false);
        application.run();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Bean BrowserState browserState() { return new BrowserState(); }

    @Bean UserRepository userRepository(BrowserState state) {
        UserRepository repository = mock(UserRepository.class);
        when(repository.findByUsername(anyString())).thenAnswer(call -> Optional.ofNullable(state.users.get(call.getArgument(0))));
        return repository;
    }

    @Bean CustomUserDetailsService customUserDetailsService(BrowserState state) {
        CustomUserDetailsService service = mock(CustomUserDetailsService.class);
        when(service.loadUserByUsername(anyString())).thenAnswer(call -> {
            String username = call.getArgument(0);
            if (!state.users.containsKey(username)) throw new UsernameNotFoundException("Unknown fixture user");
            return org.springframework.security.core.userdetails.User.withUsername(username)
                    .password("unused-fixture-hash").roles("USER").build();
        });
        return service;
    }

    @Bean TokenCacheService tokenCacheService(BrowserState state) {
        TokenCacheService cache = mock(TokenCacheService.class);
        doAnswer(call -> { state.tokens.put(call.getArgument(0), call.getArgument(3)); return null; })
                .when(cache).cacheToken(anyString(), anyString(), anyString(), anyLong());
        doAnswer(call -> { state.refreshTokens.put(call.getArgument(0), call.getArgument(3)); return null; })
                .when(cache).cacheRefreshToken(anyString(), anyString(), nullable(String.class), anyLong());
        when(cache.isTokenValid(anyString())).thenAnswer(call -> state.tokens.getOrDefault(call.getArgument(0), 0L) > System.currentTimeMillis());
        when(cache.isRefreshTokenValid(anyString())).thenAnswer(call -> state.refreshTokens.getOrDefault(call.getArgument(0), 0L) > System.currentTimeMillis());
        doAnswer(call -> { state.tokens.remove(call.getArgument(0)); return null; }).when(cache).blacklistToken(anyString(), anyLong());
        doAnswer(call -> { state.tokens.remove(call.getArgument(0)); return null; }).when(cache).removeToken(anyString(), anyString());
        return cache;
    }

    @Bean FileUploadRepository fileUploadRepository() { return mock(FileUploadRepository.class); }

    @Bean @SuppressWarnings("unchecked")
    RedisTemplate<String, Object> redisTemplate() { return mock(RedisTemplate.class); }

    @Bean ConversationRepository conversationRepository(BrowserState state) {
        ConversationRepository repository = mock(ConversationRepository.class);
        when(repository.save(any(Conversation.class))).thenAnswer(call -> {
            Conversation conversation = call.getArgument(0);
            synchronized (state) {
                if (conversation.getId() == null) conversation.setId(state.identifiers.incrementAndGet());
                if (conversation.getCreatedAt() == null) conversation.setCreatedAt(LocalDateTime.now());
                if (conversation.getUpdatedAt() == null) conversation.setUpdatedAt(conversation.getCreatedAt());
                state.conversations.put(conversation.getConversationId(), conversation);
            }
            return conversation;
        });
        when(repository.findByConversationId(anyString())).thenAnswer(call -> Optional.ofNullable(state.conversations.get(call.getArgument(0))));
        when(repository.findByUserIdOrderByUpdatedAtDesc(anyLong())).thenAnswer(call -> state.conversations.values().stream()
                .filter(conversation -> conversation.getUser().getId().equals(call.getArgument(0)))
                .sorted(Comparator.comparing(Conversation::getUpdatedAt).reversed()).toList());
        doAnswer(call -> { state.conversations.remove(call.getArgument(0)); return null; }).when(repository).deleteByConversationId(anyString());
        return repository;
    }

    @Bean @SuppressWarnings("unchecked")
    StringRedisTemplate stringRedisTemplate(BrowserState state) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> state.cache.get(call.getArgument(0)));
        doAnswer(call -> { state.cache.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(call -> state.cache.remove(call.getArgument(0)) != null);
        when(redis.delete(anyCollection())).thenAnswer(call -> {
            Collection<String> keys = call.getArgument(0);
            return keys.stream().filter(key -> state.cache.remove(key) != null).count();
        });
        return redis;
    }

    @Bean FactoryBean<ConversationMessageService> conversationMessageService(BrowserState state) {
        ConversationMessageService messages = mock(ConversationMessageService.class);
        when(messages.appendTurn(anyString(), anyString(), anyString(), any(LocalDateTime.class))).thenAnswer(call -> {
            synchronized (state) {
                String id = call.getArgument(0);
                Conversation conversation = state.conversations.get(id);
                if (conversation == null) throw new CustomException("会话不存在", HttpStatus.NOT_FOUND);
                LocalDateTime timestamp = call.getArgument(3);
                List<Map<String, String>> pair = List.of(
                        state.message("user", call.getArgument(1), timestamp),
                        state.message("assistant", call.getArgument(2), timestamp));
                state.history.computeIfAbsent(id, ignored -> new ArrayList<>()).addAll(pair);
                conversation.setUpdatedAt(timestamp);
                if ("新对话".equals(conversation.getTitle())) conversation.setTitle("浏览器联调会话");
                state.persistedTurns.incrementAndGet();
                return pair;
            }
        });
        when(messages.getRawHistory(anyString())).thenAnswer(call -> state.history(call.getArgument(0)));
        when(messages.getLatestSequenceId(anyString())).thenAnswer(call -> state.latest(call.getArgument(0), Long.MAX_VALUE));
        when(messages.getLatestSequenceIdBefore(anyString(), anyLong())).thenAnswer(call -> state.latest(call.getArgument(0), call.getArgument(1)));
        doAnswer(call -> { synchronized (state) { state.history.remove(call.getArgument(0)); } return null; })
                .when(messages).deleteRawHistory(anyString());
        return new FixtureDoubleFactory<>(ConversationMessageService.class, messages);
    }

    @Bean ConversationCompressionService conversationCompressionService(BrowserState state, ObjectMapper mapper) {
        ConversationCompressionService compression = mock(ConversationCompressionService.class);
        when(compression.getWorkingSetVersion(anyString())).thenAnswer(call -> state.versions.getOrDefault(call.getArgument(0), 0L));
        when(compression.replaceWorkingSet(anyString(), anyLong(), anyList())).thenAnswer(call -> {
            synchronized (state) {
                String id = call.getArgument(0);
                long expected = call.getArgument(1);
                if (state.versions.getOrDefault(id, 0L) != expected) return false;
                state.cache.put("conversation:" + id, mapper.writeValueAsString(call.getArgument(2)));
                state.versions.put(id, expected + 1L);
                return true;
            }
        });
        return compression;
    }

    @Bean ConversationService conversationService(ConversationRepository conversations, UserRepository users,
            StringRedisTemplate redis, ObjectMapper mapper, ConversationMessageService messages,
            ConversationCompressionService compression) {
        return new ConversationService(conversations, users, redis, mapper, messages, compression);
    }

    @Bean FactoryBean<HybridSearchService> hybridSearchService(BrowserState state) {
        HybridSearchService search = mock(HybridSearchService.class);
        when(search.searchWithPermission(anyString(), anyString(), eq(10))).thenAnswer(call -> {
            if (!state.users.containsKey(call.getArgument(1))) throw new IllegalArgumentException("Unknown fixture user");
            state.searchCalls.incrementAndGet();
            Thread.sleep(2200);
            return List.of();
        });
        return new FixtureDoubleFactory<>(HybridSearchService.class, search);
    }

    @Bean AiProperties aiProperties() {
        AiProperties properties = new AiProperties();
        properties.getGeneration().setMaxTokens(1024);
        properties.getPrompt().setRules("你是浏览器流式联调助手。用中文简短回答。仅在用户要求检索私有知识库时调用搜索工具。");
        return properties;
    }

    @Bean TokenEstimator tokenEstimator(ObjectMapper mapper) { return new TokenEstimator(mapper); }
    @Bean ContextBudgetService contextBudgetService(AiProperties ai, TokenEstimator estimator) { return new ContextBudgetService(ai, estimator); }

    @Bean(destroyMethod = "close") LocalBrowserSupplier localBrowserSupplier(BrowserState state, ObjectMapper mapper) throws IOException {
        return new LocalBrowserSupplier(state, mapper);
    }

    @Bean DeepSeekClient deepSeekClient(BrowserState state, LocalBrowserSupplier supplier, AiProperties ai, ObjectMapper mapper) {
        if (state.live) {
            String key = System.getenv("GEMINI_API_KEY");
            if (key == null || key.isBlank()) throw new IllegalStateException("Live browser fixture requires GEMINI_API_KEY");
            String apiUrl = env("GEMINI_API_URL", "https://generativelanguage.googleapis.com/v1beta/openai");
            return browserModelClient(apiUrl, key, env("GEMINI_MODEL", "gemini-3.8-flash"), ai, mapper,
                    System.getenv("CHAT_BROWSER_HTTPS_PROXY"));
        }
        return new DeepSeekClient(supplier.url(), "", "loopback-browser-fixture", ai, mapper);
    }

    static DeepSeekClient browserModelClient(String url, String key, String model, AiProperties ai, ObjectMapper mapper, String proxy) {
        return new DeepSeekClient(url, key, model, ai, mapper, liveProxyUrl(proxy));
    }

    static String liveProxyUrl(String configuredProxy) {
        if (configuredProxy == null || configuredProxy.isBlank()) return "";
        String invalidMessage = "CHAT_BROWSER_HTTPS_PROXY must be an unauthenticated HTTP proxy with an explicit valid port";
        URI proxy;
        try {
            proxy = URI.create(configuredProxy);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException(invalidMessage);
        }
        if (!"http".equalsIgnoreCase(proxy.getScheme()) || proxy.getHost() == null
                || proxy.getRawUserInfo() != null || proxy.getPort() < 1 || proxy.getPort() > 65535
                || proxy.getRawQuery() != null || proxy.getRawFragment() != null
                || (proxy.getRawPath() != null && !proxy.getRawPath().isEmpty() && !"/".equals(proxy.getRawPath()))) {
            throw new IllegalArgumentException(invalidMessage);
        }
        return configuredProxy;
    }

    @Bean ChatHandler chatHandler(StringRedisTemplate redis, HybridSearchService search, DeepSeekClient model,
            ObjectMapper mapper, AiProperties ai, ConversationCompressionService compression,
            ConversationMessageService messages, ContextBudgetService budget, TokenEstimator estimator,
            ConversationService conversations) {
        return new ChatHandler(redis, search, model, mapper, ai, compression, messages, budget, estimator,
                conversations);
    }

    /** Finished boundary doubles must not receive inherited @Autowired/@PersistenceContext injections. */
    public static final class FixtureDoubleFactory<T> implements FactoryBean<T> {
        private final Class<T> type;
        private final T value;
        FixtureDoubleFactory(Class<T> type, T value) { this.type = type; this.value = value; }
        @Override public T getObject() { return value; }
        @Override public Class<?> getObjectType() { return type; }
        @Override public boolean isSingleton() { return true; }
    }

    public static final class BrowserState {
        final boolean live;
        final Map<String, User> users = new HashMap<>();
        final Map<String, Long> tokens = new ConcurrentHashMap<>(), refreshTokens = new ConcurrentHashMap<>();
        final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
        final Map<String, List<Map<String, String>>> history = new HashMap<>();
        final Map<String, String> cache = new ConcurrentHashMap<>();
        final Map<String, Long> versions = new ConcurrentHashMap<>();
        final AtomicLong identifiers = new AtomicLong(), sequences = new AtomicLong();
        final AtomicInteger persistedTurns = new AtomicInteger(), searchCalls = new AtomicInteger();
        final AtomicInteger supplierRequests = new AtomicInteger(), supplierActive = new AtomicInteger(), supplierDisconnected = new AtomicInteger();
        volatile String mode = "normal";
        BrowserState() { this(Boolean.parseBoolean(env("CHAT_BROWSER_LIVE_GEMINI", "false"))); }
        BrowserState(boolean live) {
            this.live = live;
            for (String username : List.of(USERNAME, OTHER_USERNAME)) {
                User user = new User();
                user.setId((long) users.size() + 1); user.setUsername(username); user.setRole(User.Role.USER);
                user.setPrimaryOrg("DEFAULT"); user.setOrgTags("DEFAULT");
                users.put(username, user);
            }
        }
        Map<String, String> message(String role, String content, LocalDateTime timestamp) {
            return Map.of("seq", String.valueOf(sequences.incrementAndGet()), "role", role,
                    "content", content, "timestamp", timestamp.toString());
        }
        synchronized List<Map<String, String>> history(String id) { return List.copyOf(history.getOrDefault(id, List.of())); }
        synchronized long latest(String id, long upper) {
            return history(id).stream().mapToLong(message -> Long.parseLong(message.get("seq")))
                    .filter(seq -> seq < upper).max().orElse(0L);
        }
    }

    /** Fixture endpoints are protected by the same production JWT/security chain. */
    @RestController
    public static final class BrowserFixtureController {
        private final BrowserState state;
        private final JwtUtils jwt;
        private final ChatRequestRegistry registry;
        BrowserFixtureController(BrowserState state, JwtUtils jwt, ChatRequestRegistry registry) {
            this.state = state; this.jwt = jwt; this.registry = registry;
        }
        @PostMapping("/api/v1/users/login")
        ResponseEntity<?> login(@RequestBody Map<String, String> credentials) {
            String username = credentials.get("username");
            if (!state.users.containsKey(username) || !PASSWORD.equals(credentials.get("password")))
                return ResponseEntity.status(401).body(Map.of("code", 401, "message", "Invalid fixture credentials"));
            return ok(Map.of("token", jwt.generateToken(username), "refreshToken", jwt.generateRefreshToken(username)));
        }
        @GetMapping("/api/v1/users/me")
        ResponseEntity<?> me(Principal principal) {
            User user = state.users.get(principal.getName());
            return ok(Map.of("id", user.getId(), "username", user.getUsername(), "role", "USER",
                    "orgTags", List.of("DEFAULT"), "primaryOrg", "DEFAULT"));
        }
        @PostMapping("/api/v1/users/logout")
        ResponseEntity<?> logout(@RequestHeader("Authorization") String authorization) {
            jwt.invalidateToken(authorization.substring(7));
            return ok(Map.of());
        }
        @GetMapping("/api/v1/browser-fixture/stats")
        ResponseEntity<?> stats() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("supplier", state.live ? "live-gemini" : "loopback"); data.put("mode", state.mode);
            data.put("activeRequests", registry.activeRequestCount()); data.put("conversationLeases", registry.conversationLeaseCount());
            data.put("retainedRequests", registry.retainedRequestCount()); data.put("persistedTurns", state.persistedTurns.get());
            data.put("searchCalls", state.searchCalls.get()); data.put("supplierRequests", state.supplierRequests.get());
            data.put("supplierActive", state.supplierActive.get()); data.put("supplierDisconnected", state.supplierDisconnected.get());
            data.put("active", registry.activeSnapshot().stream().map(context -> Map.of(
                    "requestId", context.command().requestId(), "status", context.state().name())).toList());
            synchronized (state) {
                data.put("conversations", state.conversations.values().stream().map(conversation -> Map.of(
                        "conversationId", conversation.getConversationId(),
                        "messages", state.history(conversation.getConversationId()).size())).toList());
            }
            return ok(data);
        }
        @PostMapping("/api/v1/browser-fixture/mode")
        ResponseEntity<?> mode(@RequestBody Map<String, String> request) {
            if (state.live) return ResponseEntity.status(409).body(Map.of("code", 409, "message", "Live supplier mode is fixed"));
            String mode = request.get("mode");
            if (mode == null || !Set.of("normal", "long", "tool", "error", "empty").contains(mode))
                return ResponseEntity.badRequest().body(Map.of("code", 400, "message", "Unknown fixture mode"));
            state.mode = mode;
            return ok(Map.of("mode", mode));
        }
        private static ResponseEntity<?> ok(Object data) { return ResponseEntity.ok(Map.of("code", 200, "message", "OK", "data", data)); }
    }

    /** Independent loopback stub for interactive UI checks; not used in live mode. */
    public static final class LocalBrowserSupplier implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final BrowserState state;
        private final ObjectMapper mapper;
        LocalBrowserSupplier(BrowserState state, ObjectMapper mapper) throws IOException {
            this.state = state; this.mapper = mapper;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(task -> { Thread thread = new Thread(task, "browser-loopback-supplier"); thread.setDaemon(true); return thread; });
            server.setExecutor(executor); server.createContext("/chat/completions", this::respond); server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        private void respond(HttpExchange exchange) throws IOException {
            state.supplierRequests.incrementAndGet(); state.supplierActive.incrementAndGet();
            try {
                JsonNode request = mapper.readTree(exchange.getRequestBody());
                String mode = state.mode;
                if ("error".equals(mode)) { exchange.sendResponseHeaders(503, -1); return; }
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
                exchange.sendResponseHeaders(200, 0);
                boolean toolReply = false;
                for (JsonNode message : request.path("messages")) if ("tool".equals(message.path("role").asText())) toolReply = true;
                if ("tool".equals(mode) && !toolReply) {
                    frame(exchange, mapper.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of("tool_calls", List.of(Map.of(
                            "index", 0, "id", "browser-fixture-search", "type", "function", "function", Map.of(
                                    "name", "search_knowledge_base", "arguments", "{\"query\":\"fixture\"}")))))))));
                } else if (!"empty".equals(mode)) {
                    int chunks = "long".equals(mode) ? 200 : 18;
                    for (int i = 0; i < chunks; i++) {
                        pause("long".equals(mode) ? 250 : 90);
                        frame(exchange, mapper.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of(
                                "content", i == 0 ? "你好，" : "这是流式联调响应。"))))));
                    }
                }
                frame(exchange, "[DONE]");
            } catch (IOException disconnected) {
                state.supplierDisconnected.incrementAndGet();
            } finally {
                exchange.close();
                state.supplierActive.decrementAndGet();
            }
        }
        private static void frame(HttpExchange exchange, String payload) throws IOException {
            exchange.getResponseBody().write(("data: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        }
        private static void pause(long millis) throws IOException {
            try { Thread.sleep(millis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Fixture stopped"); }
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
