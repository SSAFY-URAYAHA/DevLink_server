package com.devlink.server.repository;

import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {
    Optional<TaskEvent> findFirstByTaskIdAndEventTypeInOrderByIdDesc(
            Long taskId, List<TaskEventType> eventTypes);
    List<TaskEvent> findByTaskIdOrderByCreatedAtAsc(Long taskId);
}
