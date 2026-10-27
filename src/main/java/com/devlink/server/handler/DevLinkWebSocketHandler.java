package com.devlink.server.handler;

import java.util.Map;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.realtime.ConnectionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 브로커의 WebSocket 연결 성공과 종료를 처리한다.
 * 연결 성공: 인증된 Agent 확인 → Registry 등록 → BROKER_READY 전송.
 * 연결 종료: 종료된 세션이 현재 연결일 때만 Registry에서 제거.
 * 여기서 관리하는 것은 서버와 브로커의 연결이며, Agent의 ONLINE/OFFLINE 상태는 별도 처리한다.
 */
@Component
public class DevLinkWebSocketHandler extends TextWebSocketHandler {
    private final ConnectionRegistry connectionRegistry;
    private final ObjectMapper objectMapper;

    public DevLinkWebSocketHandler(ConnectionRegistry connectionRegistry, ObjectMapper objectMapper) {
        this.connectionRegistry = connectionRegistry;
        this.objectMapper = objectMapper;
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
            // 브로커에 전달할 최소 응답을 구성
            var ready = Map.of(
                    "type", "BROKER_READY",
                    "payload", Map.of("connection_id", session.getId(), "agent_id", agent.agentId()));
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ready)));
        } catch (Exception exception) {
            // 등록 또는 READY 전송 중 실패하면 사용할 수 없는 연결 정보가 남지 않게 정리
            connectionRegistry.unregister(agentId, session);
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

    /** 연결 종료가 감지되면 Spring이 호출한다. 정상 종료와 비정상 종료 모두 정리 대상이다. */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (session.getPrincipal() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AgentPrincipal agent) {
            connectionRegistry.unregister(agent.getName(), session);
        }
    }
}
