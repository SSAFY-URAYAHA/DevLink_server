package com.devlink.server.repository;

import java.util.List;
import com.devlink.server.domain.taskevent.TaskEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {
    List<TaskEvent> findByTaskIdOrderByCreatedAtAsc(Long taskId);
}
