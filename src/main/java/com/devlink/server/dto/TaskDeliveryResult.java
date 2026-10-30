package com.devlink.server.dto;

import java.util.Objects;
import com.devlink.server.domain.taskevent.TaskEventType;

public record TaskDeliveryResult(
    TaskEventType status,
    String reasonCode
) {
    public TaskDeliveryResult {
        Objects.requireNonNull(status, "status");

        switch (status) {
            case ACK_PENDING, ACK_RECEIVED -> {
                if (reasonCode != null) {
                    throw new IllegalArgumentException(
                        "ACK status cannot have a failure reason");
                }
            }

            case DELIVERY_UNKNOWN, DELIVERY_NOT_SENT -> {
                if (reasonCode == null || reasonCode.isBlank()) {
                    throw new IllegalArgumentException(
                        "Delivery failure must have a reason");
                }
            }

            default -> throw new IllegalArgumentException(
                "Unsupported delivery status: " + status);
        }
    }
}
