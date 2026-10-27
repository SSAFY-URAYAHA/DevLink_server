package com.devlink.server.realtime;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 인증된 Agent별 현재 브로커 연결을 관리한다.
 * Agent의 ONLINE/OFFLINE 상태는 AGENT_PRESENCE 처리에서 별도로 관리한다.
 */
@Component
public class ConnectionRegistry {
    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);
    private static final CloseStatus CONNECTION_REPLACED =
            new CloseStatus(4002, "Connection replaced");

    private final ConcurrentMap<String, WebSocketSession> connections = new ConcurrentHashMap<>();

    /**
     * 인증 과정에서 확인한 Agent ID와 열린 연결을 등록한다.
     * 새 연결을 먼저 등록한 뒤 기존 연결을 종료하므로 이전 종료 콜백이 새 연결을 지울 수 없다.
     */
    public void register(String agentId, WebSocketSession session) {
        Assert.hasText(agentId, "agentId must not be blank");
        Assert.notNull(session, "session must not be null");
        Assert.isTrue(session.isOpen(), "session must be open");

        WebSocketSession previous = connections.put(agentId, session);
        if (previous != null && previous != session) {
            closeReplacedConnection(agentId, previous);
        }
    }

    /** 현재 열린 브로커 연결을 반환한다. Agent의 실제 접속 상태를 뜻하지는 않는다. */
    public Optional<WebSocketSession> findByAgentId(String agentId) {
        Assert.hasText(agentId, "agentId must not be blank");
        return Optional.ofNullable(connections.get(agentId)).filter(WebSocketSession::isOpen);
    }

    /** 이전 연결의 메시지가 새 연결에 영향을 주지 않도록 실제 세션 객체까지 비교한다. */
    public boolean isCurrentConnection(String agentId, WebSocketSession session) {
        Assert.hasText(agentId, "agentId must not be blank");
        Assert.notNull(session, "session must not be null");
        return connections.get(agentId) == session && session.isOpen();
    }

    /**
     * 종료된 연결이 현재 연결과 같을 때만 제거한다.
     * 교체된 이전 연결의 종료 이벤트라면 새 연결을 유지하고 false를 반환한다.
     */
    public boolean unregister(String agentId, WebSocketSession session) {
        Assert.hasText(agentId, "agentId must not be blank");
        Assert.notNull(session, "session must not be null");

        boolean[] removed = {false};
        connections.computeIfPresent(agentId, (key, current) -> {
            if (current == session) {
                removed[0] = true;
                return null;
            }
            return current;
        });
        return removed[0];
    }

    private void closeReplacedConnection(String agentId, WebSocketSession previous) {
        if (!previous.isOpen()) {
            return;
        }
        try {
            previous.close(CONNECTION_REPLACED);
        } catch (IOException exception) {
            // 종료에 실패해도 이전 연결을 다시 활성 연결로 복원하지 않는다.
            log.warn("Failed to close replaced broker connection for agent {}", agentId, exception);
        }
    }
}
