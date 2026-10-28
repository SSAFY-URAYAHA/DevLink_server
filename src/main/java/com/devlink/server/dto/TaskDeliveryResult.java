package com.devlink.server.dto;

import java.util.Objects;
import com.devlink.server.dto.TaskRoutingResult.Status;

public record TaskDeliveryResult(Status status, String reasonCode) {
    public TaskDeliveryResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.SENT && reasonCode != null) {
            throw new IllegalArgumentException("Successful delivery cannot have a failure reason");
        }
    }

}
