package com.devlink.server.dto;

import java.util.Objects;
import com.devlink.server.domain.task.DeliveryStatus;

public record TaskDeliveryResult(DeliveryStatus status, String reasonCode) {
    public TaskDeliveryResult {
        Objects.requireNonNull(status, "status");
        if (status == DeliveryStatus.DELIVERED && reasonCode != null) {
            throw new IllegalArgumentException("Successful delivery cannot have a failure reason");
        }
    }

    public static TaskDeliveryResult unknown() {
        return new TaskDeliveryResult(DeliveryStatus.UNKNOWN, null);
    }
}
