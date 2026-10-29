package com.aiworkbench.document;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.aiworkbench.tag.TagModels;

/**
 * 资料领域对象、请求对象和响应对象的集中定义。
 */
public final class DocumentModels {

    /** 工具类不允许实例化。 */
    private DocumentModels() {
    }

    /**
     * 资料 API 响应。
     *
     * @param id 资料 ID
     * @param moduleId 所属模块 ID
     * @param moduleName 所属模块名称
     * @param title 资料标题
     * @param content 资料正文
     * @param normalizedContent 解析后的规范化文本
     * @param sourceType 来源类型
     * @param sourceName 来源名称
     * @param sourcePath 来源路径
     * @param contentHash 正文 SHA-256 摘要
     * @param status 资料状态
     * @param indexError 最近一次索引错误
     * @param indexedAt 最近一次成功索引时间
     * @param tags 关联标签
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    public record DocumentResponse(
            /** 资料 ID。 */
            UUID id,
            /** 所属模块 ID。 */
            UUID moduleId,
            /** 所属模块名称。 */
            String moduleName,
            /** 资料标题。 */
            String title,
            /** 资料正文。 */
            String content,
            /** 解析后的规范化文本。 */
            String normalizedContent,
            /** 来源类型。 */
            String sourceType,
            /** 来源名称。 */
            String sourceName,
            /** 来源路径。 */
            String sourcePath,
            /** 正文 SHA-256 摘要。 */
            String contentHash,
            /** 资料状态。 */
            String status,
            /** 最近一次索引错误。 */
            String indexError,
            /** 最近一次成功索引时间。 */
            OffsetDateTime indexedAt,
            /** 关联标签。 */
            List<TagModels.TagResponse> tags,
            /** 创建时间。 */
            OffsetDateTime createdAt,
            /** 最后更新时间。 */
            OffsetDateTime updatedAt) {
    }

    /**
     * 创建资料请求。
     *
     * @param moduleId 所属模块 ID
     * @param title 资料标题
     * @param content 资料正文
     * @param sourceType 来源类型
     * @param sourceName 来源名称
     * @param sourcePath 来源路径
     * @param status 资料状态
     * @param tagIds 关联标签 ID
     */
    public record CreateDocumentRequest(
            /** 所属模块 ID。 */
            UUID moduleId,
            /** 资料标题。 */
            @NotBlank @Size(max = 240) String title,
            /** 资料正文。 */
            @NotBlank String content,
            /** 来源类型。 */
            String sourceType,
            /** 来源名称。 */
            @Size(max = 240) String sourceName,
            /** 来源路径。 */
            String sourcePath,
            /** 资料状态。 */
            String status,
            /** 关联标签 ID。 */
            List<UUID> tagIds) {
    }

    /**
     * 更新资料请求。未提供的字段保持原值。
     *
     * @param moduleId 所属模块 ID
     * @param title 资料标题
     * @param content 资料正文
     * @param sourceType 来源类型
     * @param sourceName 来源名称
     * @param sourcePath 来源路径
     * @param status 资料状态
     * @param tagIds 关联标签 ID
     */
    public record UpdateDocumentRequest(
            /** 所属模块 ID。 */
            UUID moduleId,
            /** 资料标题。 */
            @Size(max = 240) String title,
            /** 资料正文。 */
            String content,
            /** 来源类型。 */
            String sourceType,
            /** 来源名称。 */
            @Size(max = 240) String sourceName,
            /** 来源路径。 */
            String sourcePath,
            /** 资料状态。 */
            String status,
            /** 关联标签 ID。 */
            List<UUID> tagIds) {
    }

    /**
     * Markdown 粘贴导入请求。
     *
     * @param moduleId 所属模块 ID
     * @param title 资料标题
     * @param markdown 原始 Markdown 内容
     * @param sourceName 来源名称
     * @param tagIds 关联标签 ID
     */
    public record ImportMarkdownRequest(
            /** 所属模块 ID。 */
            UUID moduleId,
            /** 资料标题。 */
            @NotBlank @Size(max = 240) String title,
            /** 原始 Markdown 内容。 */
            @NotBlank String markdown,
            /** 来源名称。 */
            @Size(max = 240) String sourceName,
            /** 关联标签 ID。 */
            List<UUID> tagIds) {
    }

    /**
     * Markdown 导入响应。
     *
     * @param document 资料记录
     * @param job 索引任务；重复内容复用既有资料时可以为空
     * @param duplicate 是否识别为重复内容
     * @param duplicateOfDocumentId 重复资料 ID
     */
    public record ImportDocumentResponse(
            /** 资料记录。 */
            DocumentResponse document,
            /** 索引任务。 */
            IndexJobResponse job,
            /** 是否为重复内容。 */
            boolean duplicate,
            /** 重复资料 ID。 */
            UUID duplicateOfDocumentId) {
    }

    /**
     * 索引任务响应。
     *
     * @param id 任务 ID
     * @param jobType 任务类型
     * @param resourceType 资源类型
     * @param resourceId 资源 ID
     * @param status 任务状态
     * @param attemptCount 已尝试次数
     * @param maxAttempts 最大尝试次数
     * @param forceRebuild 是否强制重建
     * @param errorMessage 错误信息
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     * @param startedAt 开始时间
     * @param finishedAt 结束时间
     */
    public record IndexJobResponse(
            /** 任务 ID。 */
            UUID id,
            /** 任务类型。 */
            String jobType,
            /** 资源类型。 */
            String resourceType,
            /** 资源 ID。 */
            UUID resourceId,
            /** 任务状态。 */
            String status,
            /** 已尝试次数。 */
            int attemptCount,
            /** 最大尝试次数。 */
            int maxAttempts,
            /** 是否强制重建。 */
            boolean forceRebuild,
            /** 错误信息。 */
            String errorMessage,
            /** 创建时间。 */
            OffsetDateTime createdAt,
            /** 更新时间。 */
            OffsetDateTime updatedAt,
            /** 开始时间。 */
            OffsetDateTime startedAt,
            /** 结束时间。 */
            OffsetDateTime finishedAt) {
    }
}
