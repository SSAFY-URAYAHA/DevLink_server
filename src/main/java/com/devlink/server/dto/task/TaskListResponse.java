package com.devlink.server.dto.task;

import java.util.List;

public record TaskListResponse(
        List<TaskListItemResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}