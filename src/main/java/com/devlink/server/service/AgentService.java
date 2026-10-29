package com.devlink.server.service;

import java.util.List;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.dto.AgentResponse;
import com.devlink.server.dto.CreateAgentRequest;
import com.devlink.server.repository.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentService {
    private final AgentRepository agents;
    private final TeamAuthorizationService authorization;

    public AgentService(AgentRepository agents, TeamAuthorizationService authorization) {
        this.agents = agents;
        this.authorization = authorization;
    }

    @Transactional
    public AgentResponse create(Long userId, Long teamId, CreateAgentRequest request) {
        authorization.requireTeamMember(userId, teamId);
        Agent agent = Agent.create(teamId, userId, request.name(), request.roleDescription(),
                request.toolType(), request.projectName());
        return AgentResponse.from(agents.saveAndFlush(agent));
    }

    @Transactional(readOnly = true)
    public List<AgentResponse> list(Long userId, Long teamId) {
        authorization.requireTeamMember(userId, teamId);
        return agents.findAllByTeamIdOrderByIdAsc(teamId).stream().map(AgentResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AgentResponse get(Long userId, Long agentId) {
        requireUser(userId);
        requireAgentId(agentId);
        Agent agent = agents.findById(agentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        authorization.requireTeamMember(userId, agent.getTeamId());
        return AgentResponse.from(agent);
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    private void requireAgentId(Long agentId) {
        if (agentId == null || agentId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
