package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.chat.ChatCommand;
import com.yizhaoqi.smartpai.model.chat.ChatStreamRequest;
import com.yizhaoqi.smartpai.service.chat.ChatRequestException;
import com.yizhaoqi.smartpai.service.chat.ChatStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.beans.PropertyEditorSupport;
import java.security.Principal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {
    public static final String EMITTER_REQUEST_ATTRIBUTE = ChatController.class.getName() + ".emitter";
    private final ChatStreamService streams;
    public ChatController(ChatStreamService streams) { this.streams = streams; }

    @PostMapping(value = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@RequestBody ChatStreamRequest request, Principal principal) {
        String username = requireUsername(principal);
        if (request == null) throw invalidRequest();
        ChatCommand command = new ChatCommand(username, request.conversationId(), request.requestId(), request.message());
        ChatStreamService.validate(command);
        SseEmitter emitter = streams.open(command);
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            attributes.getRequest().setAttribute(EMITTER_REQUEST_ATTRIBUTE, emitter);
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "event-stream", java.nio.charset.StandardCharsets.UTF_8))
                .header("Cache-Control", "no-cache, no-transform").header("X-Accel-Buffering", "no").body(emitter);
    }

    @PostMapping("/requests/{requestId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable UUID requestId, Principal principal) {
        var result = streams.cancel(requireUsername(principal), requestId);
        return ResponseEntity.ok(Map.of("code", 200, "message", "请求状态已确认", "data", Map.of(
                "requestId", result.requestId(), "status", result.state().name().toLowerCase(Locale.ROOT))));
    }

    @InitBinder
    void canonicalUuidPathVariables(WebDataBinder binder) {
        binder.registerCustomEditor(UUID.class, new PropertyEditorSupport() {
            @Override public void setAsText(String text) {
                UUID id = UUID.fromString(text);
                if (!id.toString().equalsIgnoreCase(text)) throw new IllegalArgumentException("Invalid UUID");
                setValue(id);
            }
        });
    }

    private static String requireUsername(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ChatRequestException("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return principal.getName();
    }
    private static ChatRequestException invalidRequest() {
        return new ChatRequestException("INVALID_REQUEST", HttpStatus.BAD_REQUEST, "聊天请求参数无效");
    }

}
