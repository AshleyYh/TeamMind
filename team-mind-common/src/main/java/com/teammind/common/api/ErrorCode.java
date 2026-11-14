package com.teammind.common.api;

import org.springframework.http.HttpStatus;

/**
 * Stable application error codes. HTTP status describes transport semantics;
 * the numeric code lets clients distinguish business failures precisely.
 */
public enum ErrorCode {
    SUCCESS("0", "Success", HttpStatus.OK),
    VALIDATION_FAILED("40001", "Request validation failed", HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST("40002", "Malformed request", HttpStatus.BAD_REQUEST),
    NOT_FOUND("40401", "Resource not found", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("40501", "HTTP method not supported", HttpStatus.METHOD_NOT_ALLOWED),
    MEDIA_TYPE_NOT_SUPPORTED("41501", "Media type not supported", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    BUSINESS_ERROR("42201", "Business rule violated", HttpStatus.UNPROCESSABLE_ENTITY),
    INTERNAL_ERROR("50000", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE("50301", "Dependent service unavailable", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
