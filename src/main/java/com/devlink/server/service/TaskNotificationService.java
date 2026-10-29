package com.devlink.server.service;

import java.io.IOException;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.dto.OutboxDelivery;
import com.devlink.server.dto.TaskNotificationResult;
import com.devlink.server.dto.TaskNotificationResult.Status;
import com.devlink.server.realtime.BrokerMessageSender;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** 저장된 Outbox JSON을 요청자에게 전송한다. 현재 Task의 상태·결과로 메시지를 다시 만들지 않는다. */
@Service
public class TaskNotificationService {
    private static final Logger log = LoggerFactory.getLogger(TaskNotificationService.class);
    private final TaskRepository tasks;
    private final AgentRepository agents;
    private final ConnectionRegistry connections;
    private final BrokerMessageSender sender;
    private final AgentPresenceService presence;

    public TaskNotificationService(TaskRepository tasks, AgentRepository agents, ConnectionRegistry connections,
            BrokerMessageSender sender, AgentPresenceService presence) {
        this.tasks = tasks;
        this.agents = agents;
        this.connections = connections;
        this.sender = sender;
        this.presence = presence;
    }

    @Transactional(propagation = Propagation.NEVER)
    public TaskNotificationResult sendNotification(OutboxDelivery delivery, Runnable beforeSend) {
        JsonNode message = delivery.message();
        long taskId = message.path("task_id").asLong();
        if (!validEnvelope(delivery, message)) {
            return result(delivery, taskId, Status.INVALID_EVENT);
        }
        var current = tasks.findById(taskId);
        if (current.isEmpty() || current.get().getVersion() < message.path("version").longValue()) {
            return result(delivery, taskId, Status.INVALID_EVENT);
        }
        var task = current.get();
        // 작업 조회는 라우팅·권한 확인에만 사용한다. 예전 이벤트의 본문과 version은 그대로 보낸다.
        var requester = agents.findById(task.getRequesterAgentId());
        if (requester.isEmpty() || !requester.get().getTeamId().equals(task.getTeamId())
                || !requester.get().getOwnerUserId().equals(task.getRequesterUserId())) {
            return result(delivery, taskId, Status.REQUESTER_UNAVAILABLE);
        }
        String agentId = requester.get().getId().toString();
        var connection = connections.findByAgentId(agentId);
        if (connection.isEmpty()) {
            return result(delivery, taskId, Status.CONNECTION_UNAVAILABLE);
        }
        WebSocketSession session = connection.get();
        if (!matchesRequester(session, requester.get())) {
            return result(delivery, taskId, Status.REQUESTER_UNAVAILABLE);
        }
        try {
            // JSON 문자열을 다시 감싸지 않고 저장된 JsonNode 전체를 공통 전송기로 전달한다.
            // 실제 쓰기 직전 짧은 점유 연장 트랜잭션을 끝내고, 네트워크 전송에는 DB 잠금을 유지하지 않는다.
            boolean sent = sender.sendIfCurrent(agentId, session, message, beforeSend);
            return result(delivery, taskId, sent ? Status.SENT : Status.CONNECTION_CHANGED);
        } catch (IOException | IllegalStateException exception) {
            log.warn("Failed to publish notification outbox {}", delivery.outboxId(), exception);
            cleanUpConnection(agentId, requester.get().getId(), session);
            return result(delivery, taskId, Status.TRANSPORT_ERROR);
        }
    }

    private boolean validEnvelope(OutboxDelivery delivery, JsonNode message) {
        if (!"TASK".equals(delivery.aggregateType()) || !"task-events".equals(delivery.destination())
                || !message.isObject() || !delivery.outboxId().toString().equals(message.path("event_id").asText())
                || !delivery.eventType().equals(message.path("type").asText())
                || !message.path("task_id").isIntegralNumber() || !message.path("task_id").canConvertToLong()
                || message.path("task_id").longValue() <= 0
                || !message.path("version").isIntegralNumber() || !message.path("version").canConvertToLong()
                || message.path("version").longValue() < 0 || !message.path("payload").isObject()) {
            return false;
        }
        String taskId = message.path("task_id").asText();
        if (!taskId.equals(delivery.aggregateId()) || !("TASK:" + taskId).equals(delivery.messageKey())) {
            return false;
        }
        String status = message.path("payload").path("status").asText();
        if ("TASK_STATUS_CHANGED".equals(delivery.eventType())) {
            String previous = message.path("payload").path("previous_status").asText();
            return ("IN_PROGRESS".equals(status) || "REJECTED".equals(status)) && "PENDING".equals(previous)
                    || "FAILED".equals(status) && "IN_PROGRESS".equals(previous);
        }
        return "TASK_COMPLETED".equals(delivery.eventType()) && TaskStatus.COMPLETED.name().equals(status);
    }

    private boolean matchesRequester(WebSocketSession session, Agent requester) {
        return session.getPrincipal() instanceof Authentication authentication
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AgentPrincipal principal
                && principal.agentId() == requester.getId()
                && principal.ownerUserId() == requester.getOwnerUserId()
                && principal.teamId() == requester.getTeamId();
    }

    private TaskNotificationResult result(OutboxDelivery delivery, long taskId, Status status) {
        return new TaskNotificationResult(taskId, delivery.outboxId().toString(), status);
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
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } catch (IOException | RuntimeException exception) {
            log.warn("Failed to close requester connection {}", agentId, exception);
        }
    }
}
