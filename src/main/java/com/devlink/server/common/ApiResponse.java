package com.devlink.server.common;

public record ApiResponse<T>(boolean success, T data, String code, String message) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static ApiResponse<Void> error(ErrorCode error) {
        return new ApiResponse<>(false, null, error.name(), error.getMessage());
    }
}
