package com.devlink.server.service;

import java.time.Duration;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.realtime.ConnectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.socket.CloseStatus;

/** 하트비트가 끊긴 브로커를 정리한다. 재접속 시도와 대기 시간 조절은 브로커가 담당한다. */
@Service
public class BrokerHeartbeatService {
    private static final Logger log = LoggerFactory.getLogger(BrokerHeartbeatService.class);
    private static final CloseStatus HEARTBEAT_TIMEOUT = new CloseStatus(4001, "Heartbeat timeout");

    private final ConnectionRegistry connections;
    private final AgentPresenceService presence;
    private final Duration timeout;

    public BrokerHeartbeatService(ConnectionRegistry connections, AgentPresenceService presence,
            @Value("${devlink.broker.heartbeat-timeout-ms:40000}") long timeoutMillis) {
        Assert.isTrue(timeoutMillis > 0, "heartbeat timeout must be positive");
        this.connections = connections;
        this.presence = presence;
        this.timeout = Duration.ofMillis(timeoutMillis);
    }

    /** 기본 1초 간격으로 검사하므로 40초 무수신 이후 다음 검사에서 정리한다. */
    @Scheduled(fixedDelayString = "${devlink.broker.heartbeat-check-interval-ms:1000}")
    public void checkTimeouts() {
        for (var expired : connections.removeExpiredConnections(timeout)) {
            var session = expired.session();
            try {
                if (session.isOpen()) {
                    session.close(HEARTBEAT_TIMEOUT);
                }
            } catch (Exception exception) {
                // 한 소켓의 종료 실패가 다른 브로커의 시간 초과 정리를 막지 않게 한다.
                log.warn("Failed to close timed out broker for agent {}", expired.agentId(), exception);
            }
            try {
                if (session.getPrincipal() instanceof Authentication authentication
                        && authentication.getPrincipal() instanceof AgentPrincipal agent) {
                    // Registry에서는 이미 제거했다. DB 잠금 후 새 연결 유무를 다시 확인해
                    // 정리 도중 재접속한 Agent의 상태를 OFFLINE으로 덮어쓰지 않는다.
                    presence.markOfflineIfDisconnected(agent.agentId());
                }
            } catch (Exception exception) {
                log.error("Failed to mark disconnected agent {} offline", expired.agentId(), exception);
            }
        }
    }
}
