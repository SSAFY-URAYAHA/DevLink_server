package com.devlink.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.service.AgentTokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(BrokerConnectionLifecycleIntegrationTest.TokenVerificationFixture.class)
class BrokerConnectionLifecycleIntegrationTest extends AbstractIntegrationTest {
    @Autowired private ConnectionRegistry registry;
    @Autowired private ObjectMapper mapper;
    @LocalServerPort private int port;

    @Test
    void authenticatedConnectionReceivesReadyAndIsRemovedAfterNormalClose() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var listener = new BrokerListener();
            var socket = connect(client, listener);
            try {
                JsonNode ready = ready(listener);
                var registered = registry.findByAgentId("11").orElseThrow();
                assertThat(ready.path("type").asText()).isEqualTo("BROKER_READY");
                assertThat(ready.path("payload").path("agent_id").asLong()).isEqualTo(11);
                assertThat(ready.path("payload").path("connection_id").asText())
                        .isEqualTo(registered.getId());

                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
                assertThat(listener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(WebSocket.NORMAL_CLOSURE);
                await().atMost(Duration.ofSeconds(5))
                        .untilAsserted(() -> assertThat(registry.findByAgentId("11")).isEmpty());
            } finally {
                socket.abort();
            }
        }
    }

    @Test
    void reconnectClosesOldSocketAndKeepsReplacementRegistered() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var firstListener = new BrokerListener();
            var first = connect(client, firstListener);
            try {
                JsonNode firstReady = ready(firstListener);
                var previous = registry.findByAgentId("11").orElseThrow();
                var secondListener = new BrokerListener();
                var second = connect(client, secondListener);
                try {
                    JsonNode secondReady = ready(secondListener);
                    assertThat(firstListener.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4002);
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                        var current = registry.findByAgentId("11").orElseThrow();
                        assertThat(current).isNotSameAs(previous);
                        assertThat(current.getId())
                                .isEqualTo(secondReady.path("payload").path("connection_id").asText());
                    });
                    assertThat(secondReady.path("payload").path("connection_id").asText())
                            .isNotEqualTo(firstReady.path("payload").path("connection_id").asText());

                    second.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
                    secondListener.closed.get(5, TimeUnit.SECONDS);
                    await().atMost(Duration.ofSeconds(5))
                            .untilAsserted(() -> assertThat(registry.findByAgentId("11")).isEmpty());
                } finally {
                    second.abort();
                }
            } finally {
                first.abort();
            }
        }
    }

    @Test
    void abortedClientConnectionIsRemoved() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var listener = new BrokerListener();
            var socket = connect(client, listener);
            try {
                ready(listener);
                assertThat(registry.findByAgentId("11")).isPresent();

                socket.abort();

                await().atMost(Duration.ofSeconds(5))
                        .untilAsserted(() -> assertThat(registry.findByAgentId("11")).isEmpty());
            } finally {
                socket.abort();
            }
        }
    }

    private WebSocket connect(HttpClient client, BrokerListener listener) throws Exception {
        return client.newWebSocketBuilder()
                .header(HttpHeaders.AUTHORIZATION, "Bearer lifecycle-test-token")
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/broker"), listener)
                .get(5, TimeUnit.SECONDS);
    }

    private JsonNode ready(BrokerListener listener) throws Exception {
        return mapper.readTree(listener.message.get(5, TimeUnit.SECONDS));
    }

    private static class BrokerListener implements WebSocket.Listener {
        private final CompletableFuture<String> message = new CompletableFuture<>();
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
                message.complete(text.toString());
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
            message.completeExceptionally(error);
            closed.completeExceptionally(error);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TokenVerificationFixture {
        @Bean
        AgentTokenVerifier agentTokenVerifier() {
            return token -> "lifecycle-test-token".equals(token)
                    ? Optional.of(new AgentPrincipal(11, 21, 31)) : Optional.empty();
        }
    }
}
