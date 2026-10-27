package com.devlink.server.repository;

import java.util.List;
import com.devlink.server.domain.agent.Agent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentRepository extends JpaRepository<Agent, Long> {
    List<Agent> findByTeamId(Long teamId);
}
