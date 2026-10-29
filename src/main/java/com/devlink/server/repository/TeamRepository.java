package com.devlink.server.repository;

import java.util.Optional;
import com.devlink.server.domain.team.Team;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamRepository extends JpaRepository<Team, Long> {
    Optional<Team> findByInviteCode(String inviteCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select team from Team team where team.id = :teamId")
    Optional<Team> findByIdForUpdate(@Param("teamId") Long teamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select team from Team team where team.inviteCode = :inviteCode")
    Optional<Team> findByInviteCodeForUpdate(@Param("inviteCode") String inviteCode);
}
