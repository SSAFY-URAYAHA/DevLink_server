package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
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
            @RequestParam @Positive Long agentId) {
        // TODO: Authorization: Bearer {Agent 연결 토큰} 인증 연동 후 agentId 요청 파라미터를
        // 인증 객체로 교체하고, 검증된 객체에서 agentId를 추출한다. 현재 파라미터는 임시 입력이다.
        return ResponseEntity.ok(ApiResponse.success(taskStatusService.changeStatus(taskId, agentId, request)));
    }

    @PostMapping("/{teamId}")
    public ResponseEntity<ApiResponse<CreateTaskResponse>> create(
            @PathVariable @Positive Long teamId,
            @Valid @RequestBody CreateTaskRequest request,
            @RequestHeader("Authorization") String agentToken,
            @RequestParam @Positive Long agentId) {
        // TODO: Authorization: Bearer {Agent 연결 토큰} 검증 및 Agent 전용 보안 필터 연동.
        // agentId 요청 파라미터는 임시 입력이며, 인증 연동 후 인증 객체로 교체하여 추출한다.
        CreateTaskResponse response = taskCreationService.create(teamId, agentId, request);
        return ResponseEntity.status(response.replayed() ? 200 : 201)
                .body(ApiResponse.success(response));
    }
}
