package com.devlink.server.service;

import java.time.LocalDateTime;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.socket.WebSocketSession;

@Service
public class AgentPresenceService {
    private final AgentRepository agents;
    private final ConnectionRegistry connections;

    public AgentPresenceService(AgentRepository agents, ConnectionRegistry connections) {
        this.agents = agents;
        this.connections = connections;
    }

    @Transactional
    public void updateStatus(AgentPrincipal principal, WebSocketSession session, AgentStatus status) {
        Assert.notNull(principal, "principal must not be null");
        Assert.notNull(session, "session must not be null");
        Assert.notNull(status, "status must not be null");
        Agent agent = agents.findByIdForPresenceUpdate(principal.agentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // DB 잠금을 기다리는 사이 연결이 교체될 수 있으므로 잠금 획득 후 다시 검사한다.
        if (!connections.isCurrentConnection(principal.getName(), session)) {
            return;
        }
        if (!agent.getOwnerUserId().equals(principal.ownerUserId())
                || !agent.getTeamId().equals(principal.teamId())) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        if (status == AgentStatus.ONLINE) {
            agent.markOnline(LocalDateTime.now());
        } else {
            // 마지막 온라인 확인 시각은 유지한다.
            agent.markOffline();
        }
    }

    @Transactional
    public void markOfflineIfDisconnected(long agentId) {
        agents.findByIdForPresenceUpdate(agentId).ifPresent(agent -> {
            // unregister 성공 직후 새 연결이 들어올 수도 있다. DB 잠금 후 재확인해
            // 새 연결의 ONLINE 갱신보다 늦게 옛 종료 처리가 OFFLINE을 덮어쓰지 않게 한다.
            if (connections.findByAgentId(Long.toString(agentId)).isEmpty()) {
                agent.markOffline();
            }
        });
    }
}
