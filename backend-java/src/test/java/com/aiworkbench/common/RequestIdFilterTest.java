package com.aiworkbench.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;

/**
 * 请求 ID 过滤器测试。
 */
class RequestIdFilterTest {

    /**
     * 客户端传入请求 ID 时，应原样透传到请求、响应和日志上下文。
     */
    @Test
    void propagatesClientRequestId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdInChain = new AtomicReference<>();
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "req_client");

        new RequestIdFilter().doFilter(request, response, (servletRequest, servletResponse) ->
                requestIdInChain.set(MDC.get("requestId")));

        assertThat(request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE)).isEqualTo("req_client");
        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("req_client");
        assertThat(requestIdInChain.get()).isEqualTo("req_client");
        assertThat(MDC.get("requestId")).isNull();
    }

    /**
     * 未传入请求 ID 时，过滤器应生成并响应一个可追踪标识。
     */
    @Test
    void createsRequestIdWhenHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RequestIdFilter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).startsWith("req_");
    }
}
