package com.devlink.server.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.util.Assert;

public record TaskNotificationMessage(String type, @JsonProperty("event_id") UUID eventId,
        @JsonProperty("task_id") long taskId, long version, Payload payload) {
    public static TaskNotificationMessage completed(UUID eventId, Task task) {
        Assert.isTrue(task.getStatus() == TaskStatus.COMPLETED, "task must be completed");
        Assert.notNull(task.getId(), "task must be persisted");
        Assert.notNull(task.getVersion(), "task must be flushed");
        Assert.hasText(task.getResultText(), "completed result must not be blank");
        Assert.notNull(task.getStartedAt(), "startedAt must not be null");
        Assert.notNull(task.getCompletedAt(), "completedAt must not be null");
        return new TaskNotificationMessage("TASK_COMPLETED", eventId, task.getId(), task.getVersion(),
                new Payload(task.getStatus(), task.getResultText(), task.isPartiallyModified(),
                        task.getStartedAt(), task.getCompletedAt()));
    }

    public record Payload(TaskStatus status, @JsonProperty("result_text") String resultText,
            @JsonProperty("partially_modified") boolean partiallyModified,
            @JsonProperty("started_at") LocalDateTime startedAt,
            @JsonProperty("completed_at") LocalDateTime completedAt) { }
}
