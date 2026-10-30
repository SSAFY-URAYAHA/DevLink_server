package com.devlink.server.dto;

import com.devlink.server.domain.agent.ToolType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAgentRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 255) String roleDescription,
        @NotNull ToolType toolType,
        @NotBlank @Size(max = 120) String projectName) { }
