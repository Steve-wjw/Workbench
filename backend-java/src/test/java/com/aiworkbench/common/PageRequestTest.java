package com.aiworkbench.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * 分页请求边界测试。
 */
class PageRequestTest {

    /**
     * 未提供分页参数时应使用稳定默认值。
     */
    @Test
    void usesDefaultsWhenParametersAreAbsent() {
        PageRequest request = PageRequest.of(null, null);

        assertThat(request.page()).isZero();
        assertThat(request.size()).isEqualTo(20);
        assertThat(request.offset()).isZero();
    }

    /**
     * 应根据页码和页大小计算 SQL 偏移量。
     */
    @Test
    void calculatesOffset() {
        assertThat(PageRequest.of(3, 25).offset()).isEqualTo(75);
    }

    /**
     * 超出边界的参数不应进入 Repository。
     */
    @Test
    void rejectsInvalidBounds() {
        assertThatThrownBy(() -> PageRequest.of(-1, 20))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> PageRequest.of(0, 101))
                .isInstanceOf(ResponseStatusException.class);
    }
}
