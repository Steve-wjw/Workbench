package com.aiworkbench.question;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aiworkbench.common.PageRequest;
import com.aiworkbench.common.PageResponse;
import com.aiworkbench.tag.TagModels;
import com.aiworkbench.tag.TagRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 面试题数据访问层。
 *
 * <p>负责面试题主表以及面试题标签关系的读写，
 * 并将标签详情组装到 API 响应中。
 */
@Repository
public class QuestionRepository {

    /** 命名参数 JDBC 模板。 */
    private final NamedParameterJdbcTemplate jdbc;

    /** 标签数据访问对象，用于组装标签详情。 */
    private final TagRepository tagRepository;

    /**
     * 创建面试题数据访问对象。
     *
     * @param jdbc 命名参数 JDBC 模板
     * @param tagRepository 标签数据访问对象
     */
    public QuestionRepository(
            NamedParameterJdbcTemplate jdbc,
            TagRepository tagRepository) {
        this.jdbc = jdbc;
        this.tagRepository = tagRepository;
    }

    /**
     * 按关键词、模块、标签、难度和掌握状态分页查询面试题。
     *
     * @param keyword 问题或答案关键词
     * @param moduleId 模块过滤条件
     * @param tagId 标签过滤条件
     * @param difficulty 难度过滤条件
     * @param masteryStatus 掌握状态过滤条件
     * @param pageRequest 分页参数
     * @return 分页面试题列表
     */
    public PageResponse<QuestionModels.QuestionResponse> findPage(
            String keyword,
            UUID moduleId,
            UUID tagId,
            String difficulty,
            String masteryStatus,
            PageRequest pageRequest) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("keyword", normalizedKeyword)
                .addValue("likeKeyword", "%" + normalizedKeyword + "%")
                .addValue("moduleId", moduleId)
                .addValue("tagId", tagId)
                .addValue("difficulty", blankToNull(difficulty))
                .addValue("masteryStatus", blankToNull(masteryStatus))
                .addValue("limit", pageRequest.size())
                .addValue("offset", pageRequest.offset());
        List<QuestionModels.QuestionResponse> items = jdbc.query("""
                SELECT q.id, q.module_id, m.name AS module_name, q.question,
                       q.answer, q.detailed_answer, q.follow_up_questions, q.difficulty,
                       q.mastery_status, q.source_type, q.created_at, q.updated_at
                FROM core.interview_questions q
                LEFT JOIN core.modules m ON m.id = q.module_id
                WHERE (:keyword = '' OR q.question ILIKE :likeKeyword OR q.answer ILIKE :likeKeyword
                       OR q.follow_up_questions ILIKE :likeKeyword)
                  AND (CAST(:moduleId AS uuid) IS NULL OR q.module_id = CAST(:moduleId AS uuid))
                  AND (CAST(:tagId AS uuid) IS NULL OR EXISTS (
                      SELECT 1 FROM core.interview_question_tags qt_filter
                      WHERE qt_filter.question_id = q.id AND qt_filter.tag_id = CAST(:tagId AS uuid)
                  ))
                  AND (CAST(:difficulty AS varchar) IS NULL OR q.difficulty = CAST(:difficulty AS varchar))
                  AND (CAST(:masteryStatus AS varchar) IS NULL OR q.mastery_status = CAST(:masteryStatus AS varchar))
                ORDER BY q.id ASC
                LIMIT :limit OFFSET :offset
                """, params, rowMapper());
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM core.interview_questions q
                WHERE (:keyword = '' OR q.question ILIKE :likeKeyword OR q.answer ILIKE :likeKeyword
                       OR q.follow_up_questions ILIKE :likeKeyword)
                  AND (CAST(:moduleId AS uuid) IS NULL OR q.module_id = CAST(:moduleId AS uuid))
                  AND (CAST(:tagId AS uuid) IS NULL OR EXISTS (
                      SELECT 1 FROM core.interview_question_tags qt_filter
                      WHERE qt_filter.question_id = q.id AND qt_filter.tag_id = CAST(:tagId AS uuid)
                  ))
                  AND (CAST(:difficulty AS varchar) IS NULL OR q.difficulty = CAST(:difficulty AS varchar))
                  AND (CAST(:masteryStatus AS varchar) IS NULL OR q.mastery_status = CAST(:masteryStatus AS varchar))
                """, params, Long.class);
        return new PageResponse<>(
                items.stream().map(this::withTags).toList(),
                pageRequest.page(),
                pageRequest.size(),
                total == null ? 0 : total);
    }

    /**
     * 根据 ID 查询面试题详情。
     *
     * @param id 面试题 ID
     * @return 面试题详情；不存在时返回 {@code null}
     */
    public QuestionModels.QuestionResponse findById(Long id) {
        List<QuestionModels.QuestionResponse> results = jdbc.query("""
                SELECT q.id, q.module_id, m.name AS module_name, q.question,
                       q.answer, q.detailed_answer, q.follow_up_questions, q.difficulty,
                       q.mastery_status, q.source_type, q.created_at, q.updated_at
                FROM core.interview_questions q
                LEFT JOIN core.modules m ON m.id = q.module_id
                WHERE q.id = :id
                """, Map.of("id", id), rowMapper());
        return results.isEmpty() ? null : withTags(results.getFirst());
    }

    /**
     * 插入面试题及其标签关系。
     *
     * @param request 创建请求
     * @return 创建后的面试题
     */
    public QuestionModels.QuestionResponse insert(
            QuestionModels.CreateQuestionRequest request) {
        Long id = jdbc.queryForObject("""
                INSERT INTO core.interview_questions
                    (module_id, question, answer, detailed_answer, follow_up_questions, difficulty, mastery_status, source_type)
                VALUES
                    (:moduleId, :question, :answer, :detailedAnswer, :followUpQuestions, :difficulty, :masteryStatus, :sourceType)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("moduleId", request.moduleId())
                .addValue("question", request.question().trim())
                .addValue("answer", request.answer().trim())
                .addValue("detailedAnswer", blankToNull(request.detailedAnswer()))
                .addValue("followUpQuestions", request.followUpQuestions())
                .addValue("difficulty", defaultValue(request.difficulty(), "MEDIUM"))
                .addValue("masteryStatus", defaultValue(request.masteryStatus(), "NOT_STARTED"))
                .addValue("sourceType", defaultValue(request.sourceType(), "MANUAL")), Long.class);
        replaceTags(id, request.tagIds());
        return findById(id);
    }

    /**
     * 更新面试题及其标签关系。
     *
     * @param id 面试题 ID
     * @param request 更新请求
     * @return 更新后的面试题
     */
    public QuestionModels.QuestionResponse update(
            Long id,
            QuestionModels.UpdateQuestionRequest request) {
        jdbc.update("""
                UPDATE core.interview_questions
                SET module_id = COALESCE(:moduleId, module_id),
                    question = COALESCE(:question, question),
                    answer = COALESCE(:answer, answer),
                    detailed_answer = COALESCE(:detailedAnswer, detailed_answer),
                    follow_up_questions = COALESCE(:followUpQuestions, follow_up_questions),
                    difficulty = COALESCE(:difficulty, difficulty),
                    mastery_status = COALESCE(:masteryStatus, mastery_status),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("moduleId", request.moduleId())
                .addValue("question", blankToNull(request.question()))
                .addValue("answer", blankToNull(request.answer()))
                .addValue("detailedAnswer", blankToNull(request.detailedAnswer()))
                .addValue("followUpQuestions", request.followUpQuestions())
                .addValue("difficulty", blankToNull(request.difficulty()))
                .addValue("masteryStatus", blankToNull(request.masteryStatus())));
        if (request.tagIds() != null) {
            replaceTags(id, request.tagIds());
        }
        return findById(id);
    }

    /**
     * 删除面试题记录。
     *
     * <p>标签关系由数据库级联删除。
     *
     * @param id 面试题 ID
     * @return 是否删除成功
     */
    public boolean delete(Long id) {
        return jdbc.update("DELETE FROM core.interview_questions WHERE id = :id", Map.of("id", id)) > 0;
    }

    /**
     * 完整替换某道面试题的标签关系。
     *
     * @param questionId 面试题 ID
     * @param tagIds 新的标签 ID 集合
     */
    private void replaceTags(Long questionId, Collection<UUID> tagIds) {
        jdbc.update(
                "DELETE FROM core.interview_question_tags WHERE question_id = :questionId",
                Map.of("questionId", questionId));
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO core.interview_question_tags (question_id, tag_id) VALUES (:questionId, :tagId)",
                tagIds.stream().distinct().map(tagId -> new MapSqlParameterSource()
                        .addValue("questionId", questionId)
                        .addValue("tagId", tagId))
                        .toArray(MapSqlParameterSource[]::new));
    }

    /**
     * 查询面试题关联标签并组装响应对象。
     *
     * @param question 原始面试题响应
     * @return 包含标签详情的面试题响应
     */
    private QuestionModels.QuestionResponse withTags(QuestionModels.QuestionResponse question) {
        List<UUID> ids = jdbc.queryForList(
                "SELECT tag_id FROM core.interview_question_tags WHERE question_id = :questionId",
                Map.of("questionId", question.id()),
                UUID.class);
        return new QuestionModels.QuestionResponse(
                question.id(),
                question.moduleId(),
                question.moduleName(),
                question.question(),
                question.answer(),
                question.detailedAnswer(),
                question.followUpQuestions(),
                question.difficulty(),
                question.masteryStatus(),
                question.sourceType(),
                tagRepository.findByIds(ids),
                question.createdAt(),
                question.updatedAt());
    }

    /** 返回使用当前 Jackson 映射器的行映射器。 */
    private RowMapper<QuestionModels.QuestionResponse> rowMapper() {
        return this::mapRow;
    }

    /**
     * 将数据库行映射为面试题响应。
     *
     * @param resultSet 当前数据库行
     * @param rowNum 当前行号
     * @return 面试题响应
     * @throws SQLException 读取字段失败时抛出
     */
    private QuestionModels.QuestionResponse mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new QuestionModels.QuestionResponse(
                resultSet.getLong("id"),
                resultSet.getObject("module_id", UUID.class),
                resultSet.getString("module_name"),
                resultSet.getString("question"),
                resultSet.getString("answer"),
                resultSet.getString("detailed_answer"),
                resultSet.getString("follow_up_questions"),
                resultSet.getString("difficulty"),
                resultSet.getString("mastery_status"),
                resultSet.getString("source_type"),
                List.<TagModels.TagResponse>of(),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    /** 返回非空字符串，否则使用默认值。 */
    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 将空字符串规范化为数据库 {@code NULL}。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
