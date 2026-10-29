package com.devlink.server.domain;

import java.time.LocalDateTime;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.exception.InvalidTaskInputException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class TaskTest {
    private Task task() {
        return Task.create("request", 1L, 2L, 3L, 4L, "Implement feature");
    }

    @Test
    void deliveryAndCompletionRecordLifecycle() {
        Task task = task();
        LocalDateTime at = LocalDateTime.now();
        task.markDelivered(at);
        assertThat(task.getDeliveredAt()).isEqualTo(at);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        task.accept(4L);
        assertThat(task.getStartedAt()).isNotNull();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        task.complete(4L, "Done");
        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getResultText()).isEqualTo("Done");
        assertThat(task.getCompletedAt()).isNotNull();
        assertThatThrownBy(() -> task.accept(4L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> task.complete(4L, "Again")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> task.fail(4L, "Again", true)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureRecordsReasonAndPartialModification() {
        Task task = task();
        task.accept(4L);
        task.fail(4L, "Build failed", true);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getResultText()).isEqualTo("Build failed");
        assertThat(task.isPartiallyModified()).isTrue();
        assertThat(task.getCompletedAt()).isNotNull();
        assertThatThrownBy(() -> task.complete(4L, "Done")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsInvalidTransitionsAndUnauthorizedAgentsWithoutMutation() {
        Task task = task();
        assertThatThrownBy(() -> task.complete(4L, "Done")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> task.fail(4L, "Failed", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> task.accept(2L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> task.accept(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        task.accept(4L);
        assertThatThrownBy(() -> task.accept(4L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> task.complete(2L, "Done")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> task.fail(2L, "Failed", true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getCompletedAt()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void rejectsInvalidCompletionResultWithoutMutation(String result) {
        Task task = task();
        task.accept(4L);
        LocalDateTime startedAt = task.getStartedAt();

        assertThatThrownBy(() -> task.complete(4L, result))
                .isInstanceOf(InvalidTaskInputException.class);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getResultText()).isNull();
        assertThat(task.getCompletedAt()).isNull();
        assertThat(task.getStartedAt()).isEqualTo(startedAt);
        assertThat(task.isPartiallyModified()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void rejectsInvalidFailureReasonWithoutMutation(String reason) {
        Task task = task();
        task.accept(4L);
        LocalDateTime startedAt = task.getStartedAt();

        assertThatThrownBy(() -> task.fail(4L, reason, true))
                .isInstanceOf(InvalidTaskInputException.class);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(task.getResultText()).isNull();
        assertThat(task.getCompletedAt()).isNull();
        assertThat(task.isPartiallyModified()).isFalse();
        assertThat(task.getStartedAt()).isEqualTo(startedAt);
    }

    @Test
    void creationRequiresEveryArgument() {
        assertThatExceptionOfType(InvalidTaskInputException.class).isThrownBy(() -> Task.create(null, 1L, 2L, 3L, 4L, "Prompt"));
        assertThatExceptionOfType(InvalidTaskInputException.class).isThrownBy(() -> Task.create(" ", 1L, 2L, 3L, 4L, "Prompt"));
        assertThatExceptionOfType(InvalidTaskInputException.class).isThrownBy(() -> Task.create("key", null, 2L, 3L, 4L, "Prompt"));
        assertThatExceptionOfType(InvalidTaskInputException.class).isThrownBy(() -> Task.create("key", 1L, null, 3L, 4L, "Prompt"));
        assertThatExceptionOfType(InvalidTaskInputException.class).isThrownBy(() -> Task.create("key", 1L, 2L, null, 4L, "Prompt"));
        assertThatExceptionOfType(InvalidTaskInputException.class).isThrownBy(() -> Task.create("key", 1L, 2L, 3L, null, "Prompt"));
        assertThatExceptionOfType(InvalidTaskInputException.class).isThrownBy(() -> Task.create("key", 1L, 2L, 3L, 4L, null));
        assertThatExceptionOfType(InvalidTaskInputException.class).isThrownBy(() -> Task.create("key", 1L, 2L, 3L, 4L, ""));
    }

    @Test
    void receiverRejectsPendingTaskWithoutStartingItOrChangingDelivery() {
        Task task = task();
        LocalDateTime deliveredAt = LocalDateTime.now();
        task.markDelivered(deliveredAt);
        LocalDateTime before = LocalDateTime.now();

        task.reject(4L, "Project access unavailable");

        assertThat(task.getStatus()).isEqualTo(TaskStatus.REJECTED);
        assertThat(task.getResultText()).isEqualTo("Project access unavailable");
        assertThat(task.getStartedAt()).isNull();
        assertThat(task.isPartiallyModified()).isFalse();
        assertThat(task.getDeliveredAt()).isEqualTo(deliveredAt);
        assertThat(task.getCompletedAt()).isBetween(before, LocalDateTime.now());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {2L, 99L})
    void onlyReceiverCanRejectWithoutMutation(Long agentId) {
        Task task = task();
        assertThatThrownBy(() -> task.reject(agentId, "Cannot proceed"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getResultText()).isNull();
        assertThat(task.getStartedAt()).isNull();
        assertThat(task.getCompletedAt()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void rejectionRequiresReasonWithoutMutation(String reason) {
        Task task = task();
        assertThatThrownBy(() -> task.reject(4L, reason))
                .isInstanceOf(InvalidTaskInputException.class);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(task.getResultText()).isNull();
        assertThat(task.getStartedAt()).isNull();
        assertThat(task.getCompletedAt()).isNull();
        assertThat(task.isPartiallyModified()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"IN_PROGRESS", "COMPLETED", "FAILED", "REJECTED"})
    void cannotRejectNonPendingTask(TaskStatus status) {
        Task task = task();
        if (status == TaskStatus.REJECTED) {
            task.reject(4L, "Original rejection");
        } else {
            task.accept(4L);
            if (status == TaskStatus.COMPLETED) task.complete(4L, "Done");
            if (status == TaskStatus.FAILED) task.fail(4L, "Failed", true);
        }
        String originalResult = task.getResultText();
        LocalDateTime startedAt = task.getStartedAt();
        LocalDateTime completedAt = task.getCompletedAt();
        boolean partiallyModified = task.isPartiallyModified();

        assertThatThrownBy(() -> task.reject(4L, "New rejection"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(task.getStatus()).isEqualTo(status);
        assertThat(task.getResultText()).isEqualTo(originalResult);
        assertThat(task.getStartedAt()).isEqualTo(startedAt);
        assertThat(task.getCompletedAt()).isEqualTo(completedAt);
        assertThat(task.isPartiallyModified()).isEqualTo(partiallyModified);
    }

    @Test
    void rejectedTaskCannotBeAcceptedCompletedOrFailed() {
        Task task = task();
        task.reject(4L, "Cannot proceed");
        LocalDateTime completedAt = task.getCompletedAt();
        assertThatThrownBy(() -> task.accept(4L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> task.complete(4L, "Done")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> task.fail(4L, "Failed", true)).isInstanceOf(IllegalStateException.class);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.REJECTED);
        assertThat(task.getResultText()).isEqualTo("Cannot proceed");
        assertThat(task.getCompletedAt()).isEqualTo(completedAt);
        assertThat(task.getStartedAt()).isNull();
        assertThat(task.getDeliveredAt()).isNull();
        assertThat(task.isPartiallyModified()).isFalse();
    }
}
