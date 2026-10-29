package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.AgentCreateTaskRequest;
import com.devlink.server.dto.CreateTaskRequest;
import com.devlink.server.dto.CreateTaskResponse;
import com.devlink.server.service.TaskCreationService;
import com.devlink.server.service.TaskAuthorizationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/tasks")
@RequiredArgsConstructor
public class AgentTaskController {
    private final TaskCreationService tasks;
    private final TaskAuthorizationService authorization;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateTaskResponse>> create(
            @AuthenticationPrincipal AgentPrincipal principal,
            @Valid @RequestBody AgentCreateTaskRequest request) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        authorization.requireRequester(principal.ownerUserId(), principal.teamId(), principal.agentId(),
                request.receiverAgentId());
        CreateTaskResponse result = tasks.create(principal.teamId(), principal.agentId(),
                new CreateTaskRequest(request.requestKey(), request.receiverAgentId(), request.prompt()));
        return ResponseEntity.status(result.replayed() ? 200 : 201).body(ApiResponse.success(result));
    }
}
