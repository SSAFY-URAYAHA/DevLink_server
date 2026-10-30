package com.devlink.server.handler;

import static com.devlink.server.AckTestDependencies.*;

import java.io.IOException;
import java.util.List;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.service.AgentPresenceService;
import com.devlink.server.service.TaskAckService;
import com.devlink.server.dto.TaskAckResult;
import org.springframework.context.ApplicationEventPublisher;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.realtime.BrokerMessageSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

class DevLinkWebSocketHandlerTest {
    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentPresenceService presence = mock(AgentPresenceService.class);
    private final TaskAckService acknowledgements = new TaskAckService(
            registry, mock(ApplicationEventPublisher.class), tasks(), history(), transactionManager(), 5000);
    private final DevLinkWebSocketHandler handler = new DevLinkWebSocketHandler(
            registry, mapper, presence, new BrokerMessageSender(registry, mapper), acknowledgements);

    @Test
    void validAckUsesAuthenticatedSessionAndDoesNotChangePresenceOrReply() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);
        var result = acknowledgements.register(7, "event-1", new AgentPrincipal(11, 21, 31), session)
                .toCompletableFuture();

        handler.handleMessage(session, new TextMessage(
                "{\"type\":\"ACK\",\"payload\":{\"event_id\":\"event-1\",\"task_id\":7}}"));

        assertThat(result.join().status()).isEqualTo(TaskAckResult.Status.ACKNOWLEDGED);
        verify(session, times(1)).sendMessage(any(TextMessage.class));
        verifyNoInteractions(presence);
    }

    @Test
    void unknownAndDuplicateAcksDoNotCloseConnection() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);
        acknowledgements.register(7, "event-1", new AgentPrincipal(11, 21, 31), session);
        for (String eventId : List.of("event-1", "event-1", "unknown")) {
            handler.handleMessage(session, new TextMessage(
                    "{\"type\":\"ACK\",\"payload\":{\"event_id\":\"" + eventId + "\",\"task_id\":7}}"));
        }

        verify(session, never()).close(any(CloseStatus.class));
        verifyNoInteractions(presence);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"event_id\":\"e\"}", "{\"event_id\":1,\"task_id\":7}",
            "{\"event_id\":\" \",\"task_id\":7}", "{\"event_id\":\"e\",\"task_id\":0}",
            "{\"event_id\":\"e\",\"task_id\":\"7\"}", "{\"event_id\":\"e\",\"task_id\":7.5}",
            "{\"event_id\":\"e\",\"task_id\":9223372036854775808}",
            "{\"event_id\":\"e\",\"task_id\":7,\"agent_id\":99}"
    })
    void malformedAckIsRejected(String payload) throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage("{\"type\":\"ACK\",\"payload\":" + payload + "}"));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(presence);
    }

    @Test
    void closingConnectionReleasesItsAckWaitImmediately() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);
        var result = acknowledgements.register(7, "event-1", new AgentPrincipal(11, 21, 31), session)
                .toCompletableFuture();

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(result.join().status()).isEqualTo(TaskAckResult.Status.CONNECTION_CLOSED);
    }

    @Test
    void pingRepliesWithMatchingPongWithoutChangingAgentPresence() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage(
                "{\"type\":\"HEARTBEAT\",\"payload\":{\"kind\":\"PING\",\"ping_id\":\"ping-1\"}}"));

        var messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, times(2)).sendMessage(messages.capture());
        assertThat(mapper.readTree(messages.getAllValues().get(1).getPayload()))
                .isEqualTo(mapper.readTree(
                        "{\"type\":\"HEARTBEAT\",\"payload\":{\"kind\":\"PONG\",\"ping_id\":\"ping-1\"}}"));
        verifyNoInteractions(presence);
    }

    @Test
    void oldConnectionPingIsIgnoredAfterReconnect() throws Exception {
        WebSocketSession previous = agentSession("conn_001");
        WebSocketSession current = agentSession("conn_002");
        handler.afterConnectionEstablished(previous);
        handler.afterConnectionEstablished(current);

        handler.handleMessage(previous, new TextMessage(
                "{\"type\":\"HEARTBEAT\",\"payload\":{\"kind\":\"PING\",\"ping_id\":\"old\"}}"));

        verify(previous, times(1)).sendMessage(any(TextMessage.class));
        assertThat(registry.findByAgentId("11")).contains(current);
        verifyNoInteractions(presence);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"kind\":\"PONG\",\"ping_id\":\"1\"}",
            "{\"kind\":\"PING\"}",
            "{\"kind\":\"PING\",\"ping_id\":1}",
            "{\"kind\":\"PING\",\"ping_id\":\" \"}",
            "{\"kind\":\"PING\",\"ping_id\":\"1\",\"agent_id\":99}"
    })
    void malformedHeartbeatIsRejected(String payload) throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage("{\"type\":\"HEARTBEAT\",\"payload\":" + payload + "}"));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, times(1)).sendMessage(any(TextMessage.class));
        verifyNoInteractions(presence);
    }

    @Test
    void failedPongImmediatelyCleansUpCurrentConnection() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);
        IOException failure = new IOException("Pong send failed");
        doThrow(failure).when(session).sendMessage(any(TextMessage.class));

        assertThatThrownBy(() -> handler.handleMessage(session, new TextMessage(
                "{\"type\":\"HEARTBEAT\",\"payload\":{\"kind\":\"PING\",\"ping_id\":\"1\"}}")))
                .isSameAs(failure);

        assertThat(registry.findByAgentId("11")).isEmpty();
        verify(session).close(CloseStatus.SERVER_ERROR);
        verify(presence).markOfflineIfDisconnected(11L);
    }

    @Test
    void transportErrorCleansUpWithoutWaitingForCloseCallbackEvenIfCloseFails() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);
        doThrow(new IOException("Close failed")).when(session).close(CloseStatus.SERVER_ERROR);

        assertThatThrownBy(() -> handler.handleTransportError(session, new IOException("Lost connection")))
                .isInstanceOf(IOException.class);

        assertThat(registry.findByAgentId("11")).isEmpty();
        verify(presence).markOfflineIfDisconnected(11L);
    }

    @Test
    void registersVerifiedAgentBeforeSendingReady() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        doAnswer(invocation -> {
            assertThat(registry.findByAgentId("11")).contains(session);
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        handler.afterConnectionEstablished(session);

        var message = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(message.capture());
        var ready = mapper.readTree(message.getValue().getPayload());
        // 인증된 사용자·팀 ID가 숫자로 포함되고 토큰 등 불필요한 필드가 추가되지 않는지 확인한다.
        assertThat(ready).isEqualTo(mapper.readTree("""
                {
                  "type": "BROKER_READY",
                  "payload": {
                    "connection_id": "conn_001",
                    "agent_id": 11,
                    "user_id": 21,
                    "team_id": 31
                  }
                }
                """));
        assertThat(registry.findByAgentId("11")).contains(session);
    }

    @Test
    void rejectsConnectionWithoutPrincipal() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
        assertThat(registry.findByAgentId("11")).isEmpty();
    }

    @Test
    void rejectsUserAuthenticationAsAgentIdentity() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                "user-21", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any());
        assertThat(registry.findByAgentId("11")).isEmpty();
    }

    @Test
    void rejectsUnauthenticatedAgentIdentity() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.unauthenticated(
                new AgentPrincipal(11, 21, 31), null));

        handler.afterConnectionEstablished(session);

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertThat(registry.findByAgentId("11")).isEmpty();
    }

    @Test
    void closingCurrentConnectionRemovesRegistration() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(registry.findByAgentId("11")).isEmpty();
    }

    @Test
    void oldCloseCallbackDoesNotRemoveNewConnection() throws Exception {
        WebSocketSession previous = agentSession("conn_001");
        WebSocketSession current = agentSession("conn_002");
        handler.afterConnectionEstablished(previous);
        doAnswer(invocation -> {
            handler.afterConnectionClosed(previous, invocation.getArgument(0));
            return null;
        }).when(previous).close(any(CloseStatus.class));

        handler.afterConnectionEstablished(current);
        handler.afterConnectionClosed(previous, CloseStatus.NORMAL);

        verify(previous).close(new CloseStatus(4002, "Connection replaced"));
        assertThat(registry.findByAgentId("11")).contains(current);
    }

    @Test
    void failedReadySendRemovesRegistrationAndClosesSession() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        IOException failure = new IOException("Ready send failed");
        doThrow(failure).when(session).sendMessage(any(TextMessage.class));

        assertThatThrownBy(() -> handler.afterConnectionEstablished(session)).isSameAs(failure);

        assertThat(registry.findByAgentId("11")).isEmpty();
        verify(session).close(CloseStatus.SERVER_ERROR);
    }

    @Test
    void failedReadySendDoesNotRemoveAConcurrentReplacement() throws Exception {
        WebSocketSession previous = agentSession("conn_001");
        WebSocketSession current = agentSession("conn_002");
        doAnswer(invocation -> {
            registry.register("11", current);
            throw new IOException("Previous connection was replaced during send");
        }).when(previous).sendMessage(any(TextMessage.class));

        assertThatThrownBy(() -> handler.afterConnectionEstablished(previous))
                .isInstanceOf(IOException.class);

        assertThat(registry.findByAgentId("11")).contains(current);
    }

    @Test
    void closeFailureDoesNotHideOriginalSendFailure() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        IOException sendFailure = new IOException("Ready send failed");
        IOException closeFailure = new IOException("Close failed");
        doThrow(sendFailure).when(session).sendMessage(any(TextMessage.class));
        doThrow(closeFailure).when(session).close(CloseStatus.SERVER_ERROR);

        assertThatThrownBy(() -> handler.afterConnectionEstablished(session))
                .isSameAs(sendFailure).hasSuppressedException(closeFailure);
        assertThat(registry.findByAgentId("11")).isEmpty();
    }

    @Test
    void cleansUpEvenIfAuthenticationWasInvalidatedBeforeClose() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);
        ((Authentication) session.getPrincipal()).setAuthenticated(false);

        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        assertThat(registry.findByAgentId("11")).isEmpty();
    }

    @Test
    void validPresenceUsesAuthenticatedAgentAndDoesNotSendAck() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage(
                "{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\"ONLINE\"}}"));

        verify(presence).updateStatus(new AgentPrincipal(11, 21, 31), session, AgentStatus.ONLINE);
        verify(session, times(1)).sendMessage(any(TextMessage.class));
    }

    @Test
    void staleConnectionCannotUpdatePresence() throws Exception {
        WebSocketSession previous = agentSession("conn_001");
        WebSocketSession current = agentSession("conn_002");
        handler.afterConnectionEstablished(previous);
        handler.afterConnectionEstablished(current);

        handler.handleMessage(previous, new TextMessage(
                "{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\"OFFLINE\"}}"));

        verifyNoInteractions(presence);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json", "", "null", "[]", "{}",
            "{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\"ONLINE\"}} true",
            "{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\"BUSY\"}}",
            "{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":null}}",
            "{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":true}}",
            "{\"type\":\"AGENT_PRESENCE\",\"payload\":{}}",
            "{\"type\":\"OTHER\",\"payload\":{\"status\":\"ONLINE\"}}",
            "{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\"ONLINE\",\"agent_id\":99}}",
            "{\"type\":\"AGENT_PRESENCE\",\"agent_id\":99,\"payload\":{\"status\":\"ONLINE\"}}"
    })
    void malformedPresenceIsRejectedWithoutUpdatingDatabase(String json) throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);

        handler.handleMessage(session, new TextMessage(json));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(presence);
    }

    @Test
    void revokedAuthenticationCannotReportPresence() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);
        ((Authentication) session.getPrincipal()).setAuthenticated(false);

        handler.handleMessage(session, new TextMessage(
                "{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\"ONLINE\"}}"));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verifyNoInteractions(presence);
    }

    @Test
    void rejectedAgentStateClosesConnection() throws Exception {
        WebSocketSession session = agentSession("conn_001");
        handler.afterConnectionEstablished(session);
        doThrow(new BusinessException(ErrorCode.ACCESS_DENIED)).when(presence)
                .updateStatus(new AgentPrincipal(11, 21, 31), session, AgentStatus.ONLINE);

        handler.handleMessage(session, new TextMessage(
                "{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\"ONLINE\"}}"));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void onlyCurrentCloseTriggersOfflineUpdate() throws Exception {
        WebSocketSession previous = agentSession("conn_001");
        WebSocketSession current = agentSession("conn_002");
        handler.afterConnectionEstablished(previous);
        handler.afterConnectionEstablished(current);

        handler.afterConnectionClosed(previous, CloseStatus.NORMAL);
        verifyNoInteractions(presence);
        handler.afterConnectionClosed(current, CloseStatus.NORMAL);
        handler.afterConnectionClosed(current, CloseStatus.NORMAL);

        verify(presence, times(1)).markOfflineIfDisconnected(11L);
    }

    private WebSocketSession agentSession(String connectionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(connectionId);
        when(session.isOpen()).thenReturn(true);
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new AgentPrincipal(11, 21, 31), null,
                List.of(new SimpleGrantedAuthority("ROLE_AGENT"))));
        return session;
    }
}
