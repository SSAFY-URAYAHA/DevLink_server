package com.devlink.server.service;

import java.util.Map;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.TaskNotificationResult.Status;
import com.devlink.server.realtime.TaskNotificationEvent;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 알림 실패만 독립 트랜잭션에 기록하며, 원래 작업의 상태·결과·버전은 수정하지 않는다. */
@Service
public class TaskNotificationFailureRecorder {
    private final TaskRepository tasks;
    private final TaskEventRepository history;
    private final ObjectMapper mapper;

    public TaskNotificationFailureRecorder(TaskRepository tasks, TaskEventRepository history, ObjectMapper mapper) {
        this.tasks = tasks;
        this.history = history;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(TaskNotificationEvent event, Status reason) {
        // 커밋 이후 작업이 삭제된 경우에는 존재하지 않는 작업의 이력을 만들지 않는다.
        if (!tasks.existsById(event.taskId())) {
            return;
        }
        try {
            String content = mapper.writeValueAsString(Map.of(
                    "event_id", event.eventId(), "message_type", event.type().name(),
                    "requester_agent_id", event.payload().requesterAgentId(), "reason_code", reason.name()));
            history.save(TaskEvent.of(event.taskId(), TaskEventType.NOTIFY_FAILED, content));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize notification failure", exception);
        }
    }
}
