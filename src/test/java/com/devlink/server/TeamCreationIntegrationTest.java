package com.devlink.server;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.service.InviteCodeGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TeamCreationIntegrationTest extends AbstractIntegrationTest {
    private static final String PASSWORD = "ValidPassword123!";

    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private TeamRepository teams;
    @MockitoSpyBean private TeamMemberRepository members;
    @MockitoSpyBean private InviteCodeGenerator inviteCodes;

    @Test
    void creatingTeamStoresAuthenticatedUserAsOwnerAndReturnsUniqueInviteCode() throws Exception {
        try (var browser = new Browser()) {
            long userId = browser.signupAndLogin();
            String name = "Team " + UUID.randomUUID();
            var first = browser.post("/api/v1/teams", Map.of("name", name), browser.csrf());
            assertSuccess(first, 201);
            long teamId = assertStoredOwner(first, userId, name);
            String firstCode = first.body().path("data").path("inviteCode").asText();
            assertThat(firstCode).matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(firstCode)).hasSize(32);

            String secondName = "Another " + UUID.randomUUID();
            var second = browser.post("/api/v1/teams", Map.of("name", secondName), browser.csrf());
            assertSuccess(second, 201);
            assertThat(assertStoredOwner(second, userId, secondName)).isNotEqualTo(teamId);
            assertThat(second.body().path("data").path("inviteCode").asText())
                    .matches("[A-Za-z0-9_-]{43}").isNotEqualTo(firstCode);
        }
    }

    @Test
    void inviteCodeCollisionRollsBackAttemptAndRetriesWithNewCode() throws Exception {
        try (var browser = new Browser()) {
            long userId = browser.signupAndLogin();
            String existingName = "Existing " + UUID.randomUUID();
            var existingResponse = browser.post("/api/v1/teams", Map.of("name", existingName), browser.csrf());
            assertSuccess(existingResponse, 201);
            long existingTeamId = assertStoredOwner(existingResponse, userId, existingName);
            String existingCode = existingResponse.body().path("data").path("inviteCode").asText();
            long teamCount = teams.count();
            long memberCount = members.count();
            String newName = "After collision " + UUID.randomUUID();
            clearInvocations(inviteCodes);
            doReturn(existingCode).doCallRealMethod().when(inviteCodes).generate();
            try {
                var response = browser.post("/api/v1/teams", Map.of("name", newName), browser.csrf());

                assertSuccess(response, 201);
                long newTeamId = assertStoredOwner(response, userId, newName);
                assertThat(newTeamId).isNotEqualTo(existingTeamId);
                assertThat(response.body().path("data").path("inviteCode").asText())
                        .matches("[A-Za-z0-9_-]{43}").isNotEqualTo(existingCode);
                assertThat(teams.count()).isEqualTo(teamCount + 1);
                assertThat(members.count()).isEqualTo(memberCount + 1);
                assertThat(teams.findById(existingTeamId).orElseThrow().getInviteCode()).isEqualTo(existingCode);
                verify(inviteCodes, times(2)).generate();
            } finally {
                reset(inviteCodes);
            }
        }
    }

    @Test
    void exhaustedInviteCodeCollisionsLeaveExistingTeamAndMembershipUnchanged() throws Exception {
        try (var browser = new Browser()) {
            long userId = browser.signupAndLogin();
            String existingName = "Existing " + UUID.randomUUID();
            var existingResponse = browser.post("/api/v1/teams", Map.of("name", existingName), browser.csrf());
            assertSuccess(existingResponse, 201);
            long existingTeamId = assertStoredOwner(existingResponse, userId, existingName);
            Team existingTeam = teams.findById(existingTeamId).orElseThrow();
            TeamMember existingOwner = members.findByTeamIdAndUserId(existingTeamId, userId).orElseThrow();
            String existingCode = existingTeam.getInviteCode();
            long teamCount = teams.count();
            long memberCount = members.count();
            String newName = "Never committed " + UUID.randomUUID();
            clearInvocations(inviteCodes);
            doReturn(existingCode).when(inviteCodes).generate();
            try {
                var response = browser.post("/api/v1/teams", Map.of("name", newName), browser.csrf());

                assertError(response, 500, "INTERNAL_SERVER_ERROR");
                assertThat(response.body().path("message").asText())
                        .doesNotContain(existingCode, "uk_teams_invite_code", "duplicate key");
                assertThat(teams.count()).isEqualTo(teamCount);
                assertThat(members.count()).isEqualTo(memberCount);
                assertThat(teams.findAll()).extracting(Team::getName).doesNotContain(newName);
                Team unchangedTeam = teams.findById(existingTeamId).orElseThrow();
                assertThat(unchangedTeam.getName()).isEqualTo(existingName);
                assertThat(unchangedTeam.getInviteCode()).isEqualTo(existingCode);
                assertThat(unchangedTeam.getCreatedAt()).isEqualTo(existingTeam.getCreatedAt());
                TeamMember unchangedOwner = members.findByTeamIdAndUserId(existingTeamId, userId).orElseThrow();
                assertThat(unchangedOwner.getId()).isEqualTo(existingOwner.getId());
                assertThat(unchangedOwner.getRole()).isEqualTo(MemberRole.OWNER);
                verify(inviteCodes, times(5)).generate();
            } finally {
                reset(inviteCodes);
            }
        }
    }

    @Test
    void ownerAndRoleFieldsInRequestCannotReplaceAuthenticatedUser() throws Exception {
        try (var owner = new Browser(); var other = new Browser()) {
            long ownerId = owner.signupAndLogin();
            long otherId = other.signupAndLogin();
            String name = "Owner protected " + UUID.randomUUID();
            var response = owner.post("/api/v1/teams", Map.of(
                    "name", name,
                    "userId", otherId,
                    "ownerUserId", otherId,
                    "role", "MEMBER",
                    "myRole", "MEMBER"), owner.csrf());

            assertSuccess(response, 201);
            long teamId = assertStoredOwner(response, ownerId, name);
            assertThat(members.findByTeamIdAndUserId(teamId, otherId)).isEmpty();
        }
    }

    @Test
    void independentUserSessionsCreateTeamsWithDifferentOwners() throws Exception {
        try (var first = new Browser(); var second = new Browser()) {
            long firstUserId = first.signupAndLogin();
            long secondUserId = second.signupAndLogin();
            String firstName = "First team " + UUID.randomUUID();
            String secondName = "Second team " + UUID.randomUUID();
            var firstResponse = first.post("/api/v1/teams", Map.of("name", firstName), first.csrf());
            var secondResponse = second.post("/api/v1/teams", Map.of("name", secondName), second.csrf());

            assertSuccess(firstResponse, 201);
            assertSuccess(secondResponse, 201);
            long firstTeamId = assertStoredOwner(firstResponse, firstUserId, firstName);
            long secondTeamId = assertStoredOwner(secondResponse, secondUserId, secondName);
            assertThat(firstTeamId).isNotEqualTo(secondTeamId);
            assertThat(members.findByTeamIdAndUserId(firstTeamId, secondUserId)).isEmpty();
            assertThat(members.findByTeamIdAndUserId(secondTeamId, firstUserId)).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("invalidTeamNames")
    void invalidTeamNameDoesNotCreateTeamOrMembership(String name) throws Exception {
        try (var browser = new Browser()) {
            browser.signupAndLogin();
            long teamCount = teams.count();
            long memberCount = members.count();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);

            var response = browser.post("/api/v1/teams", body, browser.csrf());

            assertError(response, 400, "VALIDATION_FAILED");
            assertThat(teams.count()).isEqualTo(teamCount);
            assertThat(members.count()).isEqualTo(memberCount);
        }
    }

    @Test
    void anonymousRequestWithValidCsrfStillRequiresLogin() throws Exception {
        try (var browser = new Browser()) {
            long teamCount = teams.count();
            long memberCount = members.count();

            var response = browser.post("/api/v1/teams", Map.of("name", "Anonymous team"), browser.csrf());

            assertError(response, 401, "AUTHENTICATION_REQUIRED");
            assertThat(teams.count()).isEqualTo(teamCount);
            assertThat(members.count()).isEqualTo(memberCount);
        }
    }

    @Test
    void loggedInRequestWithoutCsrfIsForbidden() throws Exception {
        try (var browser = new Browser()) {
            browser.signupAndLogin();
            long teamCount = teams.count();
            long memberCount = members.count();

            var response = browser.post("/api/v1/teams", Map.of("name", "Missing csrf"), null);

            assertError(response, 403, "ACCESS_DENIED");
            assertThat(teams.count()).isEqualTo(teamCount);
            assertThat(members.count()).isEqualTo(memberCount);
        }
    }

    @Test
    void membershipPersistenceFailureRollsBackNewTeam() throws Exception {
        try (var browser = new Browser()) {
            browser.signupAndLogin();
            var csrf = browser.csrf();
            long teamCount = teams.count();
            long memberCount = members.count();
            String name = "Rollback " + UUID.randomUUID();
            doThrow(new IllegalStateException("owner persistence failed"))
                    .when(members).saveAndFlush(any(TeamMember.class));
            try {
                var response = browser.post("/api/v1/teams", Map.of("name", name), csrf);

                assertError(response, 500, "INTERNAL_SERVER_ERROR");
                assertThat(teams.count()).isEqualTo(teamCount);
                assertThat(members.count()).isEqualTo(memberCount);
                assertThat(teams.findAll()).extracting(Team::getName).doesNotContain(name);
            } finally {
                reset(members);
            }
        }
    }

    private long assertStoredOwner(HttpResult response, long userId, String name) {
        JsonNode data = response.body().path("data");
        long teamId = data.path("teamId").asLong();
        assertThat(teamId).isPositive();
        assertThat(data.path("name").asText()).isEqualTo(name);
        assertThat(data.path("myRole").asText()).isEqualTo("OWNER");
        assertThat(data.path("createdAt").asText()).isNotBlank();
        Team team = teams.findById(teamId).orElseThrow();
        assertThat(team.getName()).isEqualTo(name);
        assertThat(team.getInviteCode()).isEqualTo(data.path("inviteCode").asText());
        TeamMember member = members.findByTeamIdAndUserId(teamId, userId).orElseThrow();
        assertThat(member.getRole()).isEqualTo(MemberRole.OWNER);
        assertThat(member.getJoinedAt()).isNotNull();
        assertThat(members.findAll().stream().filter(saved -> saved.getTeamId().equals(teamId)))
                .hasSize(1);
        return teamId;
    }

    private void assertSuccess(HttpResult response, int statusCode) {
        assertJsonResponse(response, statusCode);
        assertThat(response.body().path("success").asBoolean()).isTrue();
        assertThat(response.body().path("code").isNull()).isTrue();
        assertThat(response.body().path("message").isNull()).isTrue();
    }

    private void assertError(HttpResult response, int statusCode, String code) {
        assertJsonResponse(response, statusCode);
        assertThat(response.body().path("success").asBoolean()).isFalse();
        assertThat(response.body().path("data").isNull()).isTrue();
        assertThat(response.body().path("code").asText()).isEqualTo(code);
    }

    private void assertJsonResponse(HttpResult response, int statusCode) {
        assertThat(response.statusCode()).isEqualTo(statusCode);
        assertThat(response.headers().firstValue("Location")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/json");
    }

    private static Stream<String> invalidTeamNames() {
        return Stream.of(null, "", "   ", "A".repeat(121));
    }

    private record Csrf(String headerName, String token) { }

    private record HttpResult(int statusCode, HttpHeaders headers, JsonNode body) { }

    private final class Browser implements AutoCloseable {
        private final HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        private long signupAndLogin() throws Exception {
            String email = "team-" + UUID.randomUUID() + "@example.com";
            var csrf = csrf();
            var signup = post("/api/v1/auth/signup", Map.of(
                    "email", email, "password", PASSWORD, "name", "Team user"), csrf);
            assertSuccess(signup, 201);
            var login = post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD), csrf);
            assertSuccess(login, 200);
            return login.body().path("data").path("userId").asLong();
        }

        private Csrf csrf() throws Exception {
            var response = send("GET", "/api/v1/auth/csrf", null, null);
            assertSuccess(response, 200);
            var data = response.body().path("data");
            assertThat(data.path("headerName").asText()).isNotBlank();
            assertThat(data.path("token").asText()).isNotBlank();
            return new Csrf(data.path("headerName").asText(), data.path("token").asText());
        }

        private HttpResult post(String path, Object body, Csrf csrf) throws Exception {
            return send("POST", path, body, csrf);
        }

        private HttpResult send(String method, String path, Object body, Csrf csrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
            if (csrf != null) {
                request.header(csrf.headerName(), csrf.token());
            }
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpResult(response.statusCode(), response.headers(), json.readTree(response.body()));
        }

        @Override
        public void close() {
            client.close();
        }
    }
}
