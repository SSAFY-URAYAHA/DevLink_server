package com.devlink.server.service;

import java.util.Optional;
import com.devlink.server.common.AgentTokenHash;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TeamMemberRepository;
import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Fallback
public class DatabaseAgentTokenVerifier implements AgentTokenVerifier {
    private final AgentRepository agents;
    private final TeamMemberRepository members;

    public DatabaseAgentTokenVerifier(AgentRepository agents, TeamMemberRepository members) {
        this.agents = agents;
        this.members = members;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentPrincipal> verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 256
                || rawToken.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        return agents.findByConnectionTokenHash(AgentTokenHash.sha256(rawToken))
                .filter(agent -> members.existsByTeamIdAndUserId(agent.getTeamId(), agent.getOwnerUserId()))
                .map(agent -> new AgentPrincipal(agent.getId(), agent.getOwnerUserId(), agent.getTeamId()));
    }
}
