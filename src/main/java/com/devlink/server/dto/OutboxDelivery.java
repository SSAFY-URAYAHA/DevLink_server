package com.devlink.server.dto;

import java.util.UUID;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;

/** 점유 트랜잭션 밖으로 전달할 값 객체다. JPA 엔티티나 현재 Task 상태를 메시지로 사용하지 않는다. */
public record OutboxDelivery(UUID outboxId, UUID lockToken, String eventType, String aggregateType,
        String aggregateId, String destination, String messageKey, JsonNode message, int attemptCount) {
    public OutboxDelivery {
        message = message.deepCopy();
    }

    public static OutboxDelivery from(OutboxEvent event) {
        return new OutboxDelivery(event.getOutboxId(), event.getLockToken(), event.getEventType(),
                event.getAggregateType(), event.getAggregateId(), event.getDestination(), event.getMessageKey(),
                event.getMessage(), event.getAttemptCount());
    }

    @Override
    public JsonNode message() {
        return message.deepCopy();
    }
}
