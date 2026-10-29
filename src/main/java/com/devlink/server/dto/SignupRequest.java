package com.devlink.server.dto;

import java.util.Locale;
import com.devlink.server.validation.Utf8Password;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8) @Utf8Password String password,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 100) String nickname) {
    public SignupRequest {
        if (email != null) {
            email = email.strip().toLowerCase(Locale.ROOT);
        }
    }
}
