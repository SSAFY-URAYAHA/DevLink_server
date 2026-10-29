package com.devlink.server.dto;

import java.util.UUID;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.fasterxml.jackson.annotation.JsonProperty;

public record TaskStatusChangedMessage(
        String type,
        @JsonProperty("event_id") UUID eventId,
        @JsonProperty("task_id") Long taskId,
        Long version,
        Payload payload) {
    public static final String TYPE = "TASK_STATUS_CHANGED";
    // Logical destination; the outbox publisher maps this to the broker transport.
    public static final String DESTINATION = "task-events";

    /** Call after flushing the task so the envelope contains the committed candidate version. */
    public static TaskStatusChangedMessage from(UUID outboxId, Task task, TaskStatus previousStatus) {
        return new TaskStatusChangedMessage(TYPE, outboxId, task.getId(), task.getVersion(),
                new Payload(previousStatus, task.getStatus()));
    }

    public record Payload(
            @JsonProperty("previous_status") TaskStatus previousStatus,
            TaskStatus status) { }
}
