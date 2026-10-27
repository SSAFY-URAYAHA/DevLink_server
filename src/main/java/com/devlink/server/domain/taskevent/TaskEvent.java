package com.devlink.server.domain.taskevent;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Bounded context: task.
@Entity
@Table(name = "task_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", length = 255, nullable = false)
    private Long id;

    @Column(name = "task_id", length = 255, nullable = false)
    private Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 30, nullable = false)
    private TaskEventType eventType;

    @Column(name = "content", length = 255, nullable = true, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", length = 255, nullable = false, columnDefinition = "TIMESTAMP(3)")
    private LocalDateTime createdAt;

    public static TaskEvent of(Long taskId, TaskEventType type, String content) {
        requireValue(taskId, "taskId");
        requireValue(type, "eventType");
        TaskEvent entity = new TaskEvent();
        entity.taskId = taskId;
        entity.eventType = type;
        entity.content = content;
        return entity;
    }

    @PrePersist
    private void initializeTimestamp() {
        this.createdAt = LocalDateTime.now();
    }

    private static void requireValue(Object value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }
}
