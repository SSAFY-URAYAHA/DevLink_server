package com.devlink.server.dto;

import com.devlink.server.domain.task.TaskStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ChangeTaskStatusRequest(
        @NotNull TaskStatus status,
        @NotNull @PositiveOrZero Long version,
        String rejectionReason,
        String failureReason,
        Boolean partiallyModified) {

    @JsonIgnore
    @AssertTrue(message = "Fields must match the requested task status")
    public boolean isValidTransitionInput() {
        if (status == null) return true; // @NotNull handles the missing status.
        return switch (status) {
            case IN_PROGRESS -> rejectionReason == null && failureReason == null && partiallyModified == null;
            case REJECTED -> hasText(rejectionReason) && failureReason == null && partiallyModified == null;
            case FAILED -> hasText(failureReason) && rejectionReason == null && partiallyModified != null;
            default -> false;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
