package com.devlink.server.repository;

import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.outbox.OutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    // 짧은 점유 트랜잭션 안에서만 사용한다. 다른 발행기가 잡은 행은 기다리지 않고 건너뛴다.
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE aggregate_type = 'TASK' AND destination = 'task-events'
              AND event_type IN ('TASK_STATUS_CHANGED', 'TASK_COMPLETED')
              AND ((status = 'PENDING' AND available_at <= :now)
                OR (status = 'PROCESSING' AND locked_until <= :now))
            ORDER BY created_at, outbox_id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> findNextTaskNotification(@Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<OutboxEvent> findByOutboxIdAndLockTokenAndStatus(UUID outboxId, UUID lockToken, OutboxStatus status);
}
