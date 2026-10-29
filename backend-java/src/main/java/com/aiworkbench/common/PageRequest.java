package com.aiworkbench.common;

import org.springframework.web.server.ResponseStatusException;

/**
 * 经过边界校验的分页请求参数。
 *
 * @param page 页码，从 0 开始
 * @param size 每页记录数，范围为 1 到 100
 */
public record PageRequest(int page, int size) {

    /**
     * 根据可选的查询参数创建分页对象。
     *
     * @param page 请求页码，未提供时默认为 0
     * @param size 请求页大小，未提供时默认为 20
     * @return 已校验的分页参数
     * @throws org.springframework.web.server.ResponseStatusException 参数超出允许范围时抛出
     */
    public static PageRequest of(Integer page, Integer size) {
        int resolvedPage = page == null ? 0 : page;
        int resolvedSize = size == null ? 20 : size;
        if (resolvedPage < 0 || resolvedSize < 1 || resolvedSize > 100) {
            throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "page must be >= 0 and size must be between 1 and 100");
        }
        return new PageRequest(resolvedPage, resolvedSize);
    }

    /**
     * 计算 SQL 查询使用的偏移量。
     *
     * @return 分页偏移量
     */
    public int offset() {
        return page * size;
    }
}
