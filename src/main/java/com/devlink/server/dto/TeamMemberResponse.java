package com.devlink.server.dto;

import java.time.LocalDateTime;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;

public record TeamMemberResponse(Long userId, String name, String nickname,
        MemberRole role, LocalDateTime joinedAt) {
    public static TeamMemberResponse from(User user, TeamMember membership) {
        return new TeamMemberResponse(user.getId(), user.getName(), user.getNickname(),
                membership.getRole(), membership.getJoinedAt());
    }
}
