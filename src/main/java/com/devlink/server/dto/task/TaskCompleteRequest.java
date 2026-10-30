package com.devlink.server.dto.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record TaskCompleteRequest(
		@NotNull
		@PositiveOrZero
		Long version,
		
		@NotBlank
		String resultText,
		
		@Valid
		List<@NotNull TaskFileRequest> taskFiles) {
	public TaskCompleteRequest{
		taskFiles = taskFiles == null ? List.of(): taskFiles;
	}

	
}
