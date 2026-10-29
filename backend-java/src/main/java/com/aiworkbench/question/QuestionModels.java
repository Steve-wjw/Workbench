package com.aiworkbench.question;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.aiworkbench.tag.TagModels;

/**
 * 面试题领域对象、请求对象和响应对象的集中定义。
 */
public final class QuestionModels {

    /** 工具类不允许实例化。 */
    private QuestionModels() {
    }

    /**
     * 面试题 API 响应。
     *
     * @param id 面试题 ID
     * @param moduleId 所属模块 ID
     * @param moduleName 所属模块名称
     * @param title 面试题标题
     * @param question 题目正文
     * @param shortAnswer 简短答案
     * @param detailedAnswer 详细解释
     * @param keyPoints 关键点列表
     * @param difficulty 难度
     * @param masteryStatus 掌握状态
     * @param sourceType 来源类型
     * @param status 记录状态
     * @param tags 关联标签
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    public record QuestionResponse(
            /** 面试题 ID。 */
            UUID id,
            /** 所属模块 ID。 */
            UUID moduleId,
            /** 所属模块名称。 */
            String moduleName,
            /** 面试题标题。 */
            String title,
            /** 题目正文。 */
            String question,
            /** 简短答案。 */
            String shortAnswer,
            /** 详细解释。 */
            String detailedAnswer,
            /** 关键点列表。 */
            List<String> keyPoints,
            /** 难度。 */
            String difficulty,
            /** 掌握状态。 */
            String masteryStatus,
            /** 来源类型。 */
            String sourceType,
            /** 记录状态。 */
            String status,
            /** 关联标签。 */
            List<TagModels.TagResponse> tags,
            /** 创建时间。 */
            OffsetDateTime createdAt,
            /** 最后更新时间。 */
            OffsetDateTime updatedAt) {
    }

    /**
     * 创建面试题请求。
     *
     * @param moduleId 所属模块 ID
     * @param title 面试题标题
     * @param question 题目正文
     * @param shortAnswer 简短答案
     * @param detailedAnswer 详细解释
     * @param keyPoints 关键点列表
     * @param difficulty 难度
     * @param masteryStatus 掌握状态
     * @param sourceType 来源类型
     * @param status 记录状态
     * @param tagIds 关联标签 ID
     */
    public record CreateQuestionRequest(
            /** 所属模块 ID。 */
            UUID moduleId,
            /** 面试题标题。 */
            @NotBlank @Size(max = 240) String title,
            /** 题目正文。 */
            @NotBlank String question,
            /** 简短答案。 */
            String shortAnswer,
            /** 详细解释。 */
            String detailedAnswer,
            /** 关键点列表。 */
            List<String> keyPoints,
            /** 难度。 */
            String difficulty,
            /** 掌握状态。 */
            String masteryStatus,
            /** 来源类型。 */
            String sourceType,
            /** 记录状态。 */
            String status,
            /** 关联标签 ID。 */
            List<UUID> tagIds) {
    }

    /**
     * 更新面试题请求。未提供的字段保持原值。
     *
     * @param moduleId 所属模块 ID
     * @param title 面试题标题
     * @param question 题目正文
     * @param shortAnswer 简短答案
     * @param detailedAnswer 详细解释
     * @param keyPoints 关键点列表
     * @param difficulty 难度
     * @param masteryStatus 掌握状态
     * @param sourceType 来源类型
     * @param status 记录状态
     * @param tagIds 关联标签 ID
     */
    public record UpdateQuestionRequest(
            /** 所属模块 ID。 */
            UUID moduleId,
            /** 面试题标题。 */
            @Size(max = 240) String title,
            /** 题目正文。 */
            String question,
            /** 简短答案。 */
            String shortAnswer,
            /** 详细解释。 */
            String detailedAnswer,
            /** 关键点列表。 */
            List<String> keyPoints,
            /** 难度。 */
            String difficulty,
            /** 掌握状态。 */
            String masteryStatus,
            /** 来源类型。 */
            String sourceType,
            /** 记录状态。 */
            String status,
            /** 关联标签 ID。 */
            List<UUID> tagIds) {
    }
}
