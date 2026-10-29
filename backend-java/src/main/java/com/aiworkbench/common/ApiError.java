package com.aiworkbench.common;

import java.util.Map;

/**
 * 对外返回的统一错误结构。
 *
 * @param code     稳定的业务错误编码，供前端或调用方判断错误类型
 * @param message  面向用户或开发者的错误说明
 * @param requestId 当前请求的追踪标识
 * @param details  可选的结构化错误详情，例如字段校验错误
 */
public record ApiError(
        String code,
        String message,
        String requestId,
        Map<String, Object> details) {
}
