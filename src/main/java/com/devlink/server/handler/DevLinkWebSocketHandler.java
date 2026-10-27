package com.devlink.server.handler;

import java.util.Map;
import com.devlink.server.common.BusinessException;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.service.AgentPresenceService;
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

/** 브로커의 연결 수명과 AGENT_PRESENCE 보고를 처리한다. */
@Component
public class DevLinkWebSocketHandler extends TextWebSocketHandler {
    private final ConnectionRegistry connectionRegistry;
    private final ObjectMapper objectMapper;
    private final AgentPresenceService agentPresenceService;

    public DevLinkWebSocketHandler(ConnectionRegistry connectionRegistry, ObjectMapper objectMapper,
            AgentPresenceService agentPresenceService) {
        this.connectionRegistry = connectionRegistry;
        this.objectMapper = objectMapper;
        this.agentPresenceService = agentPresenceService;
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
            connectionRegistry.register(agentId, session);
            if (!connectionRegistry.isCurrentConnection(agentId, session)) {
                return;
            }
            var ready = Map.of(
                    "type", "BROKER_READY",
                    "payload", Map.of("connection_id", session.getId(), "agent_id", agent.agentId()));
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ready)));
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

        AgentStatus status;
        try {
            status = readPresenceStatus(message.getPayload());
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

    /** 정상·비정상 종료 모두 현재 연결을 제거하고 해당 Agent를 오프라인 처리한다. */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // 연결 중 인증이 비활성화됐더라도 기존 인증 신원으로 종료 처리는 수행
        if (session.getPrincipal() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AgentPrincipal agent) {
            unregisterAndMarkOffline(agent, session);
        }
    }

    private void unregisterAndMarkOffline(AgentPrincipal agent, WebSocketSession session) {
        if (connectionRegistry.unregister(agent.getName(), session)) {
            agentPresenceService.markOfflineIfDisconnected(agent.agentId());
        }
    }

    private AgentStatus readPresenceStatus(String payload) throws JsonProcessingException {
        JsonNode event = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(payload);
        if (event == null || !event.isObject() || event.size() != 2
                || !event.path("type").isTextual()
                || !"AGENT_PRESENCE".equals(event.path("type").textValue())
                || !event.path("payload").isObject() || event.path("payload").size() != 1
                || !event.path("payload").path("status").isTextual()) {
            throw new IllegalArgumentException("Invalid AGENT_PRESENCE message");
        }
        return AgentStatus.valueOf(event.path("payload").path("status").textValue());
    }
}
