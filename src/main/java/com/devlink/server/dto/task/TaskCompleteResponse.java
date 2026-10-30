package com.devlink.server.dto.task;

import com.devlink.server.domain.task.TaskStatus;
import java.time.LocalDateTime;

public record TaskCompleteResponse(
		Long taskId,
		TaskStatus status,
		Long version,
		LocalDateTime completedAt) {

}
