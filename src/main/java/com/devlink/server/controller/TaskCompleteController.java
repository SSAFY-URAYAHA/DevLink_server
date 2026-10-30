package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.task.TaskCompleteRequest;
import com.devlink.server.dto.task.TaskCompleteResponse;
import com.devlink.server.service.TaskCompleteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/v1/tasks")
@RequiredArgsConstructor
public class TaskCompleteController {

	private final TaskCompleteService taskCompleteService;
	
	@PostMapping("/{taskId}/complete")
	public ResponseEntity<ApiResponse<TaskCompleteResponse>> complete(
			@PathVariable @Positive Long taskId,
			@AuthenticationPrincipal AgentPrincipal agent,
			@Valid @RequestBody TaskCompleteRequest request){
		
		TaskCompleteResponse response = taskCompleteService.complete(taskId, agent, request);

		return ResponseEntity.ok(
				ApiResponse.success(response));
	}
}
