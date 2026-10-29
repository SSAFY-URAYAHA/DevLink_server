package com.devlink.server.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
    DUPLICATE_REQUEST(HttpStatus.CONFLICT, "중복 요청입니다."),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    REQUEST_KEY_CONFLICT(HttpStatus.CONFLICT, "동일 요청 키에 다른 작업 요청이 존재합니다."),
    RECEIVER_OFFLINE(HttpStatus.CONFLICT, "수신 Agent의 브로커에 연결할 수 없습니다."),
    DELIVERY_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "작업 전달 서비스를 사용할 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() { return status; }
    public String getMessage() { return message; }
}
