package com.devlink.server.controller;

import java.util.List;
import com.devlink.server.common.ApiResponse;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.UserPrincipal;
import com.devlink.server.dto.CreateTeamRequest;
import com.devlink.server.dto.CreateTeamResponse;
import com.devlink.server.dto.JoinTeamRequest;
import com.devlink.server.dto.TeamInviteResponse;
import com.devlink.server.dto.TeamJoinResponse;
import com.devlink.server.dto.TeamMemberResponse;
import com.devlink.server.dto.TeamResponse;
import com.devlink.server.service.TeamCreationService;
import com.devlink.server.service.TeamService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamController {
    private final TeamCreationService teamCreationService;
    private final TeamService teamService;

    @PostMapping
    public ResponseEntity<ApiResponse<CreateTeamResponse>> create(
            @Valid @RequestBody CreateTeamRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(teamCreationService.create(principal.userId(), request)));
    }

    @GetMapping
    public ApiResponse<List<TeamResponse>> getMyTeams(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(teamService.getMyTeams(requireUserId(principal)));
    }

    @GetMapping("/{teamId}")
    public ApiResponse<TeamResponse> getTeam(@PathVariable Long teamId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(teamService.getTeam(requireUserId(principal), teamId));
    }

    @GetMapping("/{teamId}/members")
    public ApiResponse<List<TeamMemberResponse>> getMembers(@PathVariable Long teamId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(teamService.getMembers(requireUserId(principal), teamId));
    }

    @PostMapping("/{teamId}/invites")
    public ResponseEntity<ApiResponse<TeamInviteResponse>> regenerateInvite(@PathVariable Long teamId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(teamService.regenerateInvite(requireUserId(principal), teamId)));
    }

    @PostMapping("/join")
    public ResponseEntity<ApiResponse<TeamJoinResponse>> join(@Valid @RequestBody JoinTeamRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        TeamJoinResponse result = teamService.join(requireUserId(principal), request);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ApiResponse.success(result));
    }

    private Long requireUserId(UserPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return principal.userId();
    }
}
