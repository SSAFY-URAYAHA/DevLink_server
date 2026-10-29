package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.dto.task.TaskDetailResponse;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.task.TaskListRequest;
import com.devlink.server.dto.task.TaskListResponse;
import com.devlink.server.service.TaskQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TaskQueryController {

    private final TaskQueryService taskQueryService;

    @GetMapping("/teams/{teamId}/tasks")
    public ResponseEntity<ApiResponse<TaskListResponse>> getTasks(
            @PathVariable @Positive Long teamId,
            @AuthenticationPrincipal AgentPrincipal agent,
            @Valid @ModelAttribute TaskListRequest request
    ) {
        TaskListResponse response = taskQueryService.getTasks(
                teamId,
                agent,
                request
        );

        return ResponseEntity.ok(
                ApiResponse.success(response)
        );
    }
    
    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<ApiResponse<TaskDetailResponse>> getTask(
            @PathVariable @Positive Long taskId,
            @AuthenticationPrincipal AgentPrincipal agent
    ) {
        TaskDetailResponse response = taskQueryService.getTask(
                taskId,
                agent
        );

        return ResponseEntity.ok(
                ApiResponse.success(response)
        );
    }
    
    
}