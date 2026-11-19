package com.aiworkbench.question;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

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
     * @param question 题目正文
     * @param answer 答案
     * @param detailedAnswer 详细解释
     * @param followUpQuestions 追问内容，支持结构化 JSON 并兼容旧 Markdown
     * @param difficulty 难度
     * @param masteryStatus 掌握状态
     * @param sourceType 创建来源：MANUAL 手动增加，DOCUMENT 资料库生成
     * @param tags 关联标签
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    public record QuestionResponse(
            /** 自增主键；以十进制字符串返回，避免浏览器丢失 BIGINT 精度。 */
            @JsonSerialize(using = ToStringSerializer.class) Long id,
            /** 所属模块 ID。 */
            UUID moduleId,
            /** 所属模块名称。 */
            String moduleName,
            /** 题目正文。 */
            String question,
            /** 答案。 */
            String answer,
            /** 详细解释。 */
            String detailedAnswer,
            /** 追问内容，支持结构化 JSON 并兼容旧 Markdown。 */
            String followUpQuestions,
            /** 难度。 */
            String difficulty,
            /** 掌握状态。 */
            String masteryStatus,
            /** 创建来源，编辑内容时保持不变。 */
            String sourceType,
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
     * @param question 题目正文
     * @param answer 答案
     * @param detailedAnswer 详细解释
     * @param followUpQuestions 追问内容，支持结构化 JSON 并兼容旧 Markdown
     * @param difficulty 难度
     * @param masteryStatus 掌握状态
     * @param tagIds 关联标签 ID
     * @param sourceType 创建来源；不传时默认为 MANUAL
     */
    public record CreateQuestionRequest(
            /** 所属模块 ID。 */
            UUID moduleId,
            /** 题目正文。 */
            @NotBlank String question,
            /** 答案。 */
            @NotBlank String answer,
            /** 详细解释。 */
            String detailedAnswer,
            /** 可选的追问内容，按原文存储。 */
            String followUpQuestions,
            /** 难度。 */
            String difficulty,
            /** 掌握状态。 */
            String masteryStatus,
            /** 关联标签 ID。 */
            List<UUID> tagIds,
            /** 不传时按手动增加处理。 */
            @Pattern(regexp = "MANUAL|DOCUMENT") String sourceType) {
    }

    /**
     * 更新面试题请求。未提供的字段保持原值。
     *
     * @param moduleId 所属模块 ID
     * @param question 题目正文
     * @param answer 答案
     * @param detailedAnswer 详细解释
     * @param followUpQuestions 追问内容；空字符串清空，null 保持原值
     * @param difficulty 难度
     * @param masteryStatus 掌握状态
     * @param tagIds 关联标签 ID
     */
    public record UpdateQuestionRequest(
            /** 所属模块 ID。 */
            UUID moduleId,
            /** 题目正文。 */
            String question,
            /** 答案。 */
            String answer,
            /** 详细解释。 */
            String detailedAnswer,
            /** 空字符串清空追问，null 保持原值。 */
            String followUpQuestions,
            /** 难度。 */
            String difficulty,
            /** 掌握状态。 */
            String masteryStatus,
            /** 关联标签 ID。 */
            List<UUID> tagIds) {
    }
}
