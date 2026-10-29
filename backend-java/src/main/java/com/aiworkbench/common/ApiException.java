package com.aiworkbench.common;

import org.springframework.http.HttpStatus;

/**
 * 可预期的业务异常。
 *
 * <p>业务服务使用该异常表达资源不存在、关联对象无效等情况，
 * 再由 {@link GlobalExceptionHandler} 转换成统一错误响应。
 */
public class ApiException extends RuntimeException {

    /** 稳定的业务错误编码。 */
    private final String code;

    /** HTTP 响应状态码。 */
    private final HttpStatus status;

    /**
     * 创建业务异常。
     *
     * @param status HTTP 响应状态码
     * @param code 业务错误编码
     * @param message 错误说明
     */
    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    /**
     * 返回业务错误编码。
     *
     * @return 错误编码
     */
    public String getCode() {
        return code;
    }

    /**
     * 返回建议的 HTTP 状态码。
     *
     * @return HTTP 状态码
     */
    public HttpStatus getStatus() {
        return status;
    }
}
