package com.devlink.server.dto.task;

import com.devlink.server.domain.taskevent.TaskEventType;
import java.time.LocalDateTime;

public record TaskEventResponse(
        TaskEventType eventType,
        String message,
        LocalDateTime createdAt
) {
	
	
}