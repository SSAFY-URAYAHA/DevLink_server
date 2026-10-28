package com.devlink.server;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;

import com.devlink.server.domain.task.TaskStatus;

import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.CreateTaskRequest;
import com.devlink.server.repository.*;
import com.devlink.server.service.TaskCreationService;
import com.devlink.server.service.TaskRoutingService;
import com.devlink.server.dto.TaskRoutingResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.devlink.server.dto.TaskDeliveryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskCreationIntegrationTest extends AbstractIntegrationTest {
    @Autowired TaskCreationService service;
    @MockitoBean TaskRoutingService routing;

    @MockitoSpyBean TaskRepository tasks;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired AgentRepository agents;
    @MockitoSpyBean TaskEventRepository events;
    Long teamId;
    Long userId;
    Long agentId;
    CreateTaskRequest request;

    @BeforeEach
    void setup() {
        String key = UUID.randomUUID().toString();
        userId = users.save(User.create(key + "@test.com", "Tester", null, "hash")).getId();
        teamId = teams.save(Team.create("Test", key)).getId();
        agentId = agents.save(Agent.create(teamId, userId, "Agent", null, ToolType.CODEX, "project")).getId();
        request = new CreateTaskRequest(key, agentId, agentId, "Implement feature");
        when(routing.sendTask(anyLong())).thenAnswer(invocation ->
                new TaskRoutingResult(invocation.getArgument(0), TaskRoutingResult.Status.SENT, "event", "connection"));
    }

    @Test
    void routesAfterCreatedAndDeliveringCommitThenStoresDelivered() {
        when(routing.sendTask(anyLong())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Long taskId = invocation.getArgument(0);
            assertThat(tasks.findById(taskId)).isPresent();
            assertThat(events.findByTaskIdOrderByCreatedAtAsc(taskId))
                    .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED, TaskEventType.DELIVERING);
            assertThat(service.create(teamId, userId, request).delivery()).isNull();
            return new TaskRoutingResult(taskId, TaskRoutingResult.Status.SENT, "event", "connection");
        });
        var result = service.create(teamId, userId, request);
        assertThat(result.replayed()).isFalse();
        assertThat(result.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.delivery().status()).isEqualTo(TaskEventType.DELIVERED);
        assertThat(tasks.findById(result.taskId()).orElseThrow().getDeliveredAt()).isNotNull();
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(result.taskId()))
                .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED, TaskEventType.DELIVERING, TaskEventType.DELIVERED);
        verify(routing).sendTask(result.taskId());
    }

    @Test
    void duplicateReturnsExistingTaskWithoutAnotherHistory() {
        var created = service.create(teamId, userId, request);
        var replay = service.create(teamId, userId, request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.taskId()).isEqualTo(created.taskId());
        assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(teamId)).hasSize(1);
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(created.taskId())).hasSize(3);
        verify(routing, times(1)).sendTask(created.taskId());
    }

    @ParameterizedTest
    @EnumSource(value = TaskRoutingResult.Status.class, names = {
            "RECEIVER_OFFLINE", "CONNECTION_UNAVAILABLE", "CONNECTION_REPLACED"})
    void routingFailureIsRecordedAndReturned(TaskRoutingResult.Status status) {
        when(routing.sendTask(anyLong())).thenAnswer(invocation ->
                new TaskRoutingResult(invocation.getArgument(0), status, null, null));
        var result = service.create(teamId, userId, request);
        assertThat(result.delivery()).isEqualTo(new TaskDeliveryResult(TaskEventType.DELIVERY_FAILED, status.name()));
        assertThat(result.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(tasks.findById(result.taskId()).orElseThrow().getDeliveredAt()).isNull();
        var history = events.findByTaskIdOrderByCreatedAtAsc(result.taskId());
        assertThat(history).extracting(TaskEvent::getEventType)
                .containsExactly(TaskEventType.CREATED, TaskEventType.DELIVERING, TaskEventType.DELIVERY_FAILED);
        assertThat(history.getLast().getContent()).isEqualTo(status.name());
        assertThat(service.create(teamId, userId, request).delivery()).isEqualTo(result.delivery());
        verify(routing, times(1)).sendTask(result.taskId());
    }

    @ParameterizedTest
    @EnumSource(value = TaskRoutingResult.Status.class, names = {"TRANSPORT_ERROR", "TASK_NOT_PENDING"})
    void nonSentRoutingStoresFailure(TaskRoutingResult.Status status) {
        when(routing.sendTask(anyLong())).thenAnswer(invocation ->
                new TaskRoutingResult(invocation.getArgument(0), status, null, null));
        var result = service.create(teamId, userId, request);
        assertThat(result.delivery()).isEqualTo(new TaskDeliveryResult(TaskEventType.DELIVERY_FAILED, status.name()));
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(result.taskId()))
                .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED, TaskEventType.DELIVERING, TaskEventType.DELIVERY_FAILED);
    }

    @Test
    void replayReturnsStoredSentResultWithoutResending() {
        var created = service.create(teamId, userId, request);
        var replay = service.create(teamId, userId, request);
        assertThat(replay.delivery()).isEqualTo(created.delivery());
        assertThat(replay.delivery().status()).isEqualTo(TaskEventType.DELIVERED);
        verify(routing, times(1)).sendTask(created.taskId());
    }

    @Test
    void routingHistoryFailureKeepsCreatedTask() {
        doThrow(new IllegalStateException("routing history failed")).when(events)
                .save(argThat(event -> event.getEventType() == TaskEventType.DELIVERED));
        assertThatThrownBy(() -> service.create(teamId, userId, request))
                .isInstanceOf(IllegalStateException.class);
        var task = tasks.findByRequesterUserIdAndRequestKey(userId, request.requestKey()).orElseThrow();
        assertThat(task.getDeliveredAt()).isNull();
        assertThat(task.getVersion()).isEqualTo(0L);
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(task.getId()))
                .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED, TaskEventType.DELIVERING);
    }
    @Test
    void createdHistoryFailureRollsBackTask() {
        doThrow(new IllegalStateException("history storage failed")).when(events)
                .save(argThat(event -> event.getEventType() == TaskEventType.CREATED));
        assertThatThrownBy(() -> service.create(teamId, userId, request))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tasks.findByRequesterUserIdAndRequestKey(userId, request.requestKey())).isEmpty();
        verifyNoInteractions(routing);
    }

    @Test
    void missingReceiverDoesNotCreateTask() {
        var invalid = new CreateTaskRequest(request.requestKey(), agentId, Long.MAX_VALUE, "Prompt");
        assertThatThrownBy(() -> service.create(teamId, userId, invalid))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(tasks.findByRequesterUserIdAndRequestKey(userId, request.requestKey())).isEmpty();
    }

    @Test
    void sameKeyWithDifferentPayloadConflicts() {
        service.create(teamId, userId, request);
        var changed = new CreateTaskRequest(request.requestKey(), agentId, agentId, "Different prompt");
        assertThatThrownBy(() -> service.create(teamId, userId, changed))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.REQUEST_KEY_CONFLICT));
    }

    @Test
    void concurrentInsertConflictPropagatesAndOnlyOneTaskAndHistoryCommit() throws Exception {
        var ready = new CountDownLatch(2);
        // Force both requests past the same empty pre-check; INSERT and rollback use PostgreSQL.
        doAnswer(invocation -> {
            ready.countDown();
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Missing concurrent request");
            return java.util.Optional.empty();
        }).when(tasks).findByRequesterUserIdAndRequestKey(userId, request.requestKey());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.create(teamId, userId, request));
            var second = executor.submit(() -> service.create(teamId, userId, request));
            int successes = 0;
            int conflicts = 0;
            for (var future : java.util.List.of(first, second)) {
                try {
                    assertThat(future.get(20, TimeUnit.SECONDS).replayed()).isFalse();
                    successes++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(DataIntegrityViolationException.class);
                    conflicts++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            var saved = tasks.findByTeamIdOrderByCreatedAtDesc(teamId);
            assertThat(saved).hasSize(1);
            assertThat(events.findByTaskIdOrderByCreatedAtAsc(saved.getFirst().getId()))
                    .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED, TaskEventType.DELIVERING, TaskEventType.DELIVERED);
        }
    }
}
