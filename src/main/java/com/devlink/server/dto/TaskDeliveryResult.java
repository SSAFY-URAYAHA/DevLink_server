package com.devlink.server.dto;

import java.util.Objects;
import com.devlink.server.domain.taskevent.TaskEventType;

public record TaskDeliveryResult(TaskEventType status, String reasonCode) {
    public TaskDeliveryResult {
        Objects.requireNonNull(status, "status");
        if (status == TaskEventType.DELIVERED && reasonCode != null) {
            throw new IllegalArgumentException("Successful delivery cannot have a failure reason");
        }
        if (status == TaskEventType.DELIVERY_FAILED && (reasonCode == null || reasonCode.isBlank())) {
            throw new IllegalArgumentException("Failed delivery must have a failure reason");
        }
    }

}
