package com.devlink.server.service;

import com.devlink.server.dto.TaskRoutingResult.Status;
import com.devlink.server.realtime.TaskCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 생성 로직과 전달 로직을 연결하되 커밋에 성공한 작업만 전송한다. */
@Component
public class TaskRoutingEventListener {
    private static final Logger log = LoggerFactory.getLogger(TaskRoutingEventListener.class);
    private final TaskRoutingService routing;

    public TaskRoutingEventListener(TaskRoutingService routing) {
        this.routing = routing;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onTaskCreated(TaskCreatedEvent event) {
        // 커밋 직후 남아 있는 생성 트랜잭션을 분리해 조회·OFFLINE 갱신이 독립적으로 수행되게 한다.
        try {
            var result = routing.sendTask(event.taskId());
            if (result.status() != Status.SENT) {
                log.info("Task {} was committed but routing returned {}", event.taskId(), result.status());
            }
        } catch (RuntimeException exception) {
            // 전달 실패로 이미 커밋된 작업 생성이 실패한 것처럼 보이게 하지 않는다.
            log.error("Task {} was committed but could not be routed", event.taskId(), exception);
        }
    }
}
