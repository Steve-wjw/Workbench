package com.aiworkbench.common;

import java.util.List;

/**
 * 统一分页响应结构。
 *
 * @param <T> 列表元素类型
 * @param items 当前页数据
 * @param page 当前页码，从 0 开始
 * @param size 当前页大小
 * @param total 符合条件的总记录数
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long total) {
}
