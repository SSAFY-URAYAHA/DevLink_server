package com.devlink.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.devlink.server.common.AgentTokenHash;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.outbox.OutboxEvent;
import com.devlink.server.domain.outbox.OutboxStatus;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.domain.taskfile.ChangeType;
import com.devlink.server.domain.taskfile.TaskFile;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.OutboxEventRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskFileRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class TaskCompleteIntegrationTest extends AbstractIntegrationTest {
    private static final String RESULT = "작업 완료. 테스트 5개 통과\n변경 파일 확인 완료";
    private static final String PASSWORD = "Complete-test-password-123!";
    private static final SecureRandom RANDOM = new SecureRandom();

    @LocalServerPort private int port;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskFileRepository files;
    @Autowired private TaskEventRepository events;
    @Autowired private OutboxEventRepository outbox;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private Team team;
    private User receiverOwner;
    private User outsider;
    private TestAgent requester;
    private TestAgent receiver;
    private TestAgent sameOwnerOther;
    private TestAgent uninvolved;
    private TestAgent otherTeamAgent;
    private Long taskId;
    private Long unrelatedTaskId;
    private DatabaseState before;
    private DatabaseState unrelatedBefore;

    @BeforeEach
    void prepareData() {
        // 실제 HTTP 요청은 다른 스레드에서 처리하므로 준비 데이터를 먼저 커밋한다.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            String hash = passwordEncoder.encode(PASSWORD);
            User requesterOwner = createUser("요청자", hash);
            receiverOwner = createUser("수신자", hash);
            outsider = createUser("다른 팀 사용자", hash);
            team = teams.save(Team.create("완료 테스트 팀", UUID.randomUUID().toString()));
            Team otherTeam = teams.save(Team.create("다른 팀", UUID.randomUUID().toString()));
            members.save(TeamMember.create(team.getId(), requesterOwner.getId(), MemberRole.OWNER));
            members.save(TeamMember.create(team.getId(), receiverOwner.getId(), MemberRole.MEMBER));
            members.save(TeamMember.create(otherTeam.getId(), outsider.getId(), MemberRole.OWNER));
            requester = createAgent(team, requesterOwner, "요청 Agent");
            receiver = createAgent(team, receiverOwner, "수신 Agent");
            sameOwnerOther = createAgent(team, receiverOwner, "수신자 소유의 다른 Agent");
            uninvolved = createAgent(team, requesterOwner, "참여하지 않은 Agent");
            otherTeamAgent = createAgent(otherTeam, outsider, "다른 팀 Agent");
            taskId = createTask(requester, receiver, TaskStatus.IN_PROGRESS).getId();
            unrelatedTaskId = createTask(requester, receiver, TaskStatus.PENDING).getId();
        });
        before = snapshot(taskId);
        unrelatedBefore = snapshot(unrelatedTaskId);
    }

    @Test
    void completesTaskAndPersistsFilesHistoryAndOutboxWithLatestVersion() throws Exception {
        ObjectNode body = validBody(taskId);
        ArrayNode taskFiles = body.putArray("taskFiles");
        for (ChangeType type : ChangeType.values()) {
            ObjectNode file = taskFiles.addObject()
                    .put("relativePath", "src/변경-" + type.name() + ".java")
                    .put("changeType", type.name());
            if (type == ChangeType.DELETED) {
                file.putNull("diffText");
            } else {
                file.put("diffText", "- 이전 코드\n+ 변경 코드");
            }
        }

        JsonNode data = assertSuccess(post(path(taskId), receiver.token(), body));

        assertCompletion(taskId, data, before);
        List<TaskFile> savedFiles = files.findByTaskId(taskId);
        assertThat(savedFiles).hasSize(ChangeType.values().length);
        assertThat(savedFiles).extracting(TaskFile::getChangeType)
                .containsExactlyInAnyOrder(ChangeType.values());
        for (TaskFile saved : savedFiles) {
            assertThat(saved.getRelativePath()).isEqualTo("src/변경-" + saved.getChangeType().name() + ".java");
            assertThat(saved.getDiffText()).isEqualTo(saved.getChangeType() == ChangeType.DELETED
                    ? null : "- 이전 코드\n+ 변경 코드");
        }
        assertThat(snapshot(unrelatedTaskId)).isEqualTo(unrelatedBefore);

        // 76번의 상세 조회에서도 완료 결과·파일·이력과 같은 버전이 보여야 한다.
        JsonNode detail = assertSuccess(get("/api/v1/tasks/" + taskId, requester.token()));
        assertThat(detail.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.path("version").asLong()).isEqualTo(data.path("version").asLong());
        assertThat(detail.path("resultText").asText()).isEqualTo(RESULT);
        assertThat(detail.path("failureReason").isNull()).isTrue();
        assertThat(detail.path("rejectionReason").isNull()).isTrue();
        assertThat(detail.path("taskFiles").size()).isEqualTo(4);
        assertThat(detail.path("events").size()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"omitted", "null", "empty"})
    void completesWithoutFiles(String mode) throws Exception {
        ObjectNode body = validBody(taskId);
        if ("omitted".equals(mode)) {
            body.remove("taskFiles");
        } else if ("null".equals(mode)) {
            body.putNull("taskFiles");
        }

        JsonNode data = assertSuccess(post(path(taskId), receiver.token(), body));

        assertCompletion(taskId, data, before);
        assertThat(files.findByTaskId(taskId)).isEmpty();
    }

    @Test
    void acceptsRelativePathAtFiveHundredCharacterLimit() throws Exception {
        ObjectNode body = validBody(taskId);
        String relativePath = "src/" + "x".repeat(496);
        body.putArray("taskFiles").addObject()
                .put("relativePath", relativePath).put("changeType", "MODIFIED");

        JsonNode data = assertSuccess(post(path(taskId), receiver.token(), body));

        assertCompletion(taskId, data, before);
        assertThat(files.findByTaskId(taskId)).singleElement().satisfies(file -> {
            assertThat(file.getRelativePath()).isEqualTo(relativePath).hasSize(500);
            assertThat(file.getDiffText()).isNull();
        });
    }

    @Test
    void completesSelfDirectedTask() throws Exception {
        Long selfId = new TransactionTemplate(transactionManager).execute(tx ->
                createTask(receiver, receiver, TaskStatus.IN_PROGRESS).getId());
        DatabaseState selfBefore = snapshot(selfId);

        JsonNode data = assertSuccess(post(path(selfId), receiver.token(), validBody(selfId)));

        assertCompletion(selfId, data, selfBefore);
        assertThat(snapshot(taskId)).isEqualTo(before);
    }

    @Test
    void rejectsCompletionWithoutAgentToken() throws Exception {
        assertRejected(post(path(taskId), null, validBody(taskId)), 401, "AUTHENTICATION_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer unknown-token", "Basic credentials", "Bearer", "Bearer first second"})
    void rejectsInvalidAuthorizationHeader(String authorization) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = request(path(taskId)).header("Authorization", authorization)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(validBody(taskId)))).build();
            assertRejected(client.send(request, HttpResponse.BodyHandlers.ofString()),
                    401, "AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void rejectsRevokedAgentToken() throws Exception {
        jdbc.update("update agents set connection_token_hash = null where id = ?", receiver.id());

        assertRejected(post(path(taskId), receiver.token(), validBody(taskId)), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void rejectsTokenAfterAgentOwnerLeavesTeam() throws Exception {
        jdbc.update("delete from team_members where team_id = ? and user_id = ?", team.getId(), receiverOwner.getId());

        assertRejected(post(path(taskId), receiver.token(), validBody(taskId)), 401, "AUTHENTICATION_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"requester", "sameOwnerOther", "uninvolved"})
    void rejectsSameTeamAgentWhoIsNotReceiver(String caller) throws Exception {
        TestAgent agent = switch (caller) {
            case "requester" -> requester;
            case "sameOwnerOther" -> sameOwnerOther;
            case "uninvolved" -> uninvolved;
            default -> throw new IllegalArgumentException(caller);
        };

        assertRejected(post(path(taskId), agent.token(), validBody(taskId)), 403, "ACCESS_DENIED");
    }

    @Test
    void rejectsAgentFromAnotherTeam() throws Exception {
        assertRejected(post(path(taskId), otherTeamAgent.token(), validBody(taskId)), 403, "ACCESS_DENIED");
    }

    @Test
    void doesNotTrustAgentIdQueryParameter() throws Exception {
        assertRejected(post(path(taskId) + "?agentId=" + receiver.id(), requester.token(), validBody(taskId)),
                403, "ACCESS_DENIED");
    }

    @Test
    void rejectsMissingTask() throws Exception {
        assertThat(tasks.existsById(Long.MAX_VALUE)).isFalse();

        assertRejected(post(path(Long.MAX_VALUE), receiver.token(), validBody(taskId)), 404, "RESOURCE_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidTaskId(String id) throws Exception {
        assertRejected(post("/api/v1/tasks/" + id + "/complete", receiver.token(), validBody(taskId)),
                400, "VALIDATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1})
    void rejectsStaleOrFutureVersion(int difference) throws Exception {
        ObjectNode body = validBody(taskId);
        body.put("version", body.path("version").asLong() + difference);

        assertRejected(post(path(taskId), receiver.token(), body), 409, "TASK_VERSION_CONFLICT");
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"PENDING", "COMPLETED", "FAILED", "REJECTED"})
    void rejectsTaskThatIsNotInProgress(TaskStatus status) throws Exception {
        Long targetId = new TransactionTemplate(transactionManager).execute(tx ->
                createTask(requester, receiver, status).getId());
        DatabaseState targetBefore = snapshot(targetId);

        assertError(post(path(targetId), receiver.token(), validBody(targetId)), 409, "TASK_STATE_CONFLICT");

        assertThat(snapshot(targetId)).isEqualTo(targetBefore);
        assertThat(snapshot(taskId)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingVersion", "nullVersion", "negativeVersion",
            "missingResult", "nullResult", "emptyResult", "blankResult",
            "missingPath", "nullPath", "emptyPath", "blankPath", "longPath",
            "missingChangeType", "nullChangeType", "invalidChangeType", "lowercaseChangeType",
            "nullFile", "fileNotObject", "filesNotArray"})
    void rejectsInvalidBodyAndLeavesDatabaseUnchanged(String invalidCase) throws Exception {
        ObjectNode body = validBody(taskId);
        ArrayNode taskFiles = body.putArray("taskFiles");
        ObjectNode file = taskFiles.addObject().put("relativePath", "src/Main.java").put("changeType", "MODIFIED");
        switch (invalidCase) {
            case "missingVersion" -> body.remove("version");
            case "nullVersion" -> body.putNull("version");
            case "negativeVersion" -> body.put("version", -1);
            case "missingResult" -> body.remove("resultText");
            case "nullResult" -> body.putNull("resultText");
            case "emptyResult" -> body.put("resultText", "");
            case "blankResult" -> body.put("resultText", " \t\n");
            case "missingPath" -> file.remove("relativePath");
            case "nullPath" -> file.putNull("relativePath");
            case "emptyPath" -> file.put("relativePath", "");
            case "blankPath" -> file.put("relativePath", "   ");
            case "longPath" -> file.put("relativePath", "x".repeat(501));
            case "missingChangeType" -> file.remove("changeType");
            case "nullChangeType" -> file.putNull("changeType");
            case "invalidChangeType" -> file.put("changeType", "UNKNOWN");
            case "lowercaseChangeType" -> file.put("changeType", "modified");
            case "nullFile" -> taskFiles.removeAll().addNull();
            case "fileNotObject" -> taskFiles.removeAll().add("invalid-file");
            case "filesNotArray" -> body.putObject("taskFiles");
            default -> throw new IllegalArgumentException(invalidCase);
        }

        assertRejected(post(path(taskId), receiver.token(), body), 400, "VALIDATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", ""})
    void rejectsMalformedOrMissingJsonBody(String body) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            var request = request(path(taskId)).header("Authorization", "Bearer " + receiver.token())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            assertRejected(client.send(request, HttpResponse.BodyHandlers.ofString()), 400, "VALIDATION_FAILED");
        }
    }

    @Test
    void rejectsRepeatedCompletionWithoutDuplicatingSavedRecords() throws Exception {
        ObjectNode originalRequest = validBody(taskId);
        JsonNode data = assertSuccess(post(path(taskId), receiver.token(), originalRequest));
        assertCompletion(taskId, data, before);
        DatabaseState completed = snapshot(taskId);

        assertError(post(path(taskId), receiver.token(), originalRequest), 409, "TASK_VERSION_CONFLICT");
        assertError(post(path(taskId), receiver.token(), validBody(taskId)), 409, "TASK_STATE_CONFLICT");

        assertThat(snapshot(taskId)).isEqualTo(completed);
    }

    @Test
    void authenticatesAgentIndependentlyOfUserSession() throws Exception {
        try (HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()) {
            loginAs(client, outsider);
            assertCurrentUser(client, outsider.getId());
            String body = json.writeValueAsString(validBody(taskId));
            assertRejected(client.send(request(path(taskId))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()),
                    401, "AUTHENTICATION_REQUIRED");
            assertRejected(client.send(request(path(taskId)).header("Authorization", "Bearer unknown-token")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()),
                    401, "AUTHENTICATION_REQUIRED");

            JsonNode data = assertSuccess(client.send(request(path(taskId))
                    .header("Authorization", "Bearer " + receiver.token())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()));

            assertCompletion(taskId, data, before);
            assertCurrentUser(client, outsider.getId());
        }
    }

    private void assertCompletion(Long id, JsonNode data, DatabaseState original) {
        Task saved = tasks.findById(id).orElseThrow();
        long originalVersion = ((Number) original.task().get("version")).longValue();
        assertThat(data.path("taskId").asLong()).isEqualTo(id);
        assertThat(data.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(saved.getResultText()).isEqualTo(RESULT);
        assertThat(saved.isPartiallyModified()).isFalse();
        assertThat(saved.getVersion()).isEqualTo(originalVersion + 1);
        assertThat(data.path("version").isIntegralNumber()).isTrue();
        assertThat(data.path("version").asLong()).isEqualTo(saved.getVersion());
        assertThat(saved.getStartedAt()).isNotNull();
        LocalDateTime responseCompletedAt = LocalDateTime.parse(data.path("completedAt").asText());
        // Java의 나노초 값은 PostgreSQL 저장 시 마이크로초로 반올림된다.
        assertThat(responseCompletedAt).isCloseTo(saved.getCompletedAt(), within(1, ChronoUnit.MICROS));
        assertThat(saved.getCompletedAt()).isAfterOrEqualTo(saved.getStartedAt());
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(id)).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo(TaskEventType.STATUS_CHANGED);
            assertThat(event.getContent()).isEqualTo("IN_PROGRESS → COMPLETED: 작업 완료");
            assertThat(event.getCreatedAt()).isNotNull();
        });
        assertThat(outboxForTask(id)).singleElement().satisfies(event -> {
            assertThat(event.getEventType()).isEqualTo("TASK_COMPLETED");
            assertThat(event.getDestination()).isEqualTo("task-events");
            assertThat(event.getMessageKey()).isEqualTo("TASK:" + id);
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(event.getAttemptCount()).isZero();
            assertThat(event.getPublishedAt()).isNull();
            JsonNode message = event.getMessage();
            assertThat(message.path("event_id").asText()).isEqualTo(event.getOutboxId().toString());
            assertThat(message.path("type").asText()).isEqualTo("TASK_COMPLETED");
            assertThat(message.path("task_id").asLong()).isEqualTo(id);
            assertThat(message.path("version").asLong()).isEqualTo(saved.getVersion());
            JsonNode payload = message.path("payload");
            assertThat(payload.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(payload.path("result_text").asText()).isEqualTo(RESULT);
            assertThat(payload.path("partially_modified").isBoolean()).isTrue();
            assertThat(payload.path("partially_modified").asBoolean()).isFalse();
            assertThat(LocalDateTime.parse(payload.path("started_at").asText())).isEqualTo(saved.getStartedAt());
            assertThat(LocalDateTime.parse(payload.path("completed_at").asText())).isEqualTo(responseCompletedAt);
        });
    }

    private void assertRejected(HttpResponse<String> response, int status, String code) throws Exception {
        assertError(response, status, code);
        assertThat(snapshot(taskId)).isEqualTo(before);
        assertThat(snapshot(unrelatedTaskId)).isEqualTo(unrelatedBefore);
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

    private ObjectNode validBody(Long id) {
        ObjectNode body = json.createObjectNode().put("version", tasks.findById(id).orElseThrow().getVersion())
                .put("resultText", RESULT);
        body.putArray("taskFiles");
        return body;
    }

    private String path(Long id) {
        return "/api/v1/tasks/" + id + "/complete";
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
    }

    private HttpResponse<String> post(String path, String token, JsonNode body) throws Exception {
        var builder = request(path).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request(path).header("Authorization", "Bearer " + token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private void loginAs(HttpClient client, User user) throws Exception {
        JsonNode csrf = assertSuccess(client.send(request("/api/v1/auth/csrf").GET().build(),
                HttpResponse.BodyHandlers.ofString()));
        var login = request("/api/v1/auth/login")
                .header(csrf.path("headerName").asText(), csrf.path("token").asText())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("email", user.getEmail(), "password", PASSWORD)))).build();
        assertSuccess(client.send(login, HttpResponse.BodyHandlers.ofString()));
    }

    private void assertCurrentUser(HttpClient client, Long id) throws Exception {
        JsonNode current = assertSuccess(client.send(request("/api/v1/auth/users/me").GET().build(),
                HttpResponse.BodyHandlers.ofString()));
        assertThat(current.path("userId").asLong()).isEqualTo(id);
    }

    private DatabaseState snapshot(Long id) {
        return new DatabaseState(jdbc.queryForMap("select * from tasks where id = ?", id),
                jdbc.queryForObject("select count(*) from task_files where task_id = ?", Long.class, id),
                jdbc.queryForObject("select count(*) from task_events where task_id = ?", Long.class, id),
                jdbc.queryForObject("select count(*) from outbox_events where aggregate_type = 'TASK' and aggregate_id = ?",
                        Long.class, id.toString()));
    }

    private List<OutboxEvent> outboxForTask(Long id) {
        return outbox.findAll().stream()
                .filter(event -> "TASK".equals(event.getAggregateType()) && id.toString().equals(event.getAggregateId()))
                .toList();
    }

    private User createUser(String name, String hash) {
        return users.save(User.create("complete-" + UUID.randomUUID() + "@example.com", name, null, hash));
    }

    private TestAgent createAgent(Team target, User owner, String name) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Agent agent = Agent.create(target.getId(), owner.getId(), name, null, ToolType.CODEX, "complete-test");
        agent.issueConnectionToken(AgentTokenHash.sha256(token));
        agent = agents.saveAndFlush(agent);
        return new TestAgent(agent.getId(), token, target.getId(), owner.getId());
    }

    private Task createTask(TestAgent from, TestAgent to, TaskStatus status) {
        Task task = tasks.saveAndFlush(Task.create(UUID.randomUUID().toString(), from.teamId(), from.id(),
                from.ownerUserId(), to.id(), "완료 등록 테스트"));
        if (status == TaskStatus.IN_PROGRESS || status == TaskStatus.COMPLETED || status == TaskStatus.FAILED) {
            task.accept(to.id());
            tasks.flush();
        }
        switch (status) {
            case COMPLETED -> task.complete(to.id(), "이미 완료된 작업");
            case FAILED -> task.fail(to.id(), "이미 실패한 작업", true);
            case REJECTED -> task.reject(to.id(), "이미 거절한 작업");
            case PENDING, IN_PROGRESS -> { }
        }
        tasks.flush();
        return task;
    }

    private record TestAgent(Long id, String token, Long teamId, Long ownerUserId) { }

    private record DatabaseState(Map<String, Object> task, long fileCount, long eventCount, long outboxCount) { }
}
