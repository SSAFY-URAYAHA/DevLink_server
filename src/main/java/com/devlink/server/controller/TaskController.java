package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.dto.CreateTaskRequest;
import com.devlink.server.dto.CreateTaskResponse;
import com.devlink.server.service.TaskCreationService;
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

    @PostMapping("/{teamId}")
    public ResponseEntity<ApiResponse<CreateTaskResponse>> create(
            @PathVariable @Positive Long teamId,
            @Valid @RequestBody CreateTaskRequest request,
            @SessionAttribute(name = "userId", required = false) Long userId) {

        // 1. 세션 값 확인
        // TODO : 로그인, 요청 Agent 소유권 사용자 팀 권한 검증 구현
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        CreateTaskResponse response = taskCreationService.create(teamId, userId, request);
        return ResponseEntity.status(response.replayed() ? 200 : 201)
                .body(ApiResponse.success(response));
    }
}
