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
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.user.User;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.ChangeTaskStatusRequest;
import com.devlink.server.repository.*;
import com.devlink.server.service.TaskStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskStatusIntegrationTest extends AbstractIntegrationTest {
    @Autowired TaskStatusService service;
    @MockitoSpyBean TaskRepository tasks;
    @MockitoSpyBean TaskEventRepository events;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired AgentRepository agents;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired jakarta.persistence.EntityManager entityManager;
    Long taskId;
    Long userId;
    Long agentId;

    @BeforeEach
    void setup() {
        String key = UUID.randomUUID().toString();
        userId = users.save(User.create(key + "@test.com", "Tester", null, "hash")).getId();
        Long teamId = teams.save(Team.create("Status", key)).getId();
        agentId = agents.save(Agent.create(teamId, userId, "Receiver", null, ToolType.CODEX, "server")).getId();
        taskId = tasks.save(Task.create(key, teamId, agentId, userId, agentId, "Prompt")).getId();
    }

    private ChangeTaskStatusRequest request(TaskStatus status, Long version) {
        return new ChangeTaskStatusRequest(status, version,
                status == TaskStatus.REJECTED ? "Cannot access project" : null,
                status == TaskStatus.FAILED ? "Build failed" : null,
                status == TaskStatus.FAILED ? Boolean.TRUE : null);
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"IN_PROGRESS", "REJECTED", "FAILED"})
    void persistsTransitionAndHistoryAndReturnsFlushedVersion(TaskStatus target) {
        long version = 0;
        if (target == TaskStatus.FAILED) {
            version = service.changeStatus(taskId, agentId, request(TaskStatus.IN_PROGRESS, version)).version();
        }
        var response = service.changeStatus(taskId, agentId, request(target, version));
        var saved = tasks.findById(taskId).orElseThrow();
        assertThat(response.status()).isEqualTo(target);
        assertThat(response.version()).isGreaterThan(version).isEqualTo(saved.getVersion());
        assertThat(saved.getDeliveredAt()).isNull();
        if (target == TaskStatus.IN_PROGRESS) {
            assertThat(response.startedAt()).isNotNull();
            assertThat(response.completedAt()).isNull();
        } else {
            assertThat(response.completedAt()).isNotNull();
        }
        if (target == TaskStatus.REJECTED) {
            assertThat(response.rejectionReason()).isEqualTo("Cannot access project");
            assertThat(response.failureReason()).isNull();
            assertThat(response.startedAt()).isNull();
            assertThat(response.partiallyModified()).isFalse();
        }
        if (target == TaskStatus.FAILED) {
            assertThat(response.failureReason()).isEqualTo("Build failed");
            assertThat(response.rejectionReason()).isNull();
            assertThat(response.partiallyModified()).isTrue();
        }
        var history = events.findByTaskIdOrderByCreatedAtAsc(taskId);
        assertThat(history).hasSize(target == TaskStatus.FAILED ? 2 : 1);
        TaskEvent last = history.getLast();
        assertThat(last.getEventType()).isEqualTo(TaskEventType.STATUS_CHANGED);
        assertThat(last.getContent()).startsWith(
                (target == TaskStatus.FAILED ? "IN_PROGRESS" : "PENDING") + " → " + target + ": ");
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"IN_PROGRESS", "REJECTED", "FAILED"})
    void otherAgentCannotChangeTaskEvenWithWrongVersion(TaskStatus target) {
        assertThatThrownBy(() -> service.changeStatus(taskId, Long.MAX_VALUE, request(target, 99L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED));
        assertUnchanged();
    }

    @Test
    void missingTaskReturnsNotFound() {
        assertThatThrownBy(() -> service.changeStatus(Long.MAX_VALUE, agentId, request(TaskStatus.IN_PROGRESS, 0L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void staleVersionIsRejectedBeforeStateValidation() {
        service.changeStatus(taskId, agentId, request(TaskStatus.REJECTED, 0L));
        assertThatThrownBy(() -> service.changeStatus(taskId, agentId, request(TaskStatus.IN_PROGRESS, 0L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TASK_VERSION_CONFLICT));
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(taskId)).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"IN_PROGRESS", "REJECTED", "FAILED", "COMPLETED"})
    void cannotAcceptNonPendingTask(TaskStatus initial) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            Task task = tasks.findById(taskId).orElseThrow();
            if (initial == TaskStatus.REJECTED) task.reject(task.getReceiverAgentId(), "No");
            else {
                task.accept(task.getReceiverAgentId());
                if (initial == TaskStatus.FAILED) task.fail(task.getReceiverAgentId(), "Failed", false);
                if (initial == TaskStatus.COMPLETED) task.complete(task.getReceiverAgentId(), "Done");
            }
        });
        Long version = tasks.findById(taskId).orElseThrow().getVersion();
        assertThatThrownBy(() -> service.changeStatus(taskId, agentId, request(TaskStatus.IN_PROGRESS, version)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(taskId)).isEmpty();
    }

    @Test
    void pendingTaskCannotFail() {
        assertThatThrownBy(() -> service.changeStatus(taskId, agentId, request(TaskStatus.FAILED, 0L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TASK_STATE_CONFLICT));
    }

    @Test
    void historyFailureRollsBackTask() {
        doThrow(new IllegalStateException("history failed")).when(events).save(any(TaskEvent.class));
        assertThatThrownBy(() -> service.changeStatus(taskId, agentId, request(TaskStatus.IN_PROGRESS, 0L)))
                .isInstanceOf(IllegalStateException.class);
        assertUnchanged();
    }

    @Test
    void commitFailureRollsBackTaskAndHistory() {
        doAnswer(call -> {
            TaskEvent saved = call.getArgument(0);
            entityManager.persist(saved);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    throw new IllegalStateException("commit failed");
                }
            });
            return saved;
        }).when(events).save(any(TaskEvent.class));
        assertThatThrownBy(() -> service.changeStatus(taskId, agentId, request(TaskStatus.REJECTED, 0L)))
                .isInstanceOf(IllegalStateException.class);
        assertUnchanged();
    }

    @Test
    void concurrentAcceptAndRejectCommitOnlyOneTransition() throws Exception {
        var ready = new CountDownLatch(2);
        doAnswer(call -> {
            var task = java.util.Optional.ofNullable(entityManager.find(Task.class, taskId));
            ready.countDown();
            if (!ready.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent read missing");
            return task;
        }).when(tasks).findById(taskId);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var accept = executor.submit(() -> service.changeStatus(taskId, agentId, request(TaskStatus.IN_PROGRESS, 0L)));
            var reject = executor.submit(() -> service.changeStatus(taskId, agentId, request(TaskStatus.REJECTED, 0L)));
            int successes = 0;
            int conflicts = 0;
            for (var result : java.util.List.of(accept, reject)) {
                try {
                    assertThat(result.get(20, TimeUnit.SECONDS).version()).isEqualTo(1L);
                    successes++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(ObjectOptimisticLockingFailureException.class);
                    conflicts++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        }
        var saved = tasks.findById(taskId).orElseThrow();
        assertThat(saved.getVersion()).isEqualTo(1L);
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(taskId)).singleElement()
                .satisfies(event -> assertThat(event.getContent()).contains(" → " + saved.getStatus() + ": "));
    }

    private void assertUnchanged() {
        var task = tasks.findById(taskId).orElseThrow();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getVersion()).isZero();
        assertThat(task.getStartedAt()).isNull();
        assertThat(task.getCompletedAt()).isNull();
        assertThat(task.getResultText()).isNull();
        assertThat(events.findByTaskIdOrderByCreatedAtAsc(taskId)).isEmpty();
    }
}
