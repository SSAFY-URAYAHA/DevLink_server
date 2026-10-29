package com.devlink.server.dto;

import com.devlink.server.domain.team.Team;

public record TeamInviteResponse(Long teamId, String inviteCode) {
    public static TeamInviteResponse from(Team team) {
        return new TeamInviteResponse(team.getId(), team.getInviteCode());
    }
}
