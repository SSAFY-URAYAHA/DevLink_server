package com.devlink.server.controller;

import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.task.TaskStatus;
import com.devlink.server.dto.AgentCreateTaskRequest;
import com.devlink.server.dto.CreateTaskRequest;
import com.devlink.server.dto.CreateTaskResponse;
import com.devlink.server.service.TaskAuthorizationService;
import com.devlink.server.service.TaskCreationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTaskControllerTest {
    private final TaskCreationService tasks = mock(TaskCreationService.class);
    private final TaskAuthorizationService authorization = mock(TaskAuthorizationService.class);
    private final AgentTaskController controller = new AgentTaskController(tasks, authorization);

    @Test
    void createsTaskUsingVerifiedAgentIdWhenOwnerUserIdIsDifferent() {
        var principal = new AgentPrincipal(41L, 7L, 9L);
        var request = new AgentCreateTaskRequest("request-key", 55L, "Verify task creation");
        var expectedRequest = new CreateTaskRequest("request-key", 55L, "Verify task creation");
        var created = new CreateTaskResponse(99L, 55L, TaskStatus.PENDING, 0L, false, null);
        when(tasks.create(9L, 41L, expectedRequest)).thenReturn(created);

        var response = controller.create(principal, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(created);
        verify(authorization).requireRequester(7L, 9L, 41L, 55L);
        verify(tasks).create(9L, 41L, expectedRequest);
    }
}
