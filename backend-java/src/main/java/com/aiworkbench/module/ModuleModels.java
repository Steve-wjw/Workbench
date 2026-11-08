package com.aiworkbench.module;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 模块领域对象、请求对象和响应对象的集中定义。
 */
public final class ModuleModels {

    /** 工具类不允许实例化。 */
    private ModuleModels() {
    }

    /**
     * 模块 API 响应。
     *
     * @param id 模块 ID
     * @param name 模块名称
     * @param description 模块描述
     * @param icon 模块图标标识
     * @param sortOrder 展示排序
     * @param status 模块状态
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    public record ModuleResponse(
            /** 模块 ID。 */
            UUID id,
            /** 模块名称。 */
            String name,
            /** 模块描述。 */
            String description,
            /** 模块图标标识。 */
            String icon,
            /** 展示排序。 */
            int sortOrder,
            /** 模块状态。 */
            String status,
            /** 创建时间。 */
            OffsetDateTime createdAt,
            /** 最后更新时间。 */
            OffsetDateTime updatedAt) {
    }

    /**
     * 创建模块请求。
     *
     * @param name 模块名称
     * @param description 模块描述
     * @param icon 模块图标标识
     * @param sortOrder 展示排序
     * @param status 模块状态
     */
    public record CreateModuleRequest(
            /** 模块名称。 */
            @NotBlank @Size(max = 120) String name,
            /** 模块描述。 */
            @Size(max = 2000) String description,
            /** 模块图标标识。 */
            @Size(max = 80) String icon,
            /** 展示排序。 */
            Integer sortOrder,
            /** 模块状态。 */
            String status) {
    }

    /**
     * 更新模块请求。所有字段均为可选，只更新调用方提供的字段。
     *
     * @param name 模块名称
     * @param description 模块描述
     * @param icon 模块图标标识
     * @param sortOrder 展示排序
     * @param status 模块状态
     */
    public record UpdateModuleRequest(
            /** 模块名称。 */
            @Size(max = 120) String name,
            /** 模块描述。 */
            @Size(max = 2000) String description,
            /** 模块图标标识。 */
            @Size(max = 80) String icon,
            /** 展示排序。 */
            Integer sortOrder,
            /** 模块状态。 */
            String status) {
    }
}
