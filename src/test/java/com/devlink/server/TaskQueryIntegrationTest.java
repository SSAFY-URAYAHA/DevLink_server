package com.devlink.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.devlink.server.common.AgentTokenHash;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TaskQueryIntegrationTest extends AbstractIntegrationTest {
    private static final String TEST_PASSWORD = "Query-test-password-123!";
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 9, 0);
    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private TaskRepository tasks;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private User owner;
    private User teammate;
    private User outsider;
    private Team team;
    private Team otherTeam;
    private TestAgent callerAgent;
    private TestAgent sameOwnerAgent;
    private TestAgent teammateAgent;
    private TestAgent emptyAgent;
    private TestAgent otherTeamAgent;
    private Map<String, Long> taskIds;
    private int taskSequence;

    @Test
    void rejectsRequestWithoutAgentToken() throws Exception {
        assertError(getTasks(null, ""), 401, "AUTHENTICATION_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer unknown-token", "Basic credentials", "Bearer", "Bearer first second"})
    void rejectsInvalidAuthorizationHeader(String authorization) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = request(listPath(team.getId()))
                    .header("Authorization", authorization).GET().build();
            assertError(client.send(request, HttpResponse.BodyHandlers.ofString()),
                    401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void usesMineAllAndDefaultPaginationWhenQueryIsOmitted() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), ""));

        assertItems(data, "selfPending", "receivedCompleted", "receivedPending", "sentRejected",
                "sentFailed", "sentCompleted", "sentInProgress", "sentPending");
        assertPage(data, 0, 20, 8, 1, false);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "all|selfPending,receivedCompleted,receivedPending,sentRejected,sentFailed,sentCompleted,sentInProgress,sentPending",
            "sent|selfPending,sentRejected,sentFailed,sentCompleted,sentInProgress,sentPending",
            "received|selfPending,receivedCompleted,receivedPending"
    })
    void filtersDirectionAndReturnsSelfTaskOnlyOnce(String direction, String labels) throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?scope=mine&direction=" + direction));

        String[] expected = labels.split(",");
        assertItems(data, expected);
        assertPage(data, 0, 20, expected.length, 1, false);
    }

    @Test
    void mineScopeDoesNotIncludeAnotherAgentOwnedBySameUser() throws Exception {
        JsonNode data = assertSuccess(getTasks(sameOwnerAgent.token(), "?scope=mine"));

        assertItems(data, "sameOwnerReceived", "sameOwnerSent");
        assertPage(data, 0, 20, 2, 1, false);
    }

    @Test
    void teamScopeIncludesOtherAgentsButExcludesOtherTeams() throws Exception {
        // 작업에 참여하지 않은 Agent도 같은 팀 전체 목록을 조회할 수 있다.
        JsonNode data = assertSuccess(getTasks(emptyAgent.token(), "?scope=team&direction=all"));

        assertItems(data, "sameOwnerReceived", "sameOwnerSent", "selfPending", "receivedCompleted",
                "receivedPending", "sentRejected", "sentFailed", "sentCompleted",
                "sentInProgress", "sentPending");
        assertPage(data, 0, 20, 10, 1, false);
        for (JsonNode item : data.path("items")) {
            assertThat(item.path("teamId").asLong()).isEqualTo(team.getId());
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "PENDING|selfPending,receivedPending,sentPending",
            "IN_PROGRESS|sentInProgress",
            "COMPLETED|receivedCompleted,sentCompleted",
            "FAILED|sentFailed",
            "REJECTED|sentRejected"
    })
    void filtersEveryTaskStatusInMineScope(String status, String labels) throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?status=" + status));

        String[] expected = labels.split(",");
        assertItems(data, expected);
        assertPage(data, 0, 20, expected.length, 1, false);
        for (JsonNode item : data.path("items")) {
            assertThat(item.path("status").asText()).isEqualTo(status);
        }
    }

    @Test
    void combinesTeamStatusFilterAndPagination() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(),
                "?scope=team&status=FAILED&size=1&page=1"));

        assertItems(data, "sentFailed");
        assertPage(data, 1, 1, 2, 2, false);
    }

    @Test
    void combinesDirectionAndStatusFilter() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(),
                "?direction=received&status=COMPLETED"));

        assertItems(data, "receivedCompleted");
        assertPage(data, 0, 20, 1, 1, false);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0|true|selfPending,receivedCompleted,receivedPending",
            "1|true|sentRejected,sentFailed,sentCompleted",
            "2|false|sentInProgress,sentPending"
    })
    void paginatesWithStableTotals(int page, boolean hasNext, String labels) throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?size=3&page=" + page));

        assertItems(data, labels.split(","));
        assertPage(data, page, 3, 8, 3, hasNext);
    }

    @Test
    void returnsEmptyPageBeyondLastPageWithoutLosingTotals() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?size=3&page=3"));

        assertItems(data);
        assertPage(data, 3, 3, 8, 3, false);
    }

    @Test
    void returnsEmptyListForAgentWithoutTasks() throws Exception {
        JsonNode data = assertSuccess(getTasks(emptyAgent.token(), ""));

        assertItems(data);
        assertPage(data, 0, 20, 0, 0, false);
    }

    @Test
    void returnsEmptyListWhenCombinedFiltersHaveNoMatch() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?direction=received&status=FAILED"));

        assertItems(data);
        assertPage(data, 0, 20, 0, 0, false);
    }

    @Test
    void sortsEqualCreationTimesByTaskIdDescending() throws Exception {
        // createdAt이 같아도 페이지 사이에서 순서가 흔들리지 않아야 한다.
        jdbc.update("update tasks set created_at = ? where id = ?",
                Timestamp.valueOf(BASE_TIME.plusMinutes(7)), taskIds.get("sentPending"));

        JsonNode first = assertSuccess(getTasks(callerAgent.token(), "?size=1&page=0"));
        JsonNode second = assertSuccess(getTasks(callerAgent.token(), "?size=1&page=1"));
        assertItems(first, "selfPending");
        assertItems(second, "sentPending");
        assertPage(first, 0, 1, 8, 8, true);
        assertPage(second, 1, 1, 8, 8, true);
    }

    @ParameterizedTest
    @CsvSource({"sentPending,PENDING,0,false,false", "sentInProgress,IN_PROGRESS,1,true,false",
            "sentCompleted,COMPLETED,2,true,true", "sentFailed,FAILED,3,true,true",
            "sentRejected,REJECTED,4,false,true"})
    void mapsAgentAndLifecycleFields(String label, String status, int minute,
            boolean hasStarted, boolean hasCompleted) throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?direction=sent&status=" + status));
        JsonNode item = findItem(data, label);

        assertThat(item.path("teamId").asLong()).isEqualTo(team.getId());
        assertThat(item.path("requesterAgent").path("agentId").asLong()).isEqualTo(callerAgent.id());
        assertThat(item.path("requesterAgent").path("name").asText()).isEqualTo(callerAgent.name());
        assertThat(item.path("receiverAgent").path("agentId").asLong()).isEqualTo(teammateAgent.id());
        assertThat(item.path("receiverAgent").path("name").asText()).isEqualTo(teammateAgent.name());
        assertThat(item.path("status").asText()).isEqualTo(status);
        LocalDateTime createdAt = BASE_TIME.plusMinutes(minute);
        assertThat(LocalDateTime.parse(item.path("createdAt").asText())).isEqualTo(createdAt);
        assertTimestamp(item.path("startedAt"), hasStarted ? createdAt.plusSeconds(10) : null);
        assertTimestamp(item.path("completedAt"), hasCompleted ? createdAt.plusSeconds(20) : null);
    }

    @Test
    void rejectsAccessToAnotherTeam() throws Exception {
        assertError(get(listPath(otherTeam.getId()), callerAgent.token()), 403, "ACCESS_DENIED");
        assertError(get(listPath(team.getId()), otherTeamAgent.token()), 403, "ACCESS_DENIED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"scope=invalid", "direction=invalid", "status=UNKNOWN", "status=pending",
            "page=-1", "page=abc", "page=2147483648", "size=0", "size=-1", "size=101",
            "size=abc", "scope=team&direction=sent", "scope=team&direction=received"})
    void rejectsInvalidQueryParameters(String query) throws Exception {
        assertError(getTasks(callerAgent.token(), "?" + query), 400, "VALIDATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidTeamId(String teamId) throws Exception {
        assertError(get("/api/v1/teams/" + teamId + "/tasks", callerAgent.token()),
                400, "VALIDATION_FAILED");
    }

    @Test
    void acceptsMaximumPageSize() throws Exception {
        JsonNode data = assertSuccess(getTasks(callerAgent.token(), "?size=100"));
        assertPage(data, 0, 100, 8, 1, false);
        assertThat(data.path("items").size()).isEqualTo(8);
    }

    @Test
    void rejectsRevokedToken() throws Exception {
        jdbc.update("update agents set connection_token_hash = null where id = ?", callerAgent.id());

        assertError(getTasks(callerAgent.token(), ""), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void rejectsTokenWhenOwnerHasLeftTeam() throws Exception {
        jdbc.update("delete from team_members where team_id = ? and user_id = ?",
                team.getId(), owner.getId());

        assertError(getTasks(callerAgent.token(), ""), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void requiresAgentTokenEvenWithUserSessionAndKeepsSessionIdentitySeparate() throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            // 다른 팀 사용자의 실제 로그인 세션을 만들어 Agent 인증과 분리되는지 확인한다.
            JsonNode csrf = assertSuccess(client.send(request("/api/v1/auth/csrf").GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            var login = request("/api/v1/auth/login")
                    .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                            Map.of("email", outsider.getEmail(), "password", TEST_PASSWORD)))).build();
            assertSuccess(client.send(login, HttpResponse.BodyHandlers.ofString()));

            assertCurrentUser(client, outsider.getId());
            assertError(client.send(request(listPath(team.getId())).GET().build(),
                    HttpResponse.BodyHandlers.ofString()), 401, "AUTHENTICATION_REQUIRED");

            JsonNode data = assertSuccess(client.send(request(listPath(team.getId()))
                    .header("Authorization", "Bearer " + callerAgent.token()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            assertPage(data, 0, 20, 8, 1, false);
            assertItems(data, "selfPending", "receivedCompleted", "receivedPending", "sentRejected",
                    "sentFailed", "sentCompleted", "sentInProgress", "sentPending");
            assertCurrentUser(client, outsider.getId());
        }
    }

    private void assertCurrentUser(HttpClient client, Long userId) throws Exception {
        JsonNode currentUser = assertSuccess(client.send(request("/api/v1/auth/users/me").GET().build(),
                HttpResponse.BodyHandlers.ofString()));
        assertThat(currentUser.path("userId").asLong()).isEqualTo(userId);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10));
    }

    private String listPath(Long teamId) {
        return "/api/v1/teams/" + teamId + "/tasks";
    }

    private HttpResponse<String> getTasks(String token, String query) throws Exception {
        return get(listPath(team.getId()) + query, token);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = request(path).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private JsonNode assertSuccess(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("code").isNull()).isTrue();
        assertThat(body.path("message").isNull()).isTrue();
        assertThat(body.path("data").isObject()).isTrue();
        return body.path("data");
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as("HTTP response: %s", response.body()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").isNull()).isTrue();
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("message").asText()).isNotBlank();
    }

    private void assertItems(JsonNode data, String... labels) {
        assertThat(data.path("items").isArray()).isTrue();
        List<Long> actual = new ArrayList<>();
        data.path("items").forEach(item -> actual.add(item.path("taskId").asLong()));
        List<Long> expected = new ArrayList<>();
        for (String label : labels) {
            assertThat(taskIds).containsKey(label);
            expected.add(taskIds.get(label));
        }
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    private void assertPage(JsonNode data, int page, int size, long total, int totalPages, boolean hasNext) {
        assertThat(data.path("page").asInt()).isEqualTo(page);
        assertThat(data.path("size").asInt()).isEqualTo(size);
        assertThat(data.path("totalElements").asLong()).isEqualTo(total);
        assertThat(data.path("totalPages").asInt()).isEqualTo(totalPages);
        assertThat(data.path("hasNext").asBoolean()).isEqualTo(hasNext);
    }

    private JsonNode findItem(JsonNode data, String label) {
        for (JsonNode item : data.path("items")) {
            if (item.path("taskId").asLong() == taskIds.get(label)) {
                return item;
            }
        }
        throw new AssertionError("응답에서 작업을 찾을 수 없습니다: " + label);
    }

    private void assertTimestamp(JsonNode value, LocalDateTime expected) {
        if (expected == null) {
            assertThat(value.isNull()).isTrue();
        } else {
            assertThat(LocalDateTime.parse(value.asText())).isEqualTo(expected);
        }
    }

    @BeforeEach
    void prepareTestData() {
        taskIds = new LinkedHashMap<>();
        taskSequence = 0;

        // HTTP 요청은 별도 스레드에서 실행되므로 준비 데이터를 먼저 커밋한다.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
            owner = createUser("목록 조회 사용자", passwordHash);
            teammate = createUser("같은 팀 사용자", passwordHash);
            outsider = createUser("다른 팀 사용자", passwordHash);

            team = teams.save(Team.create("조회 테스트 팀", UUID.randomUUID().toString()));
            otherTeam = teams.save(Team.create("다른 팀", UUID.randomUUID().toString()));
            members.save(TeamMember.create(team.getId(), owner.getId(), MemberRole.OWNER));
            members.save(TeamMember.create(team.getId(), teammate.getId(), MemberRole.MEMBER));
            members.save(TeamMember.create(otherTeam.getId(), outsider.getId(), MemberRole.OWNER));

            callerAgent = createAgent(team, owner, "호출 Agent");
            sameOwnerAgent = createAgent(team, owner, "같은 사용자 소유의 다른 Agent");
            teammateAgent = createAgent(team, teammate, "팀원 Agent");
            emptyAgent = createAgent(team, owner, "작업이 없는 Agent");
            otherTeamAgent = createAgent(otherTeam, outsider, "다른 팀 Agent");

            createTask("sentPending", callerAgent, teammateAgent, TaskStatus.PENDING);
            createTask("sentInProgress", callerAgent, teammateAgent, TaskStatus.IN_PROGRESS);
            createTask("sentCompleted", callerAgent, teammateAgent, TaskStatus.COMPLETED);
            createTask("sentFailed", callerAgent, teammateAgent, TaskStatus.FAILED);
            createTask("sentRejected", callerAgent, teammateAgent, TaskStatus.REJECTED);
            createTask("receivedPending", teammateAgent, callerAgent, TaskStatus.PENDING);
            createTask("receivedCompleted", teammateAgent, callerAgent, TaskStatus.COMPLETED);
            createTask("selfPending", callerAgent, callerAgent, TaskStatus.PENDING);
            createTask("sameOwnerSent", sameOwnerAgent, teammateAgent, TaskStatus.PENDING);
            createTask("sameOwnerReceived", teammateAgent, sameOwnerAgent, TaskStatus.FAILED);
            createTask("otherTeamPending", otherTeamAgent, otherTeamAgent, TaskStatus.PENDING);
        });
    }

    private User createUser(String name, String passwordHash) {
        return users.save(User.create(
                "task-query-" + UUID.randomUUID() + "@example.com",
                name, null, passwordHash));
    }

    private TestAgent createAgent(Team targetTeam, User user, String name) {
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        Agent agent = Agent.create(targetTeam.getId(), user.getId(), name,
                "작업 목록 조회 테스트", ToolType.CODEX, "task-query-test");
        agent.issueConnectionToken(AgentTokenHash.sha256(token));
        agent = agents.saveAndFlush(agent);

        return new TestAgent(agent.getId(), targetTeam.getId(), user.getId(), name, token);
    }

    private void createTask(String label, TestAgent requester, TestAgent receiver, TaskStatus status) {
        Task task = tasks.saveAndFlush(Task.create(
                UUID.randomUUID().toString(), requester.teamId(), requester.id(),
                requester.ownerUserId(), receiver.id(), "목록 조회 테스트: " + label));

        if (status == TaskStatus.IN_PROGRESS
                || status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED) {
            task.accept(receiver.id());
            tasks.flush();
        }
        switch (status) {
            case COMPLETED -> task.complete(receiver.id(), "테스트 작업 완료");
            case FAILED -> task.fail(receiver.id(), "테스트 작업 실패", true);
            case REJECTED -> task.reject(receiver.id(), "테스트 작업 거절");
            case PENDING, IN_PROGRESS -> { }
        }
        tasks.flush();

        // 정렬과 페이지 검증이 실행 시각에 의존하지 않도록 테스트 데이터의 시각을 고정한다.
        LocalDateTime createdAt = BASE_TIME.plusMinutes(taskSequence++);
        Timestamp startedAt = task.getStartedAt() == null
                ? null : Timestamp.valueOf(createdAt.plusSeconds(10));
        Timestamp completedAt = task.getCompletedAt() == null
                ? null : Timestamp.valueOf(createdAt.plusSeconds(20));
        jdbc.update("update tasks set created_at = ?, started_at = ?, completed_at = ? where id = ?",
                Timestamp.valueOf(createdAt), startedAt, completedAt, task.getId());
        taskIds.put(label, task.getId());
    }

    private record TestAgent(Long id, Long teamId, Long ownerUserId, String name, String token) {
    }
}
