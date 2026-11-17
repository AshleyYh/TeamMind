package com.teammind.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** 业务接口统一响应；Actuator 和 SpringDoc 保留各自标准格式。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        String code, String message, T data, String path, Instant timestamp, List<FieldViolation> errors) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(),
                data, null, Instant.now(), null);
    }

    public static ApiResponse<Void> success() { return success(null); }

    public static ApiResponse<Void> failure(ErrorCode errorCode, String message, String path) {
        return new ApiResponse<>(errorCode.getCode(), message, null, path, Instant.now(), null);
    }

    public static ApiResponse<Void> validationFailure(String path, List<FieldViolation> errors) {
        return new ApiResponse<>(ErrorCode.VALIDATION_FAILED.getCode(),
                ErrorCode.VALIDATION_FAILED.getMessage(), null, path, Instant.now(), errors);
    }

    public record FieldViolation(String field, String message) {
    }
}
