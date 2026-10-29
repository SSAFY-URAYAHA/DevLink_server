package com.devlink.server.service;

import java.io.IOException;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.task.Task;
import com.devlink.server.dto.TaskNotificationResult;
import com.devlink.server.dto.TaskNotificationResult.Status;
import com.devlink.server.realtime.BrokerMessageSender;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.realtime.TaskNotificationEvent;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** 커밋된 상태·결과 스냅샷을 작업 요청자의 브로커에 알린다. 새 작업 수신용 ACK는 등록하지 않는다. */
@Service
public class TaskNotificationService {
    private static final Logger log = LoggerFactory.getLogger(TaskNotificationService.class);
    private final TaskRepository tasks;
    private final AgentRepository agents;
    private final ConnectionRegistry connections;
    private final BrokerMessageSender sender;
    private final AgentPresenceService presence;
    private final TaskNotificationFailureRecorder failures;

    public TaskNotificationService(TaskRepository tasks, AgentRepository agents, ConnectionRegistry connections,
            BrokerMessageSender sender, AgentPresenceService presence, TaskNotificationFailureRecorder failures) {
        this.tasks = tasks;
        this.agents = agents;
        this.connections = connections;
        this.sender = sender;
        this.presence = presence;
        this.failures = failures;
    }

    // 변경 트랜잭션 안에서 직접 보내면 롤백될 알림이 유출되므로, 커밋 후 호출만 허용한다.
    @Transactional(propagation = Propagation.NEVER)
    public TaskNotificationResult sendNotification(TaskNotificationEvent event) {
        var current = tasks.findById(event.taskId());
        if (current.isEmpty() || !matchesTask(event, current.get())) {
            return failed(event, Status.INVALID_EVENT);
        }
        var requester = agents.findById(event.payload().requesterAgentId());
        if (requester.isEmpty() || requester.get().getTeamId() != event.payload().teamId()
                || requester.get().getOwnerUserId() != event.requesterUserId()) {
            return failed(event, Status.REQUESTER_UNAVAILABLE);
        }
        String agentId = requester.get().getId().toString();
        var connection = connections.findByAgentId(agentId);
        if (connection.isEmpty()) {
            return failed(event, Status.CONNECTION_UNAVAILABLE);
        }
        WebSocketSession session = connection.get();
        if (!matchesRequester(session, requester.get())) {
            return failed(event, Status.REQUESTER_UNAVAILABLE);
        }
        try {
            // Agent의 MCP ONLINE 상태와 별개로 연결된 브로커에는 알림을 전달할 수 있다.
            // 기존 공통 전송기를 사용하되 새 작업용 ACK 대기 등록 콜백은 사용하지 않는다.
            if (!sender.sendIfCurrent(agentId, session, event.message())) {
                return failed(event, Status.CONNECTION_CHANGED);
            }
            return new TaskNotificationResult(event.taskId(), event.eventId(), Status.SENT);
        } catch (IOException | IllegalStateException exception) {
            log.warn("Failed to notify task {} event {}", event.taskId(), event.eventId(), exception);
            cleanUpConnection(agentId, requester.get().getId(), session);
            return failed(event, Status.TRANSPORT_ERROR);
        }
    }

    private boolean matchesTask(TaskNotificationEvent event, Task current) {
        var snapshot = event.payload();
        // 최신 상태를 본문에 다시 넣지 않고, 저장된 작업의 수신 경로와 버전 범위만 확인한다.
        // 연달아 변경된 작업도 각 이벤트는 자신의 불변 스냅샷을 전달하며 클라이언트는 version으로 구분한다.
        return current.getTeamId() == snapshot.teamId()
                && current.getRequesterAgentId() == snapshot.requesterAgentId()
                && current.getRequesterUserId() == event.requesterUserId()
                && current.getReceiverAgentId() == snapshot.receiverAgentId()
                && current.getVersion() >= snapshot.version()
                && (current.getVersion() != snapshot.version() || current.getStatus() == snapshot.status());
    }

    private boolean matchesRequester(WebSocketSession session, Agent requester) {
        return session.getPrincipal() instanceof Authentication authentication
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AgentPrincipal principal
                && principal.agentId() == requester.getId()
                && principal.ownerUserId() == requester.getOwnerUserId()
                && principal.teamId() == requester.getTeamId();
    }

    private TaskNotificationResult failed(TaskNotificationEvent event, Status reason) {
        try {
            failures.record(event, reason);
        } catch (RuntimeException exception) {
            // 실패 이력 저장까지 실패해도 이미 커밋된 상태·완료 결과를 실패로 되돌리지 않는다.
            log.error("Failed to record notification failure for task {} event {}", event.taskId(), event.eventId(), exception);
        }
        return new TaskNotificationResult(event.taskId(), event.eventId(), reason);
    }

    private void cleanUpConnection(String agentId, long requesterId, WebSocketSession session) {
        try {
            if (connections.unregister(agentId, session)) {
                presence.markOfflineIfDisconnected(requesterId);
            }
        } catch (RuntimeException exception) {
            log.error("Failed to mark disconnected requester {} offline", agentId, exception);
        }
        try {
            // 전송 실패 도중 새 연결이 생겨도 옛 소켓만 닫는다.
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("Failed to close requester connection {}", agentId, exception);
        }
    }
}
