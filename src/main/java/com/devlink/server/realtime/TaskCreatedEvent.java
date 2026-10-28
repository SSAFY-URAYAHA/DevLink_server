package com.devlink.server.realtime;

/** 생성 담당자가 작업 저장 트랜잭션 안에서 발행할 서버 내부 이벤트다. */
public record TaskCreatedEvent(long taskId) { }
