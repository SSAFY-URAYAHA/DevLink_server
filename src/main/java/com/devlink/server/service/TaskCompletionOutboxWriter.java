package com.devlink.server.service;

import java.util.UUID;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.task.Task;
import com.devlink.server.dto.TaskNotificationMessage;
import com.devlink.server.repository.OutboxEventRepository;
import com.devlink.server.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

/** 완료 담당자가 결과·파일 저장과 같은 트랜잭션에서 호출할 완료 알림 저장 지점이다. */
@Service
public class TaskCompletionOutboxWriter {
    private final OutboxEventRepository outbox;
    private final TaskRepository tasks;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;

    public TaskCompletionOutboxWriter(OutboxEventRepository outbox, TaskRepository tasks,
            ObjectMapper mapper, EntityManager entityManager) {
        this.outbox = outbox;
        this.tasks = tasks;
        this.mapper = mapper;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(Task completedTask) {
        Assert.isTrue(entityManager.contains(completedTask), "completed task must be managed in the current transaction");
        // 같은 트랜잭션의 변경을 flush한 뒤 갱신된 version을 완료 메시지에 저장한다.
        tasks.flush();
        UUID outboxId = UUID.randomUUID();
        var message = TaskNotificationMessage.completed(outboxId, completedTask);
        outbox.save(OutboxEvent.create(outboxId, message.type(), "TASK", completedTask.getId().toString(),
                "task-events", "TASK:" + completedTask.getId(), mapper.valueToTree(message)));
        return outboxId;
    }
}
