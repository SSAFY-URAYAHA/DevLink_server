package com.devlink.server.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.task.Task;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {
    Optional<Task> findByRequesterUserIdAndRequestKey(Long requesterUserId, String requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.id = :taskId")
    Optional<Task> findByIdForUpdate(@Param("taskId") Long taskId);

    List<Task> findByTeamIdOrderByCreatedAtDesc(Long teamId);
}
