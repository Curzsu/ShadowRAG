package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.chat.ChatRequestException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

/** JSON errors apply before SSE starts; generation failures are emitted by the stream service. */
@RestControllerAdvice(assignableTypes = ChatController.class)
public class ChatStreamingExceptionHandler {
    @ExceptionHandler(ChatRequestException.class)
    ResponseEntity<Map<String, Object>> requestError(ChatRequestException error, HttpServletRequest request,
                                                    HttpServletResponse response) {
        return response(error.getStatus(), error.getErrorCode(), error.getMessage(), request, response);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, MethodArgumentNotValidException.class})
    ResponseEntity<Map<String, Object>> invalidRequest(Exception error, HttpServletRequest request,
                                                     HttpServletResponse response) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "聊天请求参数无效", request, response);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> startFailed(Exception error, HttpServletRequest request,
                                                  HttpServletResponse response) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "CHAT_START_FAILED", "聊天请求启动失败", request, response);
    }
    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String errorCode, String message,
                                                        HttpServletRequest request, HttpServletResponse response) {
        // Servlet transport errors belong to the existing stream; never append JSON or write again.
        if (request.getDispatcherType() == DispatcherType.ASYNC || response.isCommitted()) return null;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of(
                "code", status.value(), "message", message, "data", Map.of("errorCode", errorCode)));
    }
}
