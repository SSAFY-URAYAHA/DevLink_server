package com.devlink.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentStatus;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.user.User;
import com.devlink.server.handler.DevLinkWebSocketHandler;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.service.AgentTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(AgentPresenceIntegrationTest.TokenVerificationFixture.class)
class AgentPresenceIntegrationTest extends AbstractIntegrationTest {
    @Autowired private AgentRepository agents;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private ConnectionRegistry connections;
    @Autowired private DevLinkWebSocketHandler handler;
    @Autowired private AtomicReference<AgentPrincipal> verifiedAgent;
    @LocalServerPort private int port;
    private Long agentId;

    @BeforeEach
    void createAgent() {
        String unique = UUID.randomUUID().toString();
        User user = users.save(User.create(unique + "@example.com", "Presence", null, "test-password-hash"));
        Team team = teams.save(Team.create("Presence", unique));
        Agent agent = agents.save(Agent.create(team.getId(), user.getId(), "Codex", null, ToolType.CODEX, "server"));
        agentId = agent.getId();
        verifiedAgent.set(new AgentPrincipal(agentId, user.getId(), team.getId()));
    }

    @Test
    void persistsOnlineOfflineReportsAndMarksOfflineOnConnectionClose() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var listener = new BrokerListener();
            var socket = connect(client, listener);
            try {
                listener.ready.get(5, TimeUnit.SECONDS);
                assertThat(savedAgent().getStatus()).isEqualTo(AgentStatus.OFFLINE);
                assertThat(savedAgent().getLastSeenAt()).isNull();

                sendPresence(socket, "ONLINE");
                awaitStatus(AgentStatus.ONLINE);
                LocalDateTime seenAt = savedAgent().getLastSeenAt();
                assertThat(seenAt).isNotNull();

                sendPresence(socket, "OFFLINE");
                awaitStatus(AgentStatus.OFFLINE);
                assertThat(savedAgent().getLastSeenAt()).isEqualTo(seenAt);
                assertThat(connections.findByAgentId(agentId.toString())).isPresent();

                sendPresence(socket, "ONLINE");
                awaitStatus(AgentStatus.ONLINE);
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
                listener.closed.get(5, TimeUnit.SECONDS);
                awaitStatus(AgentStatus.OFFLINE);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(connections.findByAgentId(agentId.toString())).isEmpty());
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void oldMessagesAndCloseCallbacksCannotOverwriteReplacementOnlineState() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var firstListener = new BrokerListener();
            var first = connect(client, firstListener);
            try {
                firstListener.ready.get(5, TimeUnit.SECONDS);
                sendPresence(first, "ONLINE");
                awaitStatus(AgentStatus.ONLINE);
                var previous = connections.findByAgentId(agentId.toString()).orElseThrow();
                var secondListener = new BrokerListener();
                var second = connect(client, secondListener);
                try {
                    secondListener.ready.get(5, TimeUnit.SECONDS);
                    assertThat(firstListener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4002);
                    LocalDateTime oldSeen = savedAgent().getLastSeenAt();
                    sendPresence(second, "ONLINE");
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                            assertThat(savedAgent().getLastSeenAt()).isAfter(oldSeen));

                    // 새 연결 이후 늦게 처리되는 옛 메시지와 종료 콜백을 서버 측에서 재현한다.
                    handler.handleMessage(previous, new TextMessage(
                            "{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\"OFFLINE\"}}"));
                    handler.afterConnectionClosed(previous, CloseStatus.NORMAL);

                    assertThat(savedAgent().getStatus()).isEqualTo(AgentStatus.ONLINE);
                    assertThat(connections.findByAgentId(agentId.toString())).isPresent();
                    second.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
                    secondListener.closed.get(5, TimeUnit.SECONDS);
                    awaitStatus(AgentStatus.OFFLINE);
                } finally {
                    second.abort();
                }
            } finally {
                first.abort();
            }
        }
    }

    @Test
    void invalidStatusClosesConnectionWithoutAcceptingInvalidState() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var listener = new BrokerListener();
            var socket = connect(client, listener);
            try {
                listener.ready.get(5, TimeUnit.SECONDS);
                sendPresence(socket, "ONLINE");
                awaitStatus(AgentStatus.ONLINE);
                LocalDateTime seenAt = savedAgent().getLastSeenAt();

                sendPresence(socket, "BUSY");

                assertThat(listener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
                awaitStatus(AgentStatus.OFFLINE);
                assertThat(savedAgent().getLastSeenAt()).isEqualTo(seenAt);
            } finally {
                socket.abort();
            }
        }
    }

    private Agent savedAgent() {
        return agents.findById(agentId).orElseThrow();
    }

    private void awaitStatus(AgentStatus status) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(savedAgent().getStatus()).isEqualTo(status));
    }

    private void sendPresence(WebSocket socket, String status) throws Exception {
        socket.sendText("{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\""
                + status + "\"}}", true).get(5, TimeUnit.SECONDS);
    }

    private WebSocket connect(HttpClient client, BrokerListener listener) throws Exception {
        return client.newWebSocketBuilder()
                .header(HttpHeaders.AUTHORIZATION, "Bearer presence-test-token")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/broker"), listener)
                .get(5, TimeUnit.SECONDS);
    }

    private static class BrokerListener implements WebSocket.Listener {
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (last) {
                ready.complete(null);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ready.completeExceptionally(error);
            closed.completeExceptionally(error);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TokenVerificationFixture {
        @Bean
        AtomicReference<AgentPrincipal> verifiedAgent() {
            return new AtomicReference<>();
        }

        @Bean
        AgentTokenVerifier agentTokenVerifier(AtomicReference<AgentPrincipal> verifiedAgent) {
            return token -> "presence-test-token".equals(token)
                    ? Optional.ofNullable(verifiedAgent.get()) : Optional.empty();
        }
    }
}
