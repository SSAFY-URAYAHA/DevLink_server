package com.devlink.server.dto;

import java.time.LocalDateTime;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;

public record TeamJoinResponse(Long teamId, String name, MemberRole myRole,
        LocalDateTime joinedAt, boolean replayed) {
    public static TeamJoinResponse from(Team team, TeamMember membership, boolean replayed) {
        return new TeamJoinResponse(team.getId(), team.getName(), membership.getRole(),
                membership.getJoinedAt(), replayed);
    }
}
