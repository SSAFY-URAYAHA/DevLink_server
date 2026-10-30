package com.devlink.server.service;

import com.devlink.server.realtime.AgentCredentialsChangedEvent;
import com.devlink.server.realtime.ConnectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;

@Component
public class AgentCredentialsChangedListener {
    private static final Logger log = LoggerFactory.getLogger(AgentCredentialsChangedListener.class);
    private final ConnectionRegistry connections;
    private final TaskAckService acknowledgements;
    private final AgentPresenceService presence;

    public AgentCredentialsChangedListener(ConnectionRegistry connections,
            TaskAckService acknowledgements, AgentPresenceService presence) {
        this.connections = connections;
        this.acknowledgements = acknowledgements;
        this.presence = presence;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void disconnect(AgentCredentialsChangedEvent event) {
        String agentId = Long.toString(event.agentId());
        connections.findByAgentId(agentId).ifPresent(session -> {
            connections.unregister(agentId, session);
            try {
                acknowledgements.connectionClosed(session);
            } catch (RuntimeException exception) {
                log.error("Failed to clear acknowledgements for agent {}", event.agentId(), exception);
            }
            try {
                session.close(new CloseStatus(4003, "Agent credentials changed"));
            } catch (Exception exception) {
                log.warn("Failed to close connection for agent {}", event.agentId(), exception);
            }
        });
        try {
            presence.markOfflineIfDisconnected(event.agentId());
        } catch (RuntimeException exception) {
            log.error("Failed to update presence for agent {}", event.agentId(), exception);
        }
    }
}
