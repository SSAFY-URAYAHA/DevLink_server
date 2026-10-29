package com.devlink.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinTeamRequest(@NotBlank @Size(max = 64) String inviteCode) {
    public JoinTeamRequest {
        if (inviteCode != null) {
            inviteCode = inviteCode.strip();
        }
    }
}
