package com.devlink.server.handler;

import java.io.IOException;
import java.util.List;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.realtime.ConnectionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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

class DevLinkWebSocketHandlerTest {
    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final ObjectMapper mapper = new ObjectMapper();
    private final DevLinkWebSocketHandler handler = new DevLinkWebSocketHandler(registry, mapper);

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
        assertThat(ready.path("type").asText()).isEqualTo("BROKER_READY");
        assertThat(ready.path("payload").path("connection_id").asText()).isEqualTo("conn_001");
        assertThat(ready.path("payload").path("agent_id").asLong()).isEqualTo(11);
        assertThat(ready.path("payload").size()).isEqualTo(2);
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
