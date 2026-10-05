package com.yizhaoqi.smartpai.service.chat;

import org.springframework.http.HttpStatus;

import java.util.Objects;

/** Contains only a public error code, HTTP status and safe display message. */
public class ChatRequestException extends RuntimeException {
    private final String errorCode;
    private final HttpStatus status;

    public ChatRequestException(String errorCode, HttpStatus status, String safeMessage) {
        super(safeMessage);
        this.errorCode = Objects.requireNonNull(errorCode);
        this.status = Objects.requireNonNull(status);
    }

    public String getErrorCode() { return errorCode; }
    public HttpStatus getStatus() { return status; }
}
