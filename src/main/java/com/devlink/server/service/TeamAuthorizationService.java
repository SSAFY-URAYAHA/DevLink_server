package com.devlink.server.service;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamAuthorizationService {
    private final TeamRepository teams;
    private final TeamMemberRepository members;

    public TeamAuthorizationService(TeamRepository teams, TeamMemberRepository members) {
        this.teams = teams;
        this.members = members;
    }

    @Transactional(readOnly = true)
    public TeamMember requireTeamMember(Long userId, Long teamId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (teamId == null || teamId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (!teams.existsById(teamId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return members.findByTeamIdAndUserId(teamId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCESS_DENIED));
    }

    @Transactional(readOnly = true)
    public TeamMember requireTeamOwner(Long userId, Long teamId) {
        TeamMember member = requireTeamMember(userId, teamId);
        if (member.getRole() != MemberRole.OWNER) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return member;
    }
}
