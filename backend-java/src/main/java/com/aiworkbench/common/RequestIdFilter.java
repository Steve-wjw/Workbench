package com.aiworkbench.common;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 为每个 HTTP 请求生成或透传请求 ID。
 *
 * <p>请求 ID 同时写入 Servlet 请求属性和响应头，
 * 供异常响应以及后续日志关联使用。
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    /** Servlet 请求属性名称。 */
    public static final String REQUEST_ID_ATTRIBUTE = "workbench.requestId";

    /** 客户端可以透传的请求头名称。 */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /**
     * 注入请求 ID 并继续执行过滤器链。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param filterChain Servlet 过滤器链
     * @throws ServletException 过滤器链处理失败时抛出
     * @throws IOException 读写请求或响应失败时抛出
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = "req_" + UUID.randomUUID();
        }
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("requestId", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
