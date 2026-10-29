package com.devlink.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
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
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.taskfile.ChangeType;
import com.devlink.server.domain.taskfile.TaskFile;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.user.User;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.realtime.TaskNotificationEvent;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskFileRepository;
import com.devlink.server.repository.TaskRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.service.AgentTokenVerifier;
import com.devlink.server.service.TaskAckService;
import com.devlink.server.service.TaskNotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 아직 없는 상태·완료 API 대신 실제 트랜잭션에서 변경·이벤트 발행을 재현한다. */
@Import(TaskNotificationIntegrationTest.Fixture.class)
class TaskNotificationIntegrationTest extends AbstractIntegrationTest {
    @Autowired private TaskRepository tasks;
    @Autowired private AgentRepository agents;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TaskFileRepository files;
    @Autowired private ConnectionRegistry connections;
    @Autowired private TaskNotificationService notifications;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private AtomicReference<Map<String, AgentPrincipal>> verifiedAgents;
    @MockitoSpyBean private TaskEventRepository history;
    @MockitoSpyBean private TaskAckService acknowledgements;
    @LocalServerPort private int port;
    private Agent requester;
    private Agent receiver;
    private Long taskId;

    @BeforeEach
    void setup() {
        String unique = UUID.randomUUID().toString();
        Long teamId = teams.save(Team.create("Notification", unique)).getId();
        requester = createAgent(teamId, "requester-" + unique);
        receiver = createAgent(teamId, "receiver-" + unique);
        verifiedAgents.set(Map.of("requester", principal(requester), "receiver", principal(receiver)));
        taskId = tasks.save(Task.create(unique, teamId, requester.getId(), requester.getOwnerUserId(),
                receiver.getId(), "알림 본문에 포함하지 않는 요청 내용")).getId();
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"IN_PROGRESS", "FAILED", "COMPLETED"})
    void committedStatusAndResultAreSentOnlyToRequesterWithoutTaskAck(TaskStatus status) throws Exception {
        try (var client = HttpClient.newHttpClient();
                var requesterBroker = connect(client, "requester", requester);
                var receiverBroker = connect(client, "receiver", receiver)) {
            // MCP를 ONLINE으로 보고하지 않아도 연결된 요청자 브로커에는 알림을 보낼 수 있다.
            assertThat(agents.findById(requester.getId()).orElseThrow().getStatus()).isEqualTo(AgentStatus.OFFLINE);

            var snapshot = updateTask(status, true, false, requesterBroker);

            var message = requesterBroker.next();
            assertThat(message.path("type").asText()).isEqualTo(
                    status == TaskStatus.COMPLETED ? "TASK_COMPLETED" : "TASK_STATUS_CHANGED");
            assertThat(message.path("event_id").asText()).isEqualTo(snapshot.eventId());
            assertThat(message.path("payload").path("task_id").asLong()).isEqualTo(taskId);
            assertThat(message.path("payload").path("status").asText()).isEqualTo(status.name());
            assertThat(message.path("payload").path("version").asLong()).isEqualTo(snapshot.payload().version());
            if (status != TaskStatus.IN_PROGRESS) {
                assertThat(message.path("payload").path("result_text").asText()).isEqualTo("보존할 처리 결과");
                assertThat(files.findByTaskId(taskId)).hasSize(1);
            }
            assertThat(receiverBroker.listener.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
            assertThat(tasks.findById(taskId).orElseThrow().getVersion()).isEqualTo(snapshot.payload().version());
            assertThat(history.findByTaskIdOrderByCreatedAtAsc(taskId))
                    .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.STATUS_CHANGED);
            verify(acknowledgements, never()).register(anyLong(), anyString(), any(), any());
        }
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"IN_PROGRESS", "FAILED", "COMPLETED"})
    void rollbackDoesNotSendNotificationOrKeepStatusResultFilesOrHistory(TaskStatus status) throws Exception {
        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "requester", requester)) {
            updateTask(status, true, true, broker);

            assertThat(broker.listener.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
            Task task = tasks.findById(taskId).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(task.getResultText()).isNull();
            assertThat(task.getVersion()).isZero();
            assertThat(files.findByTaskId(taskId)).isEmpty();
            assertThat(history.findByTaskIdOrderByCreatedAtAsc(taskId)).isEmpty();
        }
    }

    @Test
    void disconnectedRequesterRecordsFailureAndPreservesCompletionAndFiles() throws Exception {
        var event = updateTask(TaskStatus.COMPLETED, true, false, null);

        Task saved = tasks.findById(taskId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(saved.getResultText()).isEqualTo("보존할 처리 결과");
        assertThat(saved.getVersion()).isEqualTo(event.payload().version());
        assertThat(files.findByTaskId(taskId)).hasSize(1);
        var savedHistory = history.findByTaskIdOrderByCreatedAtAsc(taskId);
        assertThat(savedHistory).extracting(TaskEvent::getEventType)
                .containsExactly(TaskEventType.STATUS_CHANGED, TaskEventType.NOTIFY_FAILED);
        var content = mapper.readTree(savedHistory.getLast().getContent());
        assertThat(content.path("reason_code").asText()).isEqualTo("CONNECTION_UNAVAILABLE");
        assertThat(content.path("event_id").asText()).isEqualTo(event.eventId());
    }

    @Test
    void failureHistoryInsertErrorCannotRollBackCommittedCompletion() {
        doThrow(new IllegalStateException("Notification history unavailable")).when(history)
                .save(argThat(event -> event.getEventType() == TaskEventType.NOTIFY_FAILED));

        var event = updateTask(TaskStatus.COMPLETED, true, false, null);

        Task saved = tasks.findById(taskId).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(saved.getResultText()).isEqualTo("보존할 처리 결과");
        assertThat(saved.getVersion()).isEqualTo(event.payload().version());
        assertThat(files.findByTaskId(taskId)).hasSize(1);
        assertThat(history.findByTaskIdOrderByCreatedAtAsc(taskId))
                .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.STATUS_CHANGED);
    }

    @Test
    void eventPublishedOutsideTransactionDoesNotSend() throws Exception {
        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "requester", requester)) {
            var event = updateTask(TaskStatus.IN_PROGRESS, false, false, null);

            events.publishEvent(event);

            assertThat(broker.listener.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
            assertThat(history.findByTaskIdOrderByCreatedAtAsc(taskId))
                    .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.STATUS_CHANGED);
        }
    }

    @Test
    void directCallInsideTransactionIsRejectedBeforeSending() throws Exception {
        try (var client = HttpClient.newHttpClient(); var broker = connect(client, "requester", requester)) {
            var event = updateTask(TaskStatus.IN_PROGRESS, false, false, null);
            var transaction = new TransactionTemplate(transactionManager);

            assertThatThrownBy(() -> transaction.execute(status -> notifications.sendNotification(event)))
                    .isInstanceOf(IllegalTransactionStateException.class);

            assertThat(broker.listener.messages.poll(150, TimeUnit.MILLISECONDS)).isNull();
            assertThat(tasks.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        }
    }

    private TaskNotificationEvent updateTask(TaskStatus status, boolean publish, boolean rollback, TestBroker broker) {
        var transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(tx -> {
            Task task = tasks.findById(taskId).orElseThrow();
            task.accept(receiver.getId());
            if (status == TaskStatus.COMPLETED) {
                task.complete(receiver.getId(), "보존할 처리 결과");
            } else if (status == TaskStatus.FAILED) {
                task.fail(receiver.getId(), "보존할 처리 결과", true);
            }
            if (status != TaskStatus.IN_PROGRESS) {
                files.save(TaskFile.of(taskId, "src/Example.java", ChangeType.MODIFIED, "saved diff"));
            }
            history.save(TaskEvent.of(taskId, TaskEventType.STATUS_CHANGED, status.name()));
            // 다른 담당자가 붙일 부분을 재현한다. flush로 확정된 새 버전을 스냅샷에 담는다.
            em.flush();
            var event = status == TaskStatus.COMPLETED
                    ? TaskNotificationEvent.completed(task) : TaskNotificationEvent.statusChanged(task);
            if (publish) {
                events.publishEvent(event);
            }
            if (broker != null) {
                assertThat(broker.listener.messages).as("커밋 전에는 알림이 없어야 함").isEmpty();
            }
            if (rollback) {
                tx.setRollbackOnly();
            }
            return event;
        });
    }

    private Agent createAgent(Long teamId, String name) {
        User user = users.save(User.create(name + "@notification.test", "Notification", null, "test-hash"));
        return agents.save(Agent.create(teamId, user.getId(), name, null, ToolType.CODEX, "server"));
    }

    private AgentPrincipal principal(Agent agent) {
        return new AgentPrincipal(agent.getId(), agent.getOwnerUserId(), agent.getTeamId());
    }

    private TestBroker connect(HttpClient client, String token, Agent agent) throws Exception {
        var listener = new BrokerListener();
        var socket = client.newWebSocketBuilder()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                .buildAsync(URI.create("ws://localhost:" + port + "/ws/broker"), listener)
                .get(5, TimeUnit.SECONDS);
        var broker = new TestBroker(agent.getId(), socket, listener);
        assertThat(broker.next().path("type").asText()).isEqualTo("BROKER_READY");
        return broker;
    }

    private final class TestBroker implements AutoCloseable {
        private final long agentId;
        private final WebSocket socket;
        private final BrokerListener listener;

        private TestBroker(long agentId, WebSocket socket, BrokerListener listener) {
            this.agentId = agentId;
            this.socket = socket;
            this.listener = listener;
        }

        private JsonNode next() throws Exception {
            String message = listener.messages.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("서버 알림").isNotNull();
            return mapper.readTree(message);
        }

        @Override
        public void close() {
            socket.abort();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(connections.findByAgentId(Long.toString(agentId))).isEmpty());
        }
    }

    private static class BrokerListener implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                messages.add(text.toString());
                text.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean
        AtomicReference<Map<String, AgentPrincipal>> verifiedAgents() {
            return new AtomicReference<>(Map.of());
        }

        @Bean
        AgentTokenVerifier agentTokenVerifier(AtomicReference<Map<String, AgentPrincipal>> verifiedAgents) {
            return token -> Optional.ofNullable(verifiedAgents.get().get(token));
        }
    }
}
