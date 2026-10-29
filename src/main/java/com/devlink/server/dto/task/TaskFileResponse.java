package com.devlink.server.dto.task;

import com.devlink.server.domain.taskfile.ChangeType;

public record TaskFileResponse(	
		Long fileId,
		String relativePath,
		ChangeType changeType,
		String diffText) {


}
