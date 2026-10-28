package com.devlink.server.service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.TaskAckResult;
import com.devlink.server.dto.TaskAckResult.Status;
import com.devlink.server.realtime.ConnectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.socket.WebSocketSession;

/** 전송 시도별 ACK 대기를 메모리에 관리한다. 작업 상태·전달 이력의 DB 저장은 여기서 하지 않는다. */
@Service
public class TaskAckService {
    private static final Logger log = LoggerFactory.getLogger(TaskAckService.class);
    private final ConcurrentMap<String, PendingAck> pending = new ConcurrentHashMap<>();
    private final ConnectionRegistry connections;
    private final ApplicationEventPublisher events;
    private final long timeoutNanos;
    private final LongSupplier nanoTime;

    @Autowired
    public TaskAckService(ConnectionRegistry connections, ApplicationEventPublisher events,
            @Value("${devlink.broker.ack-timeout-ms:5000}") long timeoutMillis) {
        this(connections, events, timeoutMillis, System::nanoTime);
    }

    TaskAckService(ConnectionRegistry connections, ApplicationEventPublisher events,
            long timeoutMillis, LongSupplier nanoTime) {
        Assert.isTrue(timeoutMillis > 0, "ack timeout must be positive");
        this.connections = connections;
        this.events = events;
        this.timeoutNanos = Duration.ofMillis(timeoutMillis).toNanos();
        this.nanoTime = nanoTime;
    }

    /** 소켓 쓰기 직전에 등록해 즉시 돌아오는 ACK를 놓치지 않고, 실제 전송 시작부터 시간을 잰다. */
    public CompletionStage<TaskAckResult> register(long taskId, String eventId,
            AgentPrincipal receiver, WebSocketSession session) {
        Assert.isTrue(taskId > 0, "taskId must be positive");
        Assert.hasText(eventId, "eventId must not be blank");
        Assert.notNull(receiver, "receiver must not be null");
        Assert.notNull(session, "session must not be null");
        PendingAck waiting = new PendingAck(taskId, eventId, receiver, session, nanoTime.getAsLong());
        if (pending.putIfAbsent(eventId, waiting) != null) {
            throw new IllegalStateException("ACK already registered for event " + eventId);
        }
        return waiting.result.minimalCompletionStage();
    }

    /** 다른 Agent·작업·이전 연결이 보낸 ACK는 올바른 대기에 영향을 주지 않고 무시한다. */
    public boolean acknowledge(AgentPrincipal receiver, WebSocketSession session, long taskId, String eventId) {
        PendingAck waiting = pending.get(eventId);
        if (waiting == null || waiting.taskId != taskId || !waiting.receiver.equals(receiver)
                || waiting.session != session) {
            return false;
        }
        Status[] status = {Status.ACKNOWLEDGED};
        boolean claimed = connections.executeIfCurrent(receiver.getName(), session, () -> {
            if (isExpired(waiting)) {
                status[0] = Status.TIMED_OUT;
            }
            return pending.remove(eventId, waiting);
        });
        if (!claimed) {
            return false;
        }
        complete(waiting, status[0]);
        return status[0] == Status.ACKNOWLEDGED;
    }

    /** 전송 실패 시 즉시 대기를 해제한다. 먼저 확정된 ACK 성공 결과는 덮어쓰지 않는다. */
    public void sendFailed(String eventId, WebSocketSession session) {
        PendingAck waiting = pending.get(eventId);
        if (waiting != null && waiting.session == session) {
            finish(waiting, Status.SEND_FAILED);
        }
    }

    /** 종료를 감지한 연결의 대기만 해제하며, 재접속한 새 연결의 대기는 유지한다. */
    public void connectionClosed(WebSocketSession session) {
        pending.forEach((eventId, waiting) -> {
            if (waiting.session == session) {
                finish(waiting, Status.CONNECTION_CLOSED);
            }
        });
    }

    @Scheduled(fixedDelayString = "${devlink.broker.ack-check-interval-ms:100}")
    public void checkTimeouts() {
        pending.forEach((eventId, waiting) -> {
            if (!connections.isCurrentConnection(waiting.receiver.getName(), waiting.session)) {
                finish(waiting, Status.CONNECTION_CLOSED);
            } else if (isExpired(waiting)) {
                finish(waiting, Status.TIMED_OUT);
            }
        });
    }

    private boolean isExpired(PendingAck waiting) {
        return nanoTime.getAsLong() - waiting.startedAt >= timeoutNanos;
    }

    private boolean finish(PendingAck waiting, Status status) {
        if (!pending.remove(waiting.eventId, waiting)) {
            return false;
        }
        complete(waiting, status);
        return true;
    }

    private void complete(PendingAck waiting, Status status) {
        var result = new TaskAckResult(waiting.taskId, waiting.eventId, waiting.session.getId(), status);
        waiting.result.complete(result);
        try {
            events.publishEvent(result);
        } catch (RuntimeException exception) {
            log.error("Failed to publish ACK result for event {}", waiting.eventId, exception);
        }
    }

    private static final class PendingAck {
        private final long taskId;
        private final String eventId;
        private final AgentPrincipal receiver;
        private final WebSocketSession session;
        private final long startedAt;
        private final CompletableFuture<TaskAckResult> result = new CompletableFuture<>();

        private PendingAck(long taskId, String eventId, AgentPrincipal receiver,
                WebSocketSession session, long startedAt) {
            this.taskId = taskId;
            this.eventId = eventId;
            this.receiver = receiver;
            this.session = session;
            this.startedAt = startedAt;
        }
    }
}
