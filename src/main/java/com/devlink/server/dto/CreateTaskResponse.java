package com.devlink.server.dto;

import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;

public record CreateTaskResponse(Long taskId, Long receiverAgentId, TaskStatus status,
        Long version, boolean replayed, TaskDeliveryResult delivery) {
    public static CreateTaskResponse of(Task task, boolean replayed, TaskDeliveryResult delivery) {
        return new CreateTaskResponse(task.getId(), task.getReceiverAgentId(), task.getStatus(),
                task.getVersion(), replayed, delivery);
    }
}
