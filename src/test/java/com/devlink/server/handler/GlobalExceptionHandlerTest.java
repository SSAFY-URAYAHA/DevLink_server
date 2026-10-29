package com.devlink.server.handler;

import java.util.stream.Stream;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.task.Task;
import org.hibernate.StaleObjectStateException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.hibernate5.HibernateOptimisticLockingFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GlobalExceptionHandlerTest {
    static Stream<RuntimeException> taskConflicts() {
        return Stream.of(
                new ObjectOptimisticLockingFailureException(Task.class, 101L),
                new ObjectOptimisticLockingFailureException(Task.class.getName(), 101L),
                new HibernateOptimisticLockingFailureException(
                        new StaleObjectStateException(Task.class.getName(), 101L)),
                new BusinessException(ErrorCode.TASK_VERSION_CONFLICT));
    }

    static Stream<RuntimeException> otherConflicts() {
        return Stream.of(
                new ObjectOptimisticLockingFailureException(Agent.class, 101L),
                new ObjectOptimisticLockingFailureException(Agent.class.getName(), 101L),
                new ObjectOptimisticLockingFailureException("Task", 101L),
                new ObjectOptimisticLockingFailureException((String) null, 101L));
    }

    @ParameterizedTest
    @MethodSource("taskConflicts")
    void taskConflictReturns409(RuntimeException exception) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ThrowingController(exception))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.code").value("TASK_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.message").value(ErrorCode.TASK_VERSION_CONFLICT.getMessage()));
    }

    @ParameterizedTest
    @MethodSource("otherConflicts")
    void otherOrUnknownEntityKeepsExisting500Response(RuntimeException exception) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ThrowingController(exception))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }

    @RestController
    static class ThrowingController {
        private final RuntimeException exception;

        ThrowingController(RuntimeException exception) {
            this.exception = exception;
        }

        @GetMapping("/test/conflict")
        public void conflict() {
            throw exception;
        }
    }
}
