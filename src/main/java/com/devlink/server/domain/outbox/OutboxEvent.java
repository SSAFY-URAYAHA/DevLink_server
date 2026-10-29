package com.devlink.server.domain.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Persist in the same transaction as the business change. Publication is asynchronous. */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {
    @Id
    @Column(name = "outbox_id", nullable = false, updatable = false)
    private UUID outboxId;

    @Column(name = "event_type", length = 100, nullable = false, updatable = false)
    private String eventType;

    @Column(name = "aggregate_type", length = 50, nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", length = 100, nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "destination", length = 255, nullable = false, updatable = false)
    private String destination;

    @Column(name = "message_key", length = 255, updatable = false)
    private String messageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "message", nullable = false, updatable = false, columnDefinition = "JSONB")
    private JsonNode message;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "lock_token")
    private UUID lockToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    /** message.event_id matches outboxId; the wire envelope retains its event_id field. */
    public static OutboxEvent create(UUID outboxId, String eventType, String aggregateType,
                                     String aggregateId, String destination, String messageKey,
                                     JsonNode message) {
        Objects.requireNonNull(outboxId, "outboxId must not be null");
        requireText(eventType, "eventType", 100);
        requireText(aggregateType, "aggregateType", 50);
        requireText(aggregateId, "aggregateId", 100);
        requireText(destination, "destination", 255);
        if (messageKey != null) {
            requireText(messageKey, "messageKey", 255);
        }
        if (message == null || !message.isObject()
                || !outboxId.toString().equals(message.path("event_id").asText())
                || !eventType.equals(message.path("type").asText())) {
            throw new IllegalArgumentException("message must be an object with matching event_id and type");
        }
        OutboxEvent entity = new OutboxEvent();
        entity.outboxId = outboxId;
        entity.eventType = eventType;
        entity.aggregateType = aggregateType;
        entity.aggregateId = aggregateId;
        entity.destination = destination;
        entity.messageKey = messageKey;
        entity.message = message.deepCopy();
        entity.status = OutboxStatus.PENDING;
        return entity;
    }

    public JsonNode getMessage() {
        return message.deepCopy();
    }

    @PrePersist
    private void initializeTimestamps() {
        this.createdAt = Instant.now();
        this.availableAt = this.createdAt;
    }

    private static void requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not be blank or exceed " + maxLength + " characters");
        }
    }
}
