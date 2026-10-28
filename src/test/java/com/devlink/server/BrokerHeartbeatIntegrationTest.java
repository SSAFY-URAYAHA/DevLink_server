package com.devlink.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// 실제 소켓·DB·스케줄러를 검증하되 테스트의 무수신 제한만 2초로 줄인다.
@TestPropertySource(properties = {
        "devlink.broker.heartbeat-timeout-ms=2000",
        "devlink.broker.heartbeat-check-interval-ms=50"
})
@Import(BrokerHeartbeatIntegrationTest.TokenVerificationFixture.class)
class BrokerHeartbeatIntegrationTest extends AbstractIntegrationTest {
    @Autowired private AgentRepository agents;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private ConnectionRegistry connections;
    @Autowired private DevLinkWebSocketHandler handler;
    @Autowired private ObjectMapper mapper;
    @Autowired private AtomicReference<AgentPrincipal> verifiedAgent;
    @LocalServerPort private int port;
    private Long agentId;

    @BeforeEach
    void createAgent() {
        String unique = UUID.randomUUID().toString();
        User user = users.save(User.create(unique + "@example.com", "Heartbeat", null, "test-hash"));
        Team team = teams.save(Team.create("Heartbeat", unique));
        Agent agent = agents.save(Agent.create(team.getId(), user.getId(), "Codex", null, ToolType.CODEX, "server"));
        agentId = agent.getId();
        verifiedAgent.set(new AgentPrincipal(agentId, user.getId(), team.getId()));
    }

    @Test
    void timeoutMarksOfflineAndReconnectRequiresNewPresenceReport() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var firstListener = new BrokerListener();
            var first = connect(client, firstListener);
            try {
                nextMessage(firstListener);
                var previous = connections.findByAgentId(agentId.toString()).orElseThrow();
                sendPresence(first);
                awaitStatus(AgentStatus.ONLINE);
                assertThat(firstListener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4001);
                awaitStatus(AgentStatus.OFFLINE);
                assertThat(connections.findByAgentId(agentId.toString())).isEmpty();

                var secondListener = new BrokerListener();
                var second = connect(client, secondListener);
                try {
                    nextMessage(secondListener);
                    sendPing(second, "reconnected");
                    assertThat(mapper.readTree(nextMessage(secondListener))
                            .path("payload").path("ping_id").asText()).isEqualTo("reconnected");
                    assertThat(savedAgent().getStatus()).isEqualTo(AgentStatus.OFFLINE);
                    sendPresence(second);
                    awaitStatus(AgentStatus.ONLINE);

                    // 이전 연결의 지연 PING·종료 처리는 새 연결과 ONLINE 상태를 훼손하면 안 된다.
                    handler.handleMessage(previous, new TextMessage(ping("late")));
                    handler.afterConnectionClosed(previous, CloseStatus.NORMAL);
                    assertThat(connections.findByAgentId(agentId.toString())).isPresent();
                    assertThat(savedAgent().getStatus()).isEqualTo(AgentStatus.ONLINE);
                    close(second, secondListener);
                } finally {
                    second.abort();
                }
            } finally {
                first.abort();
            }
        }
    }

    @Test
    void repeatedPingsKeepConnectionAliveWithoutChangingAgentStatusOrLastSeen() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var listener = new BrokerListener();
            var socket = connect(client, listener);
            try {
                nextMessage(listener);
                // ONLINE 보고 없이도 PONG은 응답하되 Agent는 계속 OFFLINE이어야 한다.
                for (int index = 0; index < 6; index++) {
                    sendPing(socket, "ping-" + index);
                    var pong = mapper.readTree(nextMessage(listener));
                    assertThat(pong.path("type").asText()).isEqualTo("HEARTBEAT");
                    assertThat(pong.path("payload").path("kind").asText()).isEqualTo("PONG");
                    assertThat(pong.path("payload").path("ping_id").asText()).isEqualTo("ping-" + index);
                    TimeUnit.MILLISECONDS.sleep(400);
                }
                // 초기 2초 제한보다 길게 PING을 보내 수신 시각이 연장되는지 확인한다.
                assertThat(listener.closed).isNotDone();
                assertThat(connections.findByAgentId(agentId.toString())).isPresent();
                assertThat(savedAgent().getStatus()).isEqualTo(AgentStatus.OFFLINE);
                assertThat(savedAgent().getLastSeenAt()).isNull();
                close(socket, listener);
            } finally {
                socket.abort();
            }
        }
    }

    private Agent savedAgent() {
        return agents.findById(agentId).orElseThrow();
    }

    private void awaitStatus(AgentStatus status) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(savedAgent().getStatus()).isEqualTo(status));
    }

    private void sendPresence(WebSocket socket) throws Exception {
        socket.sendText("{\"type\":\"AGENT_PRESENCE\",\"payload\":{\"status\":\"ONLINE\"}}", true)
                .get(5, TimeUnit.SECONDS);
    }

    private static String ping(String id) {
        return "{\"type\":\"HEARTBEAT\",\"payload\":{\"kind\":\"PING\",\"ping_id\":\"" + id + "\"}}";
    }

    private void sendPing(WebSocket socket, String id) throws Exception {
        socket.sendText(ping(id), true).get(5, TimeUnit.SECONDS);
    }

    private String nextMessage(BrokerListener listener) throws Exception {
        String message = listener.messages.poll(5, TimeUnit.SECONDS);
        assertThat(message).as("서버 JSON 응답").isNotNull();
        return message;
    }

    private void close(WebSocket socket, BrokerListener listener) throws Exception {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        assertThat(listener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(WebSocket.NORMAL_CLOSURE);
        awaitStatus(AgentStatus.OFFLINE);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(connections.findByAgentId(agentId.toString())).isEmpty());
    }

    private WebSocket connect(HttpClient client, BrokerListener listener) throws Exception {
        return client.newWebSocketBuilder()
                .header(HttpHeaders.AUTHORIZATION, "Bearer heartbeat-test-token")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/broker"), listener)
                .get(5, TimeUnit.SECONDS);
    }

    private static class BrokerListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                messages.add(text.toString());
                text.setLength(0);
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
            return token -> "heartbeat-test-token".equals(token)
                    ? Optional.ofNullable(verifiedAgent.get()) : Optional.empty();
        }
    }
}
