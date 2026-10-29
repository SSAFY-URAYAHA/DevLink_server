package com.devlink.server.dto;

import java.time.LocalDateTime;
import com.devlink.server.domain.user.User;

public record UserResponse(Long userId, String email, String name, String nickname,
        LocalDateTime createdAt) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(),
                user.getNickname(), user.getCreatedAt());
    }
}
