package com.yizhaoqi.smartpai.controller;
import com.yizhaoqi.smartpai.model.chat.*;
import com.yizhaoqi.smartpai.service.chat.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class ChatControllerSseTest {
    ChatStreamService service; MockMvc mvc;
    final String conversation=UUID.randomUUID().toString(), request=UUID.randomUUID().toString();
    @BeforeEach void setup() {
        service=mock(ChatStreamService.class);
        mvc=MockMvcBuilders.standaloneSetup(new ChatController(service)).setControllerAdvice(new ChatStreamingExceptionHandler()).build();
    }
    String body(String message) { return "{\"conversationId\":\""+conversation+"\",\"requestId\":\""+request+"\",\"message\":\""+message+"\"}"; }
    @Test void legacyInstructionTokenEndpointIsGone() throws Exception {
        mvc.perform(get("/api/v1/chat/websocket-token").principal(() -> "alice"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
    @Test void authenticatedIdentityAndHeaders() throws Exception {
        when(service.open(any())).thenAnswer(invocation -> {
            SseEmitter emitter = new SseEmitter(320000L);
            emitter.send(SseEmitter.event().name("meta").data(Map.of("type", "meta")));
            return emitter;
        });
        mvc.perform(post("/api/v1/chat/stream").principal(() -> "alice").contentType("application/json").content(body("hello")))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-cache, no-transform")).andExpect(header().string("X-Accel-Buffering","no"));
        verify(service).open(new ChatCommand("alice",conversation,UUID.fromString(request),"hello"));
    }
    @Test void noPrincipalReturns401() throws Exception {
        mvc.perform(post("/api/v1/chat/stream").contentType("application/json").content(body("hello"))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.data.errorCode").value("UNAUTHENTICATED")); verifyNoInteractions(service);
    }
    @Test void invalidJsonAndUuidRejected() throws Exception {
        for(String body:List.of("{", "{}",body("hello").replace(request,"bad"),body("hello").replace(conversation,"bad"),body(" "),body("x".repeat(16001)))) {
            mvc.perform(post("/api/v1/chat/stream").principal(() -> "alice").contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.errorCode").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(service);
    }
    @Test void accepts16000Utf16UnitsAndPreservesBody() throws Exception {
        when(service.open(any())).thenReturn(new SseEmitter(320000L)); String message=" x"+"😀".repeat(7999);
        mvc.perform(post("/api/v1/chat/stream").principal(() -> "bob").contentType("application/json").content(body(message))).andExpect(status().isOk());
        verify(service).open(new ChatCommand("bob",conversation,UUID.fromString(request),message));
    }
    @Test void typedStartErrorsMapEveryBranch() throws Exception {
        Map<String,HttpStatus> errors=new LinkedHashMap<>(); errors.put("CONVERSATION_FORBIDDEN",HttpStatus.FORBIDDEN); errors.put("CONVERSATION_NOT_FOUND",HttpStatus.NOT_FOUND); errors.put("REQUEST_DUPLICATE",HttpStatus.CONFLICT); errors.put("REQUEST_CANCELLED",HttpStatus.CONFLICT); errors.put("CONVERSATION_BUSY",HttpStatus.CONFLICT); errors.put("CHAT_CAPACITY_EXCEEDED",HttpStatus.TOO_MANY_REQUESTS);
        for(var error:errors.entrySet()) {
            doThrow(new ChatRequestException(error.getKey(),error.getValue(),"safe test message")).when(service).open(any());
            mvc.perform(post("/api/v1/chat/stream").principal(() -> "alice").contentType("application/json").content(body("hello"))).andExpect(status().is(error.getValue().value())).andExpect(jsonPath("$.data.errorCode").value(error.getKey()));
        }
    }
    @Test void internalStartFailureIsSafe() throws Exception {
        when(service.open(any())).thenThrow(new IllegalStateException("private database credentials"));
        mvc.perform(post("/api/v1/chat/stream").principal(() -> "alice").contentType("application/json").content(body("hello"))).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.data.errorCode").value("CHAT_START_FAILED")).andExpect(jsonPath("$.message").value("聊天请求启动失败"));
    }
    @Test void cancelUsesPrincipalNamespaceAndActualState() throws Exception {
        when(service.cancel("bob",UUID.fromString(request))).thenReturn(new ChatRequestRegistry.CancelResult(UUID.fromString(request),ChatRequestContext.State.COMPLETING,false));
        mvc.perform(post("/api/v1/chat/requests/"+request+"/cancel").principal(() -> "bob")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("completing")); verify(service).cancel("bob",UUID.fromString(request));
    }
    @Test void invalidCancelUuidRejected() throws Exception {
        mvc.perform(post("/api/v1/chat/requests/bad/cancel").principal(() -> "alice")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.data.errorCode").value("INVALID_REQUEST")); verifyNoInteractions(service);
    }
    @Test void asyncIOExceptionDoesNotWriteJsonIntoStartedSse() throws Exception {
        SseEmitter emitter=new SseEmitter(320000L);
        emitter.send(SseEmitter.event().name("meta").data(Map.of("type","meta")));
        when(service.open(any())).thenReturn(emitter);
        var initial=mvc.perform(post("/api/v1/chat/stream").principal(() -> "alice")
                .contentType("application/json").content(body("hello"))).andReturn();
        emitter.completeWithError(new java.io.IOException("dedicated test disconnect"));
        var completed=mvc.perform(asyncDispatch(initial)).andReturn();
        org.junit.jupiter.api.Assertions.assertEquals(200,completed.getResponse().getStatus());
        org.junit.jupiter.api.Assertions.assertFalse(completed.getResponse().getContentAsString().contains("CHAT_START_FAILED"));
    }}

