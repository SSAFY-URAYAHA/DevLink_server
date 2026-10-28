package com.devlink.server.dto.task;

import com.devlink.server.domain.task.TaskStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record TaskListRequest(
        @Pattern(regexp = "mine|team")
        String scope,

        @Pattern(regexp = "sent|received|all")
        String direction,

        @Positive
        Long agentId,

        TaskStatus status,

        @Min(0)
        Integer page,

        @Min(1) @Max(100)
        Integer size
) {
    public TaskListRequest {
        scope = scope == null ? "mine" : scope;
        direction = direction == null ? "all" : direction;
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }
}