package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.CreateTaskRequest;
import com.devlink.server.dto.CreateTaskResponse;
import com.devlink.server.service.TaskCreationService;
import com.devlink.server.service.TaskStatusService;
import com.devlink.server.dto.ChangeTaskStatusRequest;
import com.devlink.server.dto.ChangeTaskStatusResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {
    private final TaskCreationService taskCreationService;
    private final TaskStatusService taskStatusService;

    @PatchMapping("/{taskId}/status")
    public ResponseEntity<ApiResponse<ChangeTaskStatusResponse>> changeStatus(
            @PathVariable @Positive Long taskId,
            @Valid @RequestBody ChangeTaskStatusRequest request,
            @AuthenticationPrincipal AgentPrincipal principal) {
        // 검증된 Agent 인증 객체에서 수신 Agent 식별자 추출
        return ResponseEntity.ok(ApiResponse.success(
                taskStatusService.changeStatus(taskId, requireAgentId(principal), request)));
    }

    @PostMapping("/{teamId}")
    public ResponseEntity<ApiResponse<CreateTaskResponse>> create(
            @PathVariable @Positive Long teamId,
            @Valid @RequestBody CreateTaskRequest request,
            @AuthenticationPrincipal AgentPrincipal principal) {
        // 검증된 Agent 인증 객체에서 요청 Agent 식별자 추출
        CreateTaskResponse response = taskCreationService.create(teamId, requireAgentId(principal), request);
        return ResponseEntity.status(response.replayed() ? 200 : 201)
                .body(ApiResponse.success(response));
    }

    private long requireAgentId(AgentPrincipal principal) {
        if (principal == null || principal.agentId() <= 0
                || principal.ownerUserId() <= 0 || principal.teamId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.agentId();
    }
}
