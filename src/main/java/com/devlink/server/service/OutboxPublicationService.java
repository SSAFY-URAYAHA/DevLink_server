package com.devlink.server.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import com.devlink.server.config.OutboxPublisherSettings;
import com.devlink.server.domain.outbox.OutboxStatus;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.dto.OutboxDelivery;
import com.devlink.server.repository.OutboxEventRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 점유·성공·실패 기록만 짧은 트랜잭션으로 처리한다. 네트워크 전송은 이 Bean 밖에서 수행한다. */
@Service
public class OutboxPublicationService {
    private final OutboxEventRepository outbox;
    private final OutboxPublisherSettings settings;
    private final Clock clock;

    public OutboxPublicationService(OutboxEventRepository outbox, OutboxPublisherSettings settings,
            ObjectProvider<Clock> clocks) {
        this.outbox = outbox;
        this.settings = settings;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OutboxDelivery> claimNext() {
        Instant now = now();
        for (int index = 0; index < settings.batchSize(); index++) {
            var candidates = outbox.findNextTaskNotification(now);
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            var event = candidates.getFirst();
            if (event.getAttemptCount() >= settings.maxAttempts()) {
                // 마지막 시도 중 서버가 종료된 경우도 점유 만료 후 무한 재시도하지 않는다.
                event.publicationFailed("ATTEMPT_LIMIT_AFTER_LEASE_EXPIRY");
                outbox.flush();
                continue;
            }
            event.claim(UUID.randomUUID(), now, now.plusMillis(settings.leaseMillis()));
            return Optional.of(OutboxDelivery.from(event));
        }
        return Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renewBeforeSend(UUID outboxId, UUID lockToken) {
        var owned = owned(outboxId, lockToken);
        if (owned.isEmpty()) {
            return false;
        }
        owned.get().renewLease(now().plusMillis(settings.leaseMillis()));
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean published(UUID outboxId, UUID lockToken) {
        var owned = owned(outboxId, lockToken);
        if (owned.isEmpty()) {
            return false;
        }
        owned.get().published(now());
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failed(UUID outboxId, UUID lockToken, String reason, boolean permanent) {
        var owned = owned(outboxId, lockToken);
        if (owned.isEmpty()) {
            return false;
        }
        var event = owned.get();
        if (permanent || event.getAttemptCount() >= settings.maxAttempts()) {
            event.publicationFailed(reason);
        } else {
            event.retryAt(now().plusMillis(settings.retryDelayMillis(event.getAttemptCount())), reason);
        }
        return true;
    }

    private Optional<OutboxEvent> owned(UUID outboxId, UUID lockToken) {
        // 상태·토큰을 행 잠금과 함께 확인하므로 이전 점유자가 새 점유자의 결과를 덮어쓸 수 없다.
        return outbox.findByOutboxIdAndLockTokenAndStatus(outboxId, lockToken, OutboxStatus.PROCESSING)
                .filter(event -> event.ownsLease(lockToken, now()));
    }

    private Instant now() {
        // PostgreSQL의 마이크로초 정밀도에 맞춰 조회 조건과 메모리의 만료 판단을 일치시킨다.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
