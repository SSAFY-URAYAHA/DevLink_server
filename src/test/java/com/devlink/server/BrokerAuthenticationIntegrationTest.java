package com.devlink.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.handler.DevLinkWebSocketHandler;
import com.devlink.server.service.AgentTokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.WebSocketSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(BrokerAuthenticationIntegrationTest.TokenVerificationFixture.class)
class BrokerAuthenticationIntegrationTest extends AbstractIntegrationTest {
    @Autowired private TestRestTemplate rest;
    @Autowired private MockMvc mvc;
    @LocalServerPort private int port;
    @MockitoSpyBean private DevLinkWebSocketHandler handler;

    @Test
    void validAgentTokenUpgradesAndUsesVerifiedIdentityWithoutCredentials() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var socket = client.newWebSocketBuilder()
                    .header(HttpHeaders.AUTHORIZATION, "Bearer test-agent-token")
                    .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                    .buildAsync(URI.create("ws://localhost:" + port
                            + "/ws/broker?agentId=999&ownerUserId=999"), new WebSocket.Listener() { })
                    .get(5, TimeUnit.SECONDS);
            try {
                var sessions = ArgumentCaptor.forClass(WebSocketSession.class);
                verify(handler, timeout(5000)).afterConnectionEstablished(sessions.capture());
                var authentication = (Authentication) sessions.getValue().getPrincipal();
                assertThat(authentication).isNotNull();
                assertThat(authentication.isAuthenticated()).isTrue();
                assertThat(authentication.getPrincipal()).isEqualTo(new AgentPrincipal(11, 21, 31));
                assertThat(authentication.getCredentials()).isNull();
                assertThat(authentication.getAuthorities())
                        .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_AGENT");
            } finally {
                socket.abort();
            }
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "Bearer ", "Basic test-agent-token", "Bearer unknown-token",
            "Bearer revoked-token", "Bearer expired-token", "Bearer test-agent-token extra"})
    void rejectsMissingMalformedOrInvalidCredentialsBeforeHandshake(String authorization) throws Exception {
        var headers = new HttpHeaders();
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        var response = rest.exchange("/ws/broker", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNull();
        verify(handler, never()).afterConnectionEstablished(any());
    }

    @Test
    void doesNotAcceptQueryStringTokensOrMultipleAuthorizationHeaders() throws Exception {
        var queryResponse = rest.getForEntity("/ws/broker?token=test-agent-token", JsonNode.class);
        assertThat(queryResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer test-agent-token");
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer unknown-token");
        var response = rest.exchange("/ws/broker", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(handler, never()).afterConnectionEstablished(any());
    }

    @Test
    void userSessionDoesNotReplaceRequiredAgentToken() throws Exception {
        var user = UsernamePasswordAuthenticationToken.authenticated(
                "user-21", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var session = new MockHttpSession();
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(user));
        mvc.perform(get("/ws/broker").session(session)).andExpect(status().isUnauthorized());
        verify(handler, never()).afterConnectionEstablished(any());
    }

    @Test
    void agentTokenDoesNotAuthenticateUserApis() {
        var headers = new HttpHeaders();
        headers.setBearerAuth("test-agent-token");
        var response = rest.exchange("/api/v1/teams", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validCredentialDoesNotBypassOriginValidation() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            assertThatThrownBy(() -> client.newWebSocketBuilder()
                    .header(HttpHeaders.AUTHORIZATION, "Bearer test-agent-token")
                    .header(HttpHeaders.ORIGIN, "https://untrusted.example")
                    .buildAsync(URI.create("ws://localhost:" + port + "/ws/broker"),
                            new WebSocket.Listener() { })
                    .get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOfSatisfying(WebSocketHandshakeException.class,
                            exception -> assertThat(exception.getResponse().statusCode()).isEqualTo(403));
            verify(handler, never()).afterConnectionEstablished(any());
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TokenVerificationFixture {
        @Bean
        AgentTokenVerifier agentTokenVerifier() {
            // Test-only credentials; production has no fallback token or bypass.
            return token -> "test-agent-token".equals(token)
                    ? Optional.of(new AgentPrincipal(11, 21, 31)) : Optional.empty();
        }
    }
}
