package com.devlink.server.repository;

import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.agent.Agent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentRepository extends JpaRepository<Agent, Long> {
    List<Agent> findByTeamId(Long teamId);

    // 접속 보고와 연결 종료의 상태 갱신을 Agent별로 직렬화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select agent from Agent agent where agent.id = :agentId")
    Optional<Agent> findByIdForPresenceUpdate(@Param("agentId") Long agentId);
}
