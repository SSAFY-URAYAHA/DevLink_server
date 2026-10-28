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
import com.devlink.server.domain.task.DeliveryStatus;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.CreateTaskRequest;
import com.devlink.server.repository.*;
import com.devlink.server.service.TaskCreationService;
import org.springframework.test.util.ReflectionTestUtils;
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
    // 전송은 TODO이므로 결과 처리 메서드에 담당자 반환값을 직접 전달한다.
    private Task recordDelivery(Long taskId, TaskDeliveryResult delivery) {
        Task task = tasks.findById(taskId).orElseThrow();
        return ReflectionTestUtils.invokeMethod(service, "saveDeliveryEvent", task, delivery);
    }
    @Autowired TaskCreationService service;

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
    }

    @Test
    void createsTaskAndHistoryWithDeliveryPendingIntegration() {
        var result = service.create(teamId, userId, request);
        assertThat(result.replayed()).isFalse();
        assertThat(result.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(result.version()).isEqualTo(0L);
        assertThat(result.delivery().status()).isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(tasks.findById(result.taskId()).orElseThrow().getDeliveredAt()).isNull();
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(result.taskId()))
                .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED);
    }

    @Test
    void duplicateReturnsExistingTaskWithoutAnotherHistory() {
        var created = service.create(teamId, userId, request);
        var replay = service.create(teamId, userId, request);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.taskId()).isEqualTo(created.taskId());
        assertThat(tasks.findByTeamIdOrderByCreatedAtDesc(teamId)).hasSize(1);
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(created.taskId())).hasSize(1);
    }

    @Test
    void successfulDeliveryAppendsDeliveredHistory() {
        var created = service.create(teamId, userId, request);
        Task updated = recordDelivery(created.taskId(),
                new TaskDeliveryResult(DeliveryStatus.DELIVERED, null));
        assertThat(updated.getDeliveredAt()).isNotNull();
        assertThat(updated.getVersion()).isEqualTo(created.version() + 1);
        assertThat(tasks.findById(created.taskId()).orElseThrow().getDeliveredAt())
                .isNotNull();
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(created.taskId()))
                .extracting(TaskEvent::getEventType)
                .containsExactly(TaskEventType.CREATED, TaskEventType.DELIVERED);
        assertThat(tasks.findById(created.taskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void failedDeliveryAppendsFailureReason() {
        var created = service.create(teamId, userId, request);
        recordDelivery(created.taskId(),
                new TaskDeliveryResult(DeliveryStatus.FAILED, "ACK_TIMEOUT"));
        var history = events.findByTaskIdOrderByCreatedAtAsc(created.taskId());
        assertThat(history).extracting(TaskEvent::getEventType)
                .containsExactly(TaskEventType.CREATED, TaskEventType.DELIVERY_FAILED);
        assertThat(history.getLast().getContent()).isEqualTo("ACK_TIMEOUT");
        assertThat(tasks.findById(created.taskId()).orElseThrow().getDeliveredAt()).isNull();
        assertThat(tasks.findById(created.taskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void deliveryHistoryFailureDoesNotRollBackCreatedTask() {
        var created = service.create(teamId, userId, request);
        doThrow(new IllegalStateException("delivery history failed")).when(events)
                .save(argThat(event -> event.getEventType() == TaskEventType.DELIVERED));
        assertThatThrownBy(() -> recordDelivery(created.taskId(),
                new TaskDeliveryResult(DeliveryStatus.DELIVERED, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tasks.findById(created.taskId())).isPresent();
        assertThat(tasks.findById(created.taskId()).orElseThrow().getDeliveredAt()).isNull();
        assertThat(tasks.findById(created.taskId()).orElseThrow().getVersion()).isEqualTo(created.version());
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(created.taskId()))
                .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED);
    }

    @Test
    void replayReturnsLatestStoredDeliveryResultWithoutAddingHistory() {
        var created = service.create(teamId, userId, request);
        recordDelivery(created.taskId(),
                new TaskDeliveryResult(DeliveryStatus.FAILED, "ACK_TIMEOUT"));
        var failedReplay = service.create(teamId, userId, request);
        assertThat(failedReplay.delivery()).isEqualTo(
                new TaskDeliveryResult(DeliveryStatus.FAILED, "ACK_TIMEOUT"));

        recordDelivery(created.taskId(),
                new TaskDeliveryResult(DeliveryStatus.DELIVERED, null));
        var deliveredReplay = service.create(teamId, userId, request);
        assertThat(deliveredReplay.replayed()).isTrue();
        assertThat(deliveredReplay.delivery()).isEqualTo(
                new TaskDeliveryResult(DeliveryStatus.DELIVERED, null));
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(created.taskId())).hasSize(3);
    }

    @Test
    void createdHistoryFailureRollsBackTask() {
        doThrow(new IllegalStateException("history storage failed")).when(events)
                .save(argThat(event -> event.getEventType() == TaskEventType.CREATED));
        assertThatThrownBy(() -> service.create(teamId, userId, request))
                .isInstanceOf(IllegalStateException.class);
        assertThat(tasks.findByRequesterUserIdAndRequestKey(userId, request.requestKey())).isEmpty();
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
                    .extracting(TaskEvent::getEventType).containsExactly(TaskEventType.CREATED);
        }
    }
}
