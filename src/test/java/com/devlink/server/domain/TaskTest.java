package com.devlink.server.domain;

import java.time.LocalDateTime;
import com.devlink.server.domain.task.Task;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.exception.InvalidTaskInputException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
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
}
