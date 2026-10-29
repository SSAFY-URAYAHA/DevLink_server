package com.devlink.server.service;

import java.security.SecureRandom;
import java.util.Base64;
import com.devlink.server.common.AgentTokenHash;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.dto.AgentTokenResponse;
import com.devlink.server.realtime.AgentCredentialsChangedEvent;
import com.devlink.server.repository.AgentRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTokenService {
    private final AgentRepository agents;
    private final TeamAuthorizationService authorization;
    private final ApplicationEventPublisher events;
    private final SecureRandom random = new SecureRandom();

    public AgentTokenService(AgentRepository agents, TeamAuthorizationService authorization,
            ApplicationEventPublisher events) {
        this.agents = agents;
        this.authorization = authorization;
        this.events = events;
    }

    @Transactional
    public AgentTokenResponse issue(Long userId, Long agentId) {
        Agent agent = requireOwnedAgent(userId, agentId);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        agent.issueConnectionToken(AgentTokenHash.sha256(token));
        agents.flush();
        events.publishEvent(new AgentCredentialsChangedEvent(agent.getId()));
        return new AgentTokenResponse(agent.getId(), token);
    }

    private Agent requireOwnedAgent(Long userId, Long agentId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (agentId == null || agentId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Agent agent = agents.findByIdForUpdate(agentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        authorization.requireTeamMember(userId, agent.getTeamId());
        if (!agent.getOwnerUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        return agent;
    }
}
