package com.devlink.server.dto;

import java.time.LocalDateTime;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;

public record TeamResponse(Long teamId, String name, MemberRole myRole, LocalDateTime createdAt) {
    public static TeamResponse from(Team team, TeamMember membership) {
        return new TeamResponse(team.getId(), team.getName(), membership.getRole(), team.getCreatedAt());
    }
}
