package com.devlink.server.service;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.dto.TaskNotificationResult.Status;
import com.devlink.server.realtime.BrokerMessageSender;
import com.devlink.server.realtime.ConnectionRegistry;
import com.devlink.server.realtime.TaskNotificationEvent;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskNotificationServiceTest {
    private final TaskRepository tasks = mock(TaskRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final ConnectionRegistry connections = new ConnectionRegistry();
    // 실제 서버처럼 LocalDateTime을 ISO 문자열로 직렬화해 알림 본문을 검증한다.
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final BrokerMessageSender sender = new BrokerMessageSender(connections, mapper);
    private final AgentPresenceService presence = mock(AgentPresenceService.class);
    private final TaskNotificationFailureRecorder failures = mock(TaskNotificationFailureRecorder.class);
    private final TaskNotificationService notifications = new TaskNotificationService(
            tasks, agents, connections, sender, presence, failures);
    private final Task task = Task.create("private-request-key", 31L, 1L, 20L, 2L, "private-prompt");
    private final Agent requester = Agent.create(31L, 20L, "Requester", null, ToolType.CODEX, "server");

    TaskNotificationServiceTest() {
        ReflectionTestUtils.setField(task, "id", 7L);
        ReflectionTestUtils.setField(task, "version", 1L);
        task.accept(2L);
        ReflectionTestUtils.setField(requester, "id", 1L);
        when(tasks.findById(7L)).thenReturn(Optional.of(task));
        when(agents.findById(1L)).thenReturn(Optional.of(requester));
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"IN_PROGRESS", "FAILED", "COMPLETED"})
    void sendsCorrectStatusAndResultSnapshotToRequester(TaskStatus state) throws Exception {
        if (state == TaskStatus.FAILED) {
            task.fail(2L, "테스트 실패", true);
        } else if (state == TaskStatus.COMPLETED) {
            task.complete(2L, "작업 완료");
        }
        var event = event();
        WebSocketSession session = registerRequester();

        assertThat(notifications.sendNotification(event).status()).isEqualTo(Status.SENT);

        var message = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(message.capture());
        var json = mapper.readTree(message.getValue().getPayload());
        assertThat(json.path("type").asText()).isEqualTo(
                state == TaskStatus.COMPLETED ? "TASK_COMPLETED" : "TASK_STATUS_CHANGED");
        assertThat(json.path("event_id").asText()).isEqualTo(event.eventId());
        assertThat(json.path("payload").path("task_id").asLong()).isEqualTo(7);
        assertThat(json.path("payload").path("requester_agent_id").asLong()).isEqualTo(1);
        assertThat(json.path("payload").path("status").asText()).isEqualTo(state.name());
        assertThat(json.path("payload").path("version").asLong()).isEqualTo(1);
        if (state != TaskStatus.IN_PROGRESS) {
            assertThat(json.path("payload").path("result_text").asText()).isEqualTo(task.getResultText());
            assertThat(json.path("payload").path("partially_modified").asBoolean()).isEqualTo(task.isPartiallyModified());
        }
        assertThat(message.getValue().getPayload()).doesNotContain("private-prompt", "private-request-key",
                "requester_user_id", "token");
        assertThat(task.getStatus()).isEqualTo(state);
        assertThat(task.getVersion()).isEqualTo(1L);
        verify(tasks, never()).save(any());
        verifyNoInteractions(presence, failures);
    }

    @Test
    void snapshotIsNotReplacedWithLaterDatabaseStatus() throws Exception {
        var accepted = event();
        task.complete(2L, "나중에 완료된 결과");
        ReflectionTestUtils.setField(task, "version", 2L);
        WebSocketSession session = registerRequester();

        notifications.sendNotification(accepted);

        var message = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(message.capture());
        var payload = mapper.readTree(message.getValue().getPayload()).path("payload");
        assertThat(payload.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(payload.path("version").asLong()).isEqualTo(1);
        assertThat(payload.path("result_text").isNull()).isTrue();
    }

    @Test
    void rejectedTaskNotifiesRequesterWithReasonAndNoStartTime() throws Exception {
        Task rejected = rejectedTask();
        when(tasks.findById(7L)).thenReturn(Optional.of(rejected));
        WebSocketSession session = registerRequester();
        var event = TaskNotificationEvent.statusChanged(rejected);

        assertThat(notifications.sendNotification(event).status()).isEqualTo(Status.SENT);

        var message = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(message.capture());
        var json = mapper.readTree(message.getValue().getPayload());
        assertThat(json.path("type").asText()).isEqualTo("TASK_STATUS_CHANGED");
        var payload = json.path("payload");
        assertThat(payload.path("status").asText()).isEqualTo("REJECTED");
        assertThat(payload.path("result_text").asText()).isEqualTo("담당 범위가 아니라 거절합니다");
        assertThat(payload.path("started_at").isNull()).isTrue();
        assertThat(payload.path("completed_at").isTextual()).isTrue();
        assertThat(payload.path("partially_modified").asBoolean()).isFalse();
        assertThat(rejected.getStatus()).isEqualTo(TaskStatus.REJECTED);
        assertThat(rejected.getVersion()).isEqualTo(1L);
        verifyNoInteractions(presence, failures);
    }

    @ParameterizedTest
    @ValueSource(strings = {"resultText", "completedAt"})
    void rejectedNotificationStillRequiresReasonAndCompletionTime(String requiredField) {
        Task rejected = rejectedTask();
        ReflectionTestUtils.setField(rejected, requiredField, null);

        assertThatIllegalArgumentException().isThrownBy(() -> TaskNotificationEvent.statusChanged(rejected));
    }

    @Test
    void inProgressNotificationStillRequiresStartTime() {
        ReflectionTestUtils.setField(task, "startedAt", null);

        assertThatIllegalArgumentException().isThrownBy(() -> TaskNotificationEvent.statusChanged(task));
    }

    @Test
    void connectedBrokerReceivesNotificationEvenWhenMcpAgentIsOffline() {
        registerRequester();
        requester.markOffline();

        assertThat(notifications.sendNotification(event()).status()).isEqualTo(Status.SENT);
    }

    @Test
    void noConnectionRecordsFailureWithoutChangingTask() {
        var event = event();

        assertThat(notifications.sendNotification(event).status()).isEqualTo(Status.CONNECTION_UNAVAILABLE);

        verify(failures).record(event, Status.CONNECTION_UNAVAILABLE);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getVersion()).isEqualTo(1L);
    }

    @Test
    void missingRequesterDoesNotSendToAnotherAgent() {
        when(agents.findById(1L)).thenReturn(Optional.empty());

        assertThat(notifications.sendNotification(event()).status()).isEqualTo(Status.REQUESTER_UNAVAILABLE);
    }

    @ParameterizedTest
    @CsvSource({"99,20", "31,99"})
    void requesterMustStillBelongToTaskTeamAndOwner(long teamId, long ownerId) throws Exception {
        WebSocketSession session = registerRequester();
        ReflectionTestUtils.setField(requester, "teamId", teamId);
        ReflectionTestUtils.setField(requester, "ownerUserId", ownerId);

        assertThat(notifications.sendNotification(event()).status()).isEqualTo(Status.REQUESTER_UNAVAILABLE);

        verify(session, never()).sendMessage(any());
    }

    @ParameterizedTest
    @CsvSource({"99,20,31", "1,99,31", "1,20,99"})
    void sessionIdentityMustMatchRequester(long agentId, long ownerId, long teamId) throws Exception {
        WebSocketSession session = registerRequester();
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new AgentPrincipal(agentId, ownerId, teamId), null, List.of()));

        assertThat(notifications.sendNotification(event()).status()).isEqualTo(Status.REQUESTER_UNAVAILABLE);

        verify(session, never()).sendMessage(any());
    }

    @Test
    void invalidatedAuthenticationCannotReceiveNotification() throws Exception {
        WebSocketSession session = registerRequester();
        ((Authentication) session.getPrincipal()).setAuthenticated(false);

        assertThat(notifications.sendNotification(event()).status()).isEqualTo(Status.REQUESTER_UNAVAILABLE);

        verify(session, never()).sendMessage(any());
    }

    @Test
    void futureOrMismatchedEventCannotSendPrivateResult() throws Exception {
        var event = event();
        ReflectionTestUtils.setField(task, "version", 0L);
        WebSocketSession session = registerRequester();

        assertThat(notifications.sendNotification(event).status()).isEqualTo(Status.INVALID_EVENT);

        verify(session, never()).sendMessage(any());
        verify(failures).record(event, Status.INVALID_EVENT);
    }

    @Test
    void failureToRecordNotificationErrorDoesNotEscape() {
        var event = event();
        doThrow(new IllegalStateException("History unavailable")).when(failures)
                .record(event, Status.CONNECTION_UNAVAILABLE);

        assertThat(notifications.sendNotification(event).status()).isEqualTo(Status.CONNECTION_UNAVAILABLE);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    void transportFailureCleansOnlyFailedConnectionAndPreservesCompletedResult() throws Exception {
        task.complete(2L, "완료 결과 보존");
        WebSocketSession session = registerRequester();
        doThrow(new IOException("Socket failed")).when(session).sendMessage(any());

        assertThat(notifications.sendNotification(event()).status()).isEqualTo(Status.TRANSPORT_ERROR);

        assertThat(connections.findByAgentId("1")).isEmpty();
        verify(session).close(CloseStatus.SERVER_ERROR);
        verify(presence).markOfflineIfDisconnected(1L);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getResultText()).isEqualTo("완료 결과 보존");
    }

    @Test
    void oldSendFailureCannotRemoveReplacementConnection() throws Exception {
        WebSocketSession previous = registerRequester();
        WebSocketSession current = session("new");
        doAnswer(invocation -> {
            connections.register("1", current);
            throw new IOException("Previous socket failed after reconnect");
        }).when(previous).sendMessage(any());

        assertThat(notifications.sendNotification(event()).status()).isEqualTo(Status.TRANSPORT_ERROR);

        assertThat(connections.findByAgentId("1")).contains(current);
        verifyNoInteractions(presence);
        verify(current, never()).sendMessage(any());
    }

    @Test
    void queuedNotificationDoesNotAutomaticallyRetryOnReplacement() throws Exception {
        WebSocketSession previous = registerRequester();
        WebSocketSession current = session("new");
        BrokerMessageSender replacing = new BrokerMessageSender(connections, mapper) {
            @Override
            public boolean sendIfCurrent(String agentId, WebSocketSession session, Object message) throws IOException {
                connections.register(agentId, current);
                return super.sendIfCurrent(agentId, session, message);
            }
        };
        var service = new TaskNotificationService(tasks, agents, connections, replacing, presence, failures);

        assertThat(service.sendNotification(event()).status()).isEqualTo(Status.CONNECTION_CHANGED);

        verify(previous, never()).sendMessage(any());
        verify(current, never()).sendMessage(any());
    }

    @Test
    void notificationFactoriesRejectWrongStateOrUnsavedTask() {
        assertThatIllegalArgumentException().isThrownBy(() -> TaskNotificationEvent.completed(task));
        task.complete(2L, "완료");
        assertThatIllegalArgumentException().isThrownBy(() -> TaskNotificationEvent.statusChanged(task));
        Task unsaved = Task.create("key", 31L, 1L, 20L, 2L, "prompt");
        assertThatIllegalArgumentException().isThrownBy(() -> TaskNotificationEvent.statusChanged(unsaved));
    }

    private TaskNotificationEvent event() {
        return task.getStatus() == TaskStatus.COMPLETED
                ? TaskNotificationEvent.completed(task) : TaskNotificationEvent.statusChanged(task);
    }

    private Task rejectedTask() {
        // 수락을 거치지 않고 PENDING에서 거절된 실제 도메인 흐름을 사용한다.
        Task rejected = Task.create("rejected-key", 31L, 1L, 20L, 2L, "prompt");
        ReflectionTestUtils.setField(rejected, "id", 7L);
        ReflectionTestUtils.setField(rejected, "version", 1L);
        rejected.reject(2L, "담당 범위가 아니라 거절합니다");
        return rejected;
    }

    private WebSocketSession registerRequester() {
        WebSocketSession session = session("requester");
        connections.register("1", session);
        return session;
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn(id);
        when(session.getPrincipal()).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                new AgentPrincipal(1, 20, 31), null, List.of()));
        return session;
    }
}
