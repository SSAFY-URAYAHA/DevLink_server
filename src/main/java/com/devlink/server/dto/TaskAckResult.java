package com.devlink.server.dto;

/** ACK 대기가 끝난 결과다. 기존 소켓 전송 결과(SENT) 및 DB의 작업 상태와 별도로 사용한다. */
public record TaskAckResult(long taskId, String eventId, String connectionId, Status status) {
    public enum Status {
        ACKNOWLEDGED,
        TIMED_OUT,
        CONNECTION_CLOSED,
        SEND_FAILED
    }
}
