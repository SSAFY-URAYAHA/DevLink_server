package com.devlink.server.dto;

import java.time.LocalDateTime;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;

public record ChangeTaskStatusResponse(Long taskId, TaskStatus status, Long version,
        String rejectionReason, String failureReason, boolean partiallyModified,
        LocalDateTime startedAt, LocalDateTime completedAt) {
    public static ChangeTaskStatusResponse from(Task task) {
        return new ChangeTaskStatusResponse(task.getId(), task.getStatus(), task.getVersion(),
                task.getStatus() == TaskStatus.REJECTED ? task.getResultText() : null,
                task.getStatus() == TaskStatus.FAILED ? task.getResultText() : null,
                task.isPartiallyModified(), task.getStartedAt(), task.getCompletedAt());
    }
}
