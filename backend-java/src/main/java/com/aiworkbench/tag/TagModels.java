package com.aiworkbench.tag;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 标签领域对象、请求对象和响应对象的集中定义。
 */
public final class TagModels {

    /** 工具类不允许实例化。 */
    private TagModels() {
    }

    /**
     * 标签 API 响应。
     *
     * @param id 标签 ID
     * @param name 标签名称
     * @param slug 标签稳定标识
     * @param parentId 父标签 ID
     * @param color 标签颜色
     * @param usageCount 标签被资料和面试题使用的次数
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    public record TagResponse(
            /** 标签 ID。 */
            UUID id,
            /** 标签名称。 */
            String name,
            /** 标签稳定标识。 */
            String slug,
            /** 父标签 ID。 */
            UUID parentId,
            /** 标签颜色。 */
            String color,
            /** 标签使用次数。 */
            long usageCount,
            /** 创建时间。 */
            OffsetDateTime createdAt,
            /** 最后更新时间。 */
            OffsetDateTime updatedAt) {
    }

    /**
     * 创建标签请求。
     *
     * @param name 标签名称
     * @param slug 标签稳定标识
     * @param parentId 父标签 ID
     * @param color 标签颜色
     */
    public record CreateTagRequest(
            /** 标签名称。 */
            @NotBlank @Size(max = 120) String name,
            /** 标签稳定标识。 */
            @NotBlank @Size(max = 120) String slug,
            /** 父标签 ID。 */
            UUID parentId,
            /** 标签颜色。 */
            @Size(max = 32) String color) {
    }

    /**
     * 更新标签请求。
     *
     * @param name 标签名称
     * @param slug 标签稳定标识
     * @param parentId 父标签 ID
     * @param color 标签颜色
     */
    public record UpdateTagRequest(
            /** 标签名称。 */
            @Size(max = 120) String name,
            /** 标签稳定标识。 */
            @Size(max = 120) String slug,
            /** 父标签 ID。 */
            UUID parentId,
            /** 标签颜色。 */
            @Size(max = 32) String color) {
    }
}
