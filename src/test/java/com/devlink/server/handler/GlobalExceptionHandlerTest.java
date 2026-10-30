package com.devlink.server.handler;

import java.util.stream.Stream;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.task.Task;
import org.hibernate.StaleObjectStateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.hibernate5.HibernateOptimisticLockingFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    void missingResourceReturns404() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(
                        new ThrowingController(new NoResourceFoundException(HttpMethod.GET, "/api/v1/missing")))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/test/conflict"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(ErrorCode.RESOURCE_NOT_FOUND.getMessage()));
    }

    @Test
    void unsupportedHttpMethodReturns405() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ThrowingController(new IllegalStateException("unused")))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        // /test/conflict는 GET만 매핑되어 있으므로 POST는 HttpRequestMethodNotSupportedException을 던진다.
        mvc.perform(post("/test/conflict"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value(ErrorCode.METHOD_NOT_ALLOWED.getMessage()));
    }

    @Test
    void unsupportedMediaTypeReturns415() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ThrowingController(new IllegalStateException("unused")))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        // /test/json은 JSON만 받으므로 text/plain 본문은 HttpMediaTypeNotSupportedException을 던진다.
        mvc.perform(post("/test/json").contentType(MediaType.TEXT_PLAIN).content("plain"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getMessage()));
    }

    @RestController
    static class ThrowingController {
        private final Exception exception;

        ThrowingController(Exception exception) {
            this.exception = exception;
        }

        @GetMapping("/test/conflict")
        public void conflict() throws Exception {
            throw exception;
        }

        @PostMapping(value = "/test/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        public void json() {
        }
    }
}
