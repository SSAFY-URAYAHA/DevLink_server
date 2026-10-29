package com.devlink.server.repository;

import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.teammember.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {
    boolean existsByTeamIdAndUserId(Long teamId, Long userId);

    Optional<TeamMember> findByTeamIdAndUserId(Long teamId, Long userId);

    List<TeamMember> findByUserIdOrderByJoinedAtDescIdDesc(Long userId);

    List<TeamMember> findByTeamIdOrderByJoinedAtAscIdAsc(Long teamId);
}
