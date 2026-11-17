package com.teammind.common.api;

import org.springframework.http.HttpStatus;

/** 稳定的业务错误码：HTTP 状态表达传输语义，数字编码便于客户端区分具体失败原因。 */
public enum ErrorCode {
    SUCCESS("0", "请求成功", HttpStatus.OK),
    VALIDATION_FAILED("40001", "请求参数校验失败", HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST("40002", "请求格式不正确", HttpStatus.BAD_REQUEST),
    NOT_FOUND("40401", "资源不存在", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("40501", "不支持当前请求方法", HttpStatus.METHOD_NOT_ALLOWED),
    MEDIA_TYPE_NOT_SUPPORTED("41501", "不支持当前媒体类型", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    BUSINESS_ERROR("42201", "业务规则校验失败", HttpStatus.UNPROCESSABLE_ENTITY),
    CONFLICT("40901", "数据冲突", HttpStatus.CONFLICT),
    PAYLOAD_TOO_LARGE("41301", "上传文件超过大小限制", HttpStatus.PAYLOAD_TOO_LARGE),
    INTERNAL_ERROR("50000", "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE("50301", "依赖服务暂不可用", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
    public HttpStatus getHttpStatus() { return httpStatus; }
}
