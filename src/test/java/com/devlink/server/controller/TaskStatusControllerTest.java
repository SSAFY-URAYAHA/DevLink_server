package com.devlink.server.controller;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.dto.ChangeTaskStatusRequest;
import com.devlink.server.dto.ChangeTaskStatusResponse;
import com.devlink.server.handler.GlobalExceptionHandler;
import com.devlink.server.service.TaskCreationService;
import com.devlink.server.service.TaskStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskStatusControllerTest {
    private TaskStatusService service;
    private MockMvc mvc;
    @BeforeEach
    void setup() {
        service = mock(TaskStatusService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TaskController(mock(TaskCreationService.class), service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void acceptsUsingAgentParameterWithoutSessionAndReturnsUpdatedVersion() throws Exception {
        when(service.changeStatus(anyLong(), anyLong(), any())).thenReturn(new ChangeTaskStatusResponse(
                101L, TaskStatus.IN_PROGRESS, 1L, null, null, false, null, null));
        mvc.perform(patch("/api/v1/tasks/101/status").param("agentId", "3")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        verify(service).changeStatus(101L, 3L, new ChangeTaskStatusRequest(TaskStatus.IN_PROGRESS, 0L, null, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "null", "{", "{\"status\":\"IN_PROGRESS\"}",
        "{\"status\":\"IN_PROGRESS\",\"version\":-1}",
        "{\"status\":\"COMPLETED\",\"version\":0}",
        "{\"status\":\"PENDING\",\"version\":0}",
        "{\"status\":\"UNKNOWN\",\"version\":0}",
        "{\"status\":\"REJECTED\",\"version\":0}",
        "{\"status\":\"REJECTED\",\"version\":0,\"rejectionReason\":\" \"}",
        "{\"status\":\"REJECTED\",\"version\":0,\"rejectionReason\":\"No\",\"partiallyModified\":false}",
        "{\"status\":\"IN_PROGRESS\",\"version\":0,\"failureReason\":\"No\"}",
        "{\"status\":\"FAILED\",\"version\":0,\"failureReason\":\"No\"}",
        "{\"status\":\"FAILED\",\"version\":0,\"failureReason\":\" \",\"partiallyModified\":false}",
        "{\"status\":\"FAILED\",\"version\":0,\"failureReason\":\"No\",\"partiallyModified\":false,\"rejectionReason\":\"No\"}"
    })
    void rejectsInvalidInputBeforeCallingService(String body) throws Exception {
        mvc.perform(patch("/api/v1/tasks/101/status").param("agentId", "3")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid"})
    void rejectsInvalidTaskId(String id) throws Exception {
        mvc.perform(patch("/api/v1/tasks/" + id + "/status").param("agentId", "3")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\",\"version\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid", ""})
    void rejectsInvalidAgentId(String agentId) throws Exception {
        mvc.perform(patch("/api/v1/tasks/101/status").param("agentId", agentId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\",\"version\":0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void requiresAgentParameter() throws Exception {
        mvc.perform(patch("/api/v1/tasks/101/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"IN_PROGRESS\",\"version\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TASK_VERSION_CONFLICT", "TASK_STATE_CONFLICT"})
    void returnsConflictCode(String code) throws Exception {
        when(service.changeStatus(anyLong(), anyLong(), any())).thenThrow(new BusinessException(ErrorCode.valueOf(code)));
        mvc.perform(patch("/api/v1/tasks/101/status").param("agentId", "3")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
    }
}
