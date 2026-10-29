package com.aiworkbench.common;

import java.util.Collections;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 将控制器和服务层异常转换为统一的 {@link ApiError} 响应。
 *
 * <p>该类集中处理参数校验、资源不存在、数据冲突和未预期异常，
 * 避免每个控制器重复编写错误响应逻辑。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 当前异常处理器日志。 */
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理可预期的业务异常。
     *
     * @param exception 业务异常
     * @param request 当前 HTTP 请求
     * @return 统一错误响应
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException exception, HttpServletRequest request) {
        return response(exception.getStatus(), exception.getCode(), exception.getMessage(), request, Collections.emptyMap());
    }

    /**
     * 处理 Bean Validation 参数校验失败。
     *
     * @param exception 参数校验异常
     * @param request 当前 HTTP 请求
     * @return 包含字段级错误详情的响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> fields = exception.getBindingResult().getFieldErrors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        FieldError::getField,
                        error -> error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage(),
                        (first, ignored) -> first));
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "请求参数校验失败",
                request,
                Map.of("fields", fields));
    }

    /**
     * 处理 JSON 格式错误和路径参数类型转换错误。
     *
     * @param exception 请求解析异常
     * @param request 当前 HTTP 请求
     * @return 400 错误响应
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleMalformedRequest(Exception exception, HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "请求格式或参数类型无效",
                request,
                Collections.emptyMap());
    }

    /**
     * 处理分页参数等主动抛出的状态异常。
     *
     * @param exception Spring 状态异常
     * @param request 当前 HTTP 请求
     * @return 统一错误响应
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatusException(
            ResponseStatusException exception,
            HttpServletRequest request) {
        String message = exception.getReason() == null ? "请求失败" : exception.getReason();
        return response(exception.getStatusCode(), "BAD_REQUEST", message, request, Collections.emptyMap());
    }

    /**
     * 处理唯一键冲突和外键约束冲突。
     *
     * @param exception 数据完整性异常
     * @param request 当前 HTTP 请求
     * @return 409 冲突响应
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        LOGGER.warn(
                "Data integrity violation requestId={} method={} uri={}",
                requestId(request),
                request.getMethod(),
                request.getRequestURI(),
                exception);
        return response(
                HttpStatus.CONFLICT,
                "DATA_CONFLICT",
                "数据与现有记录冲突，请检查名称、标识或关联关系",
                request,
                Collections.emptyMap());
    }

    /**
     * 处理数据库查询、字段缺失和 SQL 类型推断等数据访问异常。
     *
     * @param exception 数据访问异常
     * @param request 当前 HTTP 请求
     * @return 500 数据库错误响应
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDataAccess(DataAccessException exception, HttpServletRequest request) {
        LOGGER.error(
                "Data access error requestId={} method={} uri={}",
                requestId(request),
                request.getMethod(),
                request.getRequestURI(),
                exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "DATABASE_ERROR",
                "数据库访问失败，请查看服务日志",
                request,
                Collections.emptyMap());
    }

    /**
     * 兜底处理未预期异常。
     *
     * @param exception 未预期异常
     * @param request 当前 HTTP 请求
     * @return 500 错误响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error(
                "Unexpected error requestId={} method={} uri={}",
                requestId(request),
                request.getMethod(),
                request.getRequestURI(),
                exception);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "服务内部错误",
                request,
                Collections.emptyMap());
    }

    /**
     * 构造统一错误响应并附加请求 ID。
     *
     * @param status HTTP 状态码
     * @param code 业务错误编码
     * @param message 错误说明
     * @param request 当前 HTTP 请求
     * @param details 结构化错误详情
     * @return 错误响应实体
     */
    private ResponseEntity<ApiError> response(
            org.springframework.http.HttpStatusCode status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, Object> details) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, requestId == null ? "unknown" : requestId.toString(), details));
    }

    /**
     * 获取当前请求 ID。
     *
     * @param request 当前 HTTP 请求
     * @return 请求 ID
     */
    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }
}
