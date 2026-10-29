package com.devlink.server.controller;

import java.util.stream.Stream;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.dto.TaskRoutingResult.Status;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.dto.CreateTaskRequest;
import com.devlink.server.dto.CreateTaskResponse;
import com.devlink.server.dto.TaskDeliveryResult;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.handler.GlobalExceptionHandler;
import com.devlink.server.service.TaskCreationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC tests; session login and security-filter integration are separate responsibilities. */
class TaskControllerTest {
    private TaskCreationService service;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();
    private final CreateTaskRequest valid = new CreateTaskRequest("key", 2L, 4L, "Implement feature");

    @BeforeEach
    void setup() {
        service = mock(TaskCreationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TaskController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void createsUsingSessionUserAndReturns201() throws Exception {
        when(service.create(anyLong(), anyLong(), any())).thenReturn(response(false));
        mvc.perform(post("/api/v1/tasks/1").sessionAttr("userId", 3L)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.taskId").value(101))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.delivery.status").value("DELIVERY_NOT_SENT"))
                .andExpect(jsonPath("$.data.delivery.reasonCode").value("RECEIVER_OFFLINE"));
        verify(service).create(1L, 3L, valid);
    }

    @Test
    void successfulDeliveryReturnsDeliveredWithoutReason() throws Exception {
        when(service.create(anyLong(), anyLong(), any())).thenReturn(
                new CreateTaskResponse(101L, 4L, TaskStatus.PENDING, 1L, false,
                        new TaskDeliveryResult(TaskEventType.ACK_PENDING, null)));
        mvc.perform(post("/api/v1/tasks/1").sessionAttr("userId", 3L)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.delivery.status").value("ACK_PENDING"))
                .andExpect(jsonPath("$.data.delivery.reasonCode").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void replayReturns200() throws Exception {
        when(service.create(anyLong(), anyLong(), any())).thenReturn(response(true));
        mvc.perform(post("/api/v1/tasks/1").sessionAttr("userId", 3L)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
    }

    static Stream<CreateTaskRequest> invalidRequests() {
        return Stream.of(
                new CreateTaskRequest(null, 2L, 4L, "x"),
                new CreateTaskRequest(" ", 2L, 4L, "x"),
                new CreateTaskRequest("x".repeat(65), 2L, 4L, "x"),
                new CreateTaskRequest("key", null, 4L, "x"),
                new CreateTaskRequest("key", 0L, 4L, "x"),
                new CreateTaskRequest("key", 2L, -1L, "x"),
                new CreateTaskRequest("key", 2L, 4L, null),
                new CreateTaskRequest("key", 2L, 4L, " "));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidBodyDoesNotReachService(CreateTaskRequest request) throws Exception {
        mvc.perform(post("/api/v1/tasks/1").sessionAttr("userId", 3L)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", ""})
    void malformedBodyReturns400(String body) throws Exception {
        mvc.perform(post("/api/v1/tasks/1").sessionAttr("userId", 3L)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid"})
    void invalidTeamDoesNotReachService(String team) throws Exception {
        mvc.perform(post("/api/v1/tasks/" + team).sessionAttr("userId", 3L)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void missingSessionIdentityDoesNotReachService() throws Exception {
        mvc.perform(post("/api/v1/tasks/1").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(valid)))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void serviceConflictUsesCommonErrorResponse() throws Exception {
        when(service.create(anyLong(), anyLong(), any()))
                .thenThrow(new BusinessException(ErrorCode.REQUEST_KEY_CONFLICT));
        mvc.perform(post("/api/v1/tasks/1").sessionAttr("userId", 3L)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_KEY_CONFLICT"));
    }

    private CreateTaskResponse response(boolean replayed) {
        return new CreateTaskResponse(101L, 4L, TaskStatus.PENDING, 0L, replayed,
                new TaskDeliveryResult(TaskEventType.DELIVERY_NOT_SENT, "RECEIVER_OFFLINE"));
    }

    @Test
    void concurrentRequestKeyViolationReturns409() throws Exception {
        var violation = new ConstraintViolationException("duplicate", new SQLException("duplicate"),
                "uk_tasks_request_key");
        when(service.create(anyLong(), anyLong(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate", violation));
        mvc.perform(post("/api/v1/tasks/1").sessionAttr("userId", 3L)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_KEY_CONFLICT"));
    }

    @Test
    void unrelatedDatabaseViolationIsNotReportedAsDuplicate() throws Exception {
        when(service.create(anyLong(), anyLong(), any()))
                .thenThrow(new DataIntegrityViolationException("storage error"));
        mvc.perform(post("/api/v1/tasks/1").sessionAttr("userId", 3L)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid)))
                .andExpect(status().isInternalServerError());
    }
}
