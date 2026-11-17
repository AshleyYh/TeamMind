package com.teammind.web.exception;

import com.teammind.common.api.ApiResponse;
import com.teammind.common.api.ErrorCode;
import com.teammind.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException exception, HttpServletRequest request) {
        ErrorCode errorCode = exception.getErrorCode();
        return response(errorCode, exception.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        return validationResponse(exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiResponse.FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList(), request);
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<ApiResponse<Void>> handleBindException(
            BindException exception, HttpServletRequest request) {
        return validationResponse(exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiResponse.FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList(), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException exception, HttpServletRequest request) {
        return validationResponse(exception.getConstraintViolations().stream()
                .map(violation -> new ApiResponse.FieldViolation(
                        violation.getPropertyPath().toString(), violation.getMessage()))
                .toList(), request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiResponse<Void>> handleMethodValidation(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        return response(ErrorCode.VALIDATION_FAILED, "请求参数校验失败", request);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiResponse<Void>> handleInvalidParameter(
            Exception exception, HttpServletRequest request) {
        return response(ErrorCode.VALIDATION_FAILED, "请求参数缺失或格式不正确", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(HttpServletRequest request) {
        return response(ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Void>> handleDataConflict(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        // 数据库约束细节可能包含表名和字段名，只记录异常类型并向客户端返回稳定的冲突错误。
        log.warn("数据库约束冲突：path={}", request.getRequestURI());
        return response(ErrorCode.CONFLICT, "数据已存在或违反唯一约束", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(HttpServletRequest request) {
        return response(ErrorCode.MALFORMED_REQUEST, ErrorCode.MALFORMED_REQUEST.getMessage(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> handleNotFound(HttpServletRequest request) {
        return response(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMessage(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpServletRequest request) {
        return response(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getMessage(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpServletRequest request) {
        return response(ErrorCode.MEDIA_TYPE_NOT_SUPPORTED,
                ErrorCode.MEDIA_TYPE_NOT_SUPPORTED.getMessage(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception exception, HttpServletRequest request) {
        log.error("Unhandled request failure: method={}, path={}", request.getMethod(),
                request.getRequestURI(), exception);
        return response(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage(), request);
    }

    private ResponseEntity<ApiResponse<Void>> validationResponse(
            List<ApiResponse.FieldViolation> errors, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.validationFailure(request.getRequestURI(), errors));
    }

    private ResponseEntity<ApiResponse<Void>> response(
            ErrorCode errorCode, String message, HttpServletRequest request) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.failure(errorCode, message, request.getRequestURI()));
    }
}
