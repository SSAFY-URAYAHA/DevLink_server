package com.devlink.server.domain.taskevent;

public enum TaskEventType {
    // 1. 작업 생성
    CREATED,
    // 2. 브로커 전달
    ACK_PENDING,
    ACK_RECEIVED,
    DELIVERY_UNKNOWN,
    DELIVERY_NOT_SENT,
    // 3. 작업 실행
    STATUS_CHANGED,
    PROGRESS,
    // 4. 알림 실패
    NOTIFY_FAILED
}
