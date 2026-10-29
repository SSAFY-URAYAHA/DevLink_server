package com.devlink.server.controller;

import java.util.List;
import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.AgentResponse;
import com.devlink.server.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
public class AgentApiController {
    private final AgentService agents;

    @GetMapping("/context")
    public ApiResponse<AgentResponse> context(@AuthenticationPrincipal AgentPrincipal principal) {
        AgentPrincipal actor = requireAgent(principal);
        return ApiResponse.success(agents.get(actor.ownerUserId(), actor.agentId()));
    }

    @GetMapping("/agents")
    public ApiResponse<List<AgentResponse>> list(@AuthenticationPrincipal AgentPrincipal principal) {
        AgentPrincipal actor = requireAgent(principal);
        return ApiResponse.success(agents.list(actor.ownerUserId(), actor.teamId()));
    }

    private AgentPrincipal requireAgent(AgentPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal;
    }
}
