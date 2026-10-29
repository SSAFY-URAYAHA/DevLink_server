package com.devlink.server.dto;

import java.time.LocalDateTime;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;

public record CreateTeamResponse(Long teamId, String name, String inviteCode,
        MemberRole myRole, LocalDateTime createdAt) {
    public static CreateTeamResponse from(Team team, TeamMember membership) {
        return new CreateTeamResponse(team.getId(), team.getName(), team.getInviteCode(),
                membership.getRole(), team.getCreatedAt());
    }
}
