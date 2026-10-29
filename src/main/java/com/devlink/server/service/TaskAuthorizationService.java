package com.devlink.server.service;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.repository.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskAuthorizationService {
    private final TeamAuthorizationService teams;
    private final AgentRepository agents;

    public TaskAuthorizationService(TeamAuthorizationService teams, AgentRepository agents) {
        this.teams = teams;
        this.agents = agents;
    }

    @Transactional(readOnly = true)
    public void requireRequester(Long userId, Long teamId, Long requesterAgentId, Long receiverAgentId) {
        teams.requireTeamMember(userId, teamId);
        if (requesterAgentId == null || requesterAgentId <= 0
                || receiverAgentId == null || receiverAgentId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Agent requester = requireAgent(requesterAgentId);
        Agent receiver = requesterAgentId.equals(receiverAgentId) ? requester : requireAgent(receiverAgentId);
        if (!requester.getTeamId().equals(teamId) || !receiver.getTeamId().equals(teamId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!requester.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
    }

    private Agent requireAgent(Long agentId) {
        return agents.findById(agentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
