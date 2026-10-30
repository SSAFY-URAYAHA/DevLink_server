package com.devlink.server.dto.task;

import com.devlink.server.domain.taskfile.ChangeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TaskFileRequest(
		@NotBlank
		@Size(max = 500)
		String relativePath,
		
		@NotNull
		ChangeType changeType,
		
		String diffText) {


}
