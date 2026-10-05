package com.yizhaoqi.smartpai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.model.chat.ChatOutput;
import com.yizhaoqi.smartpai.service.*;
import com.yizhaoqi.smartpai.service.chat.ChatRequestRegistry;
import com.yizhaoqi.smartpai.support.ChatStreamingTestApplication;
import com.yizhaoqi.smartpai.support.MockModelSseServer;
import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.AiProperties;
import java.time.Duration;
import com.yizhaoqi.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import reactor.core.publisher.Flux;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes=ChatStreamingTestApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "jwt.secret-key=dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdG9rZW4tZ2VuZXJhdGlvbi1hbmQtdmVyaWZpY2F0aW9u", "chat.streaming.heartbeat-interval-ms=50"})
class ChatStreamingHttpTest {
    @LocalServerPort int port;
    @Autowired ChatHandler handler;
    @Autowired TokenCacheService cache;
    @Autowired JwtUtils jwt;
    @Autowired ChatRequestRegistry registry;
    final HttpClient client=HttpClient.newHttpClient();
    @BeforeEach void setup() { reset(handler,cache); when(cache.isTokenValid(anyString())).thenReturn(true); }
    HttpRequest request(String token) {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/chat/stream")).header("Content-Type","application/json");
        if(token!=null) builder.header("Authorization","Bearer "+token);
        return builder.POST(HttpRequest.BodyPublishers.ofString("{\"conversationId\":\""+UUID.randomUUID()+"\",\"requestId\":\""+UUID.randomUUID()+"\",\"message\":\"dedicated test\"}")).build();
    }
    @Test void removedChatWebsocketCannotHandshakeOrIssueCommandTokenOverRealHttp() throws Exception {
        String token = jwt.generateToken("alice");
        when(handler.generateReply(any())).thenReturn(Flux.empty());
        assertEquals(200, client.send(request(token), HttpResponse.BodyHandlers.ofString()).statusCode(),
                "The dedicated token must authenticate a current stream before probing removed routes");
        clearInvocations(handler);
        var legacyToken = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/chat/websocket-token"))
                .header("Authorization", "Bearer " + token).GET().build();
        var tokenResponse = client.send(legacyToken, HttpResponse.BodyHandlers.ofString());
        assertTrue(Set.of(401, 404).contains(tokenResponse.statusCode()), "Removed route must not issue a command token");
        try {
            var socket = client.newWebSocketBuilder().header("Authorization", "Bearer " + token).buildAsync(
                    URI.create("ws://127.0.0.1:" + port + "/chat/dedicated-removed-route"), new java.net.http.WebSocket.Listener() { })
                    .get(5, TimeUnit.SECONDS);
            socket.abort();
            fail("Removed chat route must never complete a WebSocket handshake");
        } catch (ExecutionException error) {
            assertInstanceOf(java.net.http.WebSocketHandshakeException.class, error.getCause());
            assertTrue(Set.of(401, 404).contains(((java.net.http.WebSocketHandshakeException) error.getCause()).getResponse().statusCode()),
                    "Removed route must reject the handshake even with a working Authorization token");
        }
        verifyNoInteractions(handler);
    }
    @Test void unauthenticatedAndForgedAndRevokedHave401Json() throws Exception {
        String issued=jwt.generateToken("alice"); when(cache.isTokenValid(anyString())).thenReturn(false);
        for(String token:Arrays.asList(null,"dedicated.invalid.token",issued)) {
            var response=client.send(request(token),HttpResponse.BodyHandlers.ofString()); assertEquals(401,response.statusCode());
            assertEquals("UNAUTHENTICATED",new ObjectMapper().readTree(response.body()).path("data").path("errorCode").asText());
        }
        verifyNoInteractions(handler);
    }
    @Test void realAsyncCompletionRetainsSseAndDoesNotBecome401() throws Exception {
        when(handler.generateReply(any())).thenReturn(Flux.just(new ChatOutput("chunk",Map.of("chunk","中文😀"))));
        var response=client.send(request(jwt.generateToken("alice")),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200,response.statusCode()); assertTrue(response.headers().firstValue("content-type").orElse("").contains("text/event-stream"));
        List<com.fasterxml.jackson.databind.JsonNode> events = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        for (String line : response.body().lines().toList()) {
            if (line.startsWith("data:")) events.add(mapper.readTree(line.substring(5)));
        }
        assertEquals(List.of("meta", "chunk", "completion"), events.stream().map(event -> event.path("type").asText()).toList());
        assertEquals("中文😀", events.get(1).path("data").path("chunk").asText(), response.body());
        assertEquals("finished", events.get(2).path("data").path("status").asText());
        for (int i = 0; i < events.size(); i++) assertEquals(i + 1, events.get(i).path("seq").asInt());
        assertFalse(response.body().contains("UNAUTHENTICATED"));
        verify(handler,times(1)).persistCompletedTurn(any(),eq("中文😀"));
    }
    @Test void postCommitErrorStaysSse() throws Exception {
        when(handler.generateReply(any())).thenReturn(Flux.error(new IllegalStateException("private model credentials")));
        var response=client.send(request(jwt.generateToken("alice")),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode()); assertTrue(response.body().contains("event:error")); assertTrue(response.body().contains("\"status\":\"failed\"")); assertFalse(response.body().contains("credentials"));
    }
    @Test void realHttpDisconnectCancelsModelSocket() throws Exception {
        try(MockModelSseServer model=new MockModelSseServer()) {
            model.enqueue(session -> { session.content("partial"); session.probeUntilDisconnected(); });
            var modelClient=new DeepSeekClient(model.url(),"dedicated-test-key","test-model",new AiProperties(),new ObjectMapper());
            CountDownLatch cancelled=new CountDownLatch(1);
            var cancelledAt = new java.util.concurrent.atomic.AtomicLong();
            when(handler.generateReply(any())).thenAnswer(invocation -> modelClient.streamResponse(List.of(Map.of("role","user","content","dedicated test")))
                .map(value -> new ChatOutput("chunk",Map.of("chunk",value))).doOnCancel(() -> {
                    cancelledAt.compareAndSet(0, System.nanoTime()); cancelled.countDown();
                }));
            var response=client.send(request(jwt.generateToken("alice")),HttpResponse.BodyHandlers.ofInputStream()); assertEquals(200,response.statusCode());
            var input=response.body(); StringBuilder received=new StringBuilder();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!received.toString().contains("partial") && System.nanoTime()<deadline) { int next=input.read(); assertNotEquals(-1,next); received.append((char)next); }
            assertTrue(received.toString().contains("partial")); long disconnectedAt = System.nanoTime(); input.close();
            assertTrue(cancelled.await(3,TimeUnit.SECONDS),"Downstream socket closure must dispose model subscription");
            assertTrue(model.awaitDisconnect(Duration.ofSeconds(2)),"Mock upstream must observe closed HTTP connection");
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3); while(registry.activeRequestCount()!=0 && System.nanoTime()<until) Thread.sleep(10);
            assertEquals(0,registry.activeRequestCount()); verify(handler,never()).persistCompletedTurn(any(),anyString());
            assertEquals(1, model.requests());
            var metricsDirectory = java.nio.file.Path.of(System.getProperty("chat.acceptance.metrics-dir",
                    ".superpowers/sdd/2026-10-03-websocket-to-sse-refactor"));
            java.nio.file.Files.createDirectories(metricsDirectory);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(metricsDirectory.resolve("task-7-disconnect-metrics.json").toFile(),
                    Map.of("heartbeatIntervalMillis", 50, "upstreamProbeIntervalMillis", 10,
                            "subscriptionCancelAfterClientCloseMillis", TimeUnit.NANOSECONDS.toMillis(cancelledAt.get() - disconnectedAt),
                            "modelSocketDisconnectAfterClientCloseMillis", TimeUnit.NANOSECONDS.toMillis(model.firstDisconnectNanos() - disconnectedAt),
                            "activeRequestsAfterDisconnect", registry.activeRequestCount(), "persistedTurns", 0,
                            "recordedAt", java.time.Instant.now().toString()));
        }
    }    @Test void legitimateGraceRefreshPreservesNewTokenHeader() throws Exception {
        String secret="dGVzdC1zZWNyZXQta2V5LWZvci1qd3QtdG9rZW4tZ2VuZXJhdGlvbi1hbmQtdmVyaWZpY2F0aW9u";
        String expired=io.jsonwebtoken.Jwts.builder().setSubject("alice").claim("tokenId","dedicated-grace-test")
            .setExpiration(new Date(System.currentTimeMillis()-60000)).signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret)),io.jsonwebtoken.SignatureAlgorithm.HS256).compact();
        when(handler.generateReply(any())).thenReturn(Flux.empty());
        var response=client.send(request(expired),HttpResponse.BodyHandlers.ofString()); assertEquals(200,response.statusCode());
        String fresh=response.headers().firstValue("New-Token").orElseThrow(); assertTrue(jwt.validateToken(fresh));
    }    @Test void nonCanonicalRequestUuidRepresentationRejected() throws Exception {
        String encoded=Base64.getEncoder().encodeToString(new byte[16]);
        String body="{\"conversationId\":\""+UUID.randomUUID()+"\",\"requestId\":\""+encoded+"\",\"message\":\"test\"}";
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/chat/stream"))
                .header("Content-Type","application/json").header("Authorization","Bearer "+jwt.generateToken("alice"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(400,response.statusCode());
        assertEquals("INVALID_REQUEST",new ObjectMapper().readTree(response.body()).path("data").path("errorCode").asText());
        verifyNoInteractions(handler);
    }    @Test void foreignPrincipalCancelCannotStopOwnersHttpStream() throws Exception {
        String requestId=UUID.randomUUID().toString(), conversationId=UUID.randomUUID().toString();
        reactor.core.publisher.Sinks.Many<ChatOutput> sink=reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();
        CountDownLatch subscribed=new CountDownLatch(1);
        when(handler.generateReply(any())).thenReturn(sink.asFlux().doOnSubscribe(subscription -> subscribed.countDown()));
        String body="{\"conversationId\":\""+conversationId+"\",\"requestId\":\""+requestId+"\",\"message\":\"dedicated test\"}";
        var aliceRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/chat/stream"))
                .header("Content-Type","application/json").header("Authorization","Bearer "+jwt.generateToken("alice"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var aliceResponse=client.send(aliceRequest,HttpResponse.BodyHandlers.ofInputStream());
        assertTrue(subscribed.await(5,TimeUnit.SECONDS));
        var bobCancel=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/chat/requests/"+requestId+"/cancel"))
                .header("Authorization","Bearer "+jwt.generateToken("bob")).POST(HttpRequest.BodyPublishers.noBody()).build();
        var cancellation=client.send(bobCancel,HttpResponse.BodyHandlers.ofString()); assertEquals(200,cancellation.statusCode());
        assertEquals("cancelled",new ObjectMapper().readTree(cancellation.body()).path("data").path("status").asText());
        assertEquals(1,registry.activeRequestCount());
        sink.tryEmitNext(new ChatOutput("chunk",Map.of("chunk","owner answer"))); sink.tryEmitComplete();
        try(var input=aliceResponse.body()) {
            String answer=new String(input.readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(answer.contains("owner answer")); assertTrue(answer.contains("\"status\":\"finished\""));
        }
        verify(handler).persistCompletedTurn(argThat(command -> command.username().equals("alice") && command.requestId().toString().equals(requestId)),eq("owner answer"));
    }    @Test void realHttpLogsExcludePromptToolResultAndJwt() throws Exception {
        String privatePrompt="PVTQUES1", privateToolResult="PVTTOOL2";
        String answerOne="公开回答😀", answerTwo="结束";
        String requestId=UUID.randomUUID().toString();
        CountDownLatch lifecycleLogged=new CountDownLatch(1);
        ch.qos.logback.classic.Logger root=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Logger application=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("com.yizhaoqi.smartpai");
        var audit=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                super.append(event);
                if(event.getFormattedMessage().contains("chatRequestId="+requestId)) lifecycleLogged.countDown();
            }
        };
        audit.setContext(root.getLoggerContext()); audit.start(); root.addAppender(audit); application.addAppender(audit);
        try(MockModelSseServer model=new MockModelSseServer()) {
            model.enqueue(session -> { session.content(answerOne); session.data("[DONE]"); });
            model.enqueue(session -> { session.content(answerTwo); session.data("[DONE]"); });
            var modelClient=new DeepSeekClient(model.url(),"dedicated-test-key","test-model",new AiProperties(),new ObjectMapper());
            when(handler.generateReply(any())).thenAnswer(invocation -> modelClient.streamResponse(List.of(Map.of("role","user","content",privatePrompt)))
                    .concatWith(modelClient.streamResponse(List.of(Map.of("role","tool","content",privateToolResult))))
                    .map(value -> new ChatOutput("chunk",Map.of("chunk",value))));
            String token=jwt.generateToken("alice");
            String body=new ObjectMapper().writeValueAsString(Map.of("conversationId",UUID.randomUUID().toString(),"requestId",requestId,"message",privatePrompt));
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/chat/stream"))
                    .header("Content-Type","application/json").header("Authorization","Bearer "+token)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200,response.statusCode());
            StringBuilder answer=new StringBuilder(); ObjectMapper mapper=new ObjectMapper();
            for(String line:response.body().lines().toList()) {
                if(!line.startsWith("data:")) continue;
                var event=mapper.readTree(line.substring(5));
                if("chunk".equals(event.path("type").asText())) answer.append(event.path("data").path("chunk").asText());
            }
            assertEquals(answerOne+answerTwo,answer.toString());
            verify(handler).persistCompletedTurn(any(),eq(answerOne+answerTwo));
            assertTrue(lifecycleLogged.await(5,TimeUnit.SECONDS),"Request lifecycle audit must remain present");
            String trace;
            synchronized(audit) { trace=audit.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n")); }
            assertFalse(trace.contains(privatePrompt),"Raw prompt must not enter logs");
            assertFalse(trace.contains(privateToolResult),"Raw tool result must not enter logs");
            assertFalse(trace.contains(token),"JWT must not enter logs");
            assertTrue(trace.contains("chatRequestId="+requestId),"Request lifecycle audit must identify this request");
            assertFalse(response.body().contains(privatePrompt)); assertFalse(response.body().contains(privateToolResult)); assertFalse(response.body().contains(token));
        } finally { application.detachAppender(audit); root.detachAppender(audit); audit.stop(); }
    }}






