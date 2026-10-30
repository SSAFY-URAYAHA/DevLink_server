package com.devlink.server.handler;

import java.util.Map;
import com.devlink.server.common.BusinessException;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.realtime.BrokerMessageSender;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.service.AgentPresenceService;
import com.devlink.server.service.TaskAckService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** 브로커의 연결 수명, AGENT_PRESENCE 보고, JSON 하트비트와 ACK를 처리한다. */
@Component
public class DevLinkWebSocketHandler extends TextWebSocketHandler {
    private final ConnectionRegistry connectionRegistry;
    private final ObjectMapper objectMapper;
    private final AgentPresenceService agentPresenceService;
    private final BrokerMessageSender sender;
    private final TaskAckService acknowledgements;

    public DevLinkWebSocketHandler(ConnectionRegistry connectionRegistry, ObjectMapper objectMapper,
            AgentPresenceService agentPresenceService, BrokerMessageSender sender, TaskAckService acknowledgements) {
        this.connectionRegistry = connectionRegistry;
        this.objectMapper = objectMapper;
        this.agentPresenceService = agentPresenceService;
        this.sender = sender;
        this.acknowledgements = acknowledgements;
    }

    /** 인증 필터를 통과하고 WebSocket 연결이 성립하면 Spring이 호출한다. */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 인증된 Agent 신원이 전달됐는지 확인
        if (!(session.getPrincipal() instanceof Authentication authentication)
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AgentPrincipal agent)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        String agentId = agent.getName();
        try {
            // 등록 직후 작업 전송이 시작돼도 BROKER_READY가 먼저 나가도록 같은 소켓 잠금을 사용
            synchronized (session) {
                connectionRegistry.register(agentId, session);
                // 브로커가 로컬 신원을 구성할 수 있도록 토큰 검증을 마친 사용자·팀 ID를 함께 전달
                // 클라이언트가 보낸 ID 대신 인증된 AgentPrincipal의 값을 사용
                var ready = Map.of(
                        "type", "BROKER_READY",
                        "payload", Map.of(
                                "connection_id", session.getId(),
                                "agent_id", agent.agentId(),
                                "user_id", agent.ownerUserId(),
                                "team_id", agent.teamId()));
                sender.sendIfCurrent(agentId, session, ready);
            }
        } catch (Exception exception) {
            // 정리 중 DB 오류가 발생해도 최초 오류를 보존하고 소켓 종료까지 시도
            try {
                unregisterAndMarkOffline(agent, session);
            } catch (Exception cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.SERVER_ERROR);
                }
            } catch (Exception closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!(session.getPrincipal() instanceof Authentication authentication)
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AgentPrincipal agent)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (!connectionRegistry.isCurrentConnection(agent.getName(), session)) {
            return;
        }

        JsonNode event;
        AgentStatus status;
        try {
            event = readEvent(message.getPayload());
            if ("HEARTBEAT".equals(event.path("type").textValue())) {
                handleHeartbeat(agent, session, event.path("payload"));
                return;
            }
            if ("ACK".equals(event.path("type").textValue())) {
                handleAck(agent, session, event.path("payload"));
                return;
            }
            status = readPresenceStatus(event);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            agentPresenceService.updateStatus(agent, session, status);
        } catch (BusinessException exception) {
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }

    /** 전송 계층에서 단절을 먼저 발견하면 하트비트 시간 초과를 기다리지 않는다. */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.SERVER_ERROR);
            }
        } finally {
            // 종료 콜백이 늦거나 소켓 종료에 실패해도 현재 연결의 등록을 정리한다.
            afterConnectionClosed(session, CloseStatus.SERVER_ERROR);
        }
    }

    /** 정상·비정상 종료 모두 현재 연결을 제거하고 해당 Agent를 오프라인 처리한다. */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // 교체된 옛 연결의 종료여도 그 연결의 ACK 대기는 해제
        acknowledgements.connectionClosed(session);
        // 연결 중 인증이 비활성화됐더라도 기존 인증 신원으로 종료 처리는 수행
        if (session.getPrincipal() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AgentPrincipal agent) {
            unregisterAndMarkOffline(agent, session);
        }
    }

    private void handleAck(AgentPrincipal agent, WebSocketSession session, JsonNode payload) {
        if (payload.size() != 2 || !payload.path("event_id").isTextual()
                || payload.path("event_id").textValue().isBlank()
                || payload.path("event_id").textValue().length() > 128
                || !payload.path("task_id").isIntegralNumber()
                || !payload.path("task_id").canConvertToLong()
                || payload.path("task_id").longValue() <= 0) {
            throw new IllegalArgumentException("Invalid ACK message");
        }
        // 형식이 맞더라도 알 수 없는 ACK·중복 ACK·늦은 ACK는 무시하고 연결은 유지
        acknowledgements.acknowledge(agent, session,
                payload.path("task_id").longValue(), payload.path("event_id").textValue());
    }

    private void unregisterAndMarkOffline(AgentPrincipal agent, WebSocketSession session) {
        if (connectionRegistry.unregister(agent.getName(), session)) {
            agentPresenceService.markOfflineIfDisconnected(agent.agentId());
        }
    }

    private JsonNode readEvent(String payload) throws JsonProcessingException {
        JsonNode event = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(payload);
        if (event == null || !event.isObject() || event.size() != 2
                || !event.path("type").isTextual()
                || !event.path("payload").isObject()) {
            throw new IllegalArgumentException("Invalid broker message");
        }
        return event;
    }

    private AgentStatus readPresenceStatus(JsonNode event) {
        if (!"AGENT_PRESENCE".equals(event.path("type").textValue())
                || event.path("payload").size() != 1
                || !event.path("payload").path("status").isTextual()) {
            throw new IllegalArgumentException("Invalid AGENT_PRESENCE message");
        }
        return AgentStatus.valueOf(event.path("payload").path("status").textValue());
    }

    private void handleHeartbeat(AgentPrincipal agent, WebSocketSession session, JsonNode payload)
            throws Exception {
        // ping_id를 그대로 돌려줌
        if (payload.size() != 2 || !"PING".equals(payload.path("kind").textValue())
                || !payload.path("ping_id").isTextual()
                || payload.path("ping_id").textValue().isBlank()
                || payload.path("ping_id").textValue().length() > 128) {
            throw new IllegalArgumentException("Invalid HEARTBEAT PING");
        }
        if (!connectionRegistry.recordHeartbeat(agent.getName(), session)) {
            return;
        }
        var pong = Map.of("type", "HEARTBEAT", "payload",
                Map.of("kind", "PONG", "ping_id", payload.path("ping_id").textValue()));
        try {
            // 작업 메시지와 PONG이 겹쳐도 공통 전송기가 소켓 쓰기를 순서대로 처리
            sender.sendIfCurrent(agent.getName(), session, pong);
        } catch (Exception exception) {
            // PONG 전송 실패로 단절을 발견한 경우에도 즉시 연결 정리를 시도
            try {
                handleTransportError(session, exception);
            } catch (Exception cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }
}
