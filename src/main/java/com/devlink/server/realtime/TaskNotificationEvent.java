package com.devlink.server.realtime;

import java.util.UUID;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.dto.TaskNotificationMessage;
import org.springframework.util.Assert;

/** 상태·완료 담당자가 변경을 flush한 뒤 같은 트랜잭션 안에서 발행할 불변 이벤트다. */
public record TaskNotificationEvent(String eventId, Type type, long requesterUserId,
        TaskNotificationMessage.Payload payload) {
    public enum Type {
        TASK_STATUS_CHANGED,
        TASK_COMPLETED
    }

    public TaskNotificationEvent {
        Assert.hasText(eventId, "eventId must not be blank");
        Assert.notNull(type, "type must not be null");
        Assert.notNull(payload, "payload must not be null");
        Assert.isTrue(requesterUserId > 0 && payload.taskId() > 0 && payload.teamId() > 0
                && payload.requesterAgentId() > 0 && payload.receiverAgentId() > 0,
                "notification identities must be positive");
        Assert.isTrue(payload.version() >= 0, "version must not be negative");
        boolean allowed = type == Type.TASK_COMPLETED ? payload.status() == TaskStatus.COMPLETED
                : payload.status() == TaskStatus.IN_PROGRESS || payload.status() == TaskStatus.FAILED
                        || payload.status() == TaskStatus.REJECTED;
        Assert.isTrue(allowed, "notification type does not match task status");
        // 거절은 작업 시작 전이므로 시작 시각 없이 알린다. 수락·실패·완료는 기존 검증을 유지한다.
        if (payload.status() != TaskStatus.REJECTED) {
            Assert.notNull(payload.startedAt(), "startedAt must not be null");
        }
        if (payload.status() != TaskStatus.IN_PROGRESS) {
            // 거절도 종료 상태이므로 거절 사유와 종료 시각은 반드시 있어야 한다.
            Assert.hasText(payload.resultText(), "terminal task result must not be blank");
            Assert.notNull(payload.completedAt(), "completedAt must not be null");
        }
    }

    public static TaskNotificationEvent statusChanged(Task task) {
        return from(task, Type.TASK_STATUS_CHANGED);
    }

    public static TaskNotificationEvent completed(Task task) {
        return from(task, Type.TASK_COMPLETED);
    }

    private static TaskNotificationEvent from(Task task, Type type) {
        Assert.notNull(task, "task must not be null");
        Assert.notNull(task.getId(), "task must be persisted");
        Assert.notNull(task.getVersion(), "task version must be available after flush");
        // 엔티티를 그대로 보관하지 않아 커밋 이후 다른 상태·결과가 저장돼도 알림 내용이 바뀌지 않는다.
        var snapshot = new TaskNotificationMessage.Payload(task.getId(), task.getTeamId(),
                task.getRequesterAgentId(), task.getReceiverAgentId(), task.getStatus(), task.getVersion(),
                task.getResultText(), task.isPartiallyModified(), task.getStartedAt(), task.getCompletedAt());
        return new TaskNotificationEvent(UUID.randomUUID().toString(), type, task.getRequesterUserId(), snapshot);
    }

    public long taskId() {
        return payload.taskId();
    }

    public TaskNotificationMessage message() {
        // 내부 권한 검증용 requesterUserId는 브로커에 보내는 JSON에서 제외한다.
        return new TaskNotificationMessage(type.name(), eventId, payload);
    }
}
