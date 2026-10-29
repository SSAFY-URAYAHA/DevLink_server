package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.dto.AgentTokenResponse;
import com.devlink.server.service.AgentTokenService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agents/{agentId}/token")
@RequiredArgsConstructor
public class AgentTokenController {
    private final AgentTokenService tokens;

    @PostMapping
    public ResponseEntity<ApiResponse<AgentTokenResponse>> issue(@PathVariable @Positive Long agentId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.success(tokens.issue(userId(principal), agentId)));
    }

    private long userId(UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.userId();
    }
}
