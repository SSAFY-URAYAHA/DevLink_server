package com.devlink.server.service;

import com.devlink.server.dto.TaskNotificationResult.Status;
import com.devlink.server.realtime.TaskNotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 상태 변경·완료 트랜잭션이 커밋된 경우에만 알림을 전달한다. 롤백·트랜잭션 밖의 이벤트는 무시한다. */
@Component
public class TaskNotificationEventListener {
    private static final Logger log = LoggerFactory.getLogger(TaskNotificationEventListener.class);
    private final TaskNotificationService notifications;
    private final TaskNotificationFailureRecorder failures;

    public TaskNotificationEventListener(TaskNotificationService notifications, TaskNotificationFailureRecorder failures) {
        this.notifications = notifications;
        this.failures = failures;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onNotification(TaskNotificationEvent event) {
        try {
            // 커밋 직후 남은 변경 트랜잭션과 분리해 네트워크 전송·실패 기록을 수행
            var result = notifications.sendNotification(event);
            if (result.status() != Status.SENT) {
                log.info("Task {} committed but notification {} returned {}",
                        event.taskId(), event.eventId(), result.status());
            }
        } catch (RuntimeException exception) {
            log.error("Task {} committed but notification {} failed", event.taskId(), event.eventId(), exception);
            try {
                failures.record(event, Status.INTERNAL_ERROR);
            } catch (RuntimeException recordingException) {
                log.error("Could not record failed notification {}", event.eventId(), recordingException);
            }
        }
    }
}
