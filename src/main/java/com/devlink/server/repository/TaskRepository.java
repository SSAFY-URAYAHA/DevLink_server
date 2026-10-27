package com.devlink.server.repository;

import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.task.Task;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, Long> {
    Optional<Task> findByRequesterUserIdAndRequestKey(Long requesterUserId, String requestKey);

    List<Task> findByTeamIdOrderByCreatedAtDesc(Long teamId);
}
