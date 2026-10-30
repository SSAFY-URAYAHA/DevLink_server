package com.devlink.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotBlank @Size(max = 64) String requestKey,
        @NotNull @Positive Long receiverAgentId,
        @NotBlank String prompt) {
}
