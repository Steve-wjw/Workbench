package com.aiworkbench.tag;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aiworkbench.common.PageRequest;
import com.aiworkbench.common.PageResponse;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 标签数据访问层。
 *
 * <p>除标签自身的 CRUD 外，还统计标签在资料和面试题中的使用次数。
 */
@Repository
public class TagRepository {

    /** 标签查询结果映射器。 */
    private static final RowMapper<TagModels.TagResponse> ROW_MAPPER = TagRepository::mapRow;

    /** 命名参数 JDBC 模板。 */
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 创建标签数据访问对象。
     *
     * @param jdbc 命名参数 JDBC 模板
     */
    public TagRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按关键词分页查询标签。
     *
     * @param keyword 标签名称或 slug 关键词
     * @param pageRequest 分页参数
     * @return 分页标签列表
     */
    public PageResponse<TagModels.TagResponse> findPage(String keyword, PageRequest pageRequest) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("keyword", normalizedKeyword)
                .addValue("likeKeyword", "%" + normalizedKeyword + "%")
                .addValue("limit", pageRequest.size())
                .addValue("offset", pageRequest.offset());
        List<TagModels.TagResponse> items = jdbc.query("""
                SELECT t.id, t.name, t.slug, t.parent_id, t.color,
                       t.created_at, t.updated_at,
                       (SELECT COUNT(*) FROM core.document_tags dt WHERE dt.tag_id = t.id)
                         + (SELECT COUNT(*) FROM core.interview_question_tags qt WHERE qt.tag_id = t.id) AS usage_count
                FROM core.tags t
                WHERE (:keyword = '' OR t.name ILIKE :likeKeyword OR t.slug ILIKE :likeKeyword)
                ORDER BY t.name
                LIMIT :limit OFFSET :offset
                """, params, ROW_MAPPER);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM core.tags
                WHERE (:keyword = '' OR name ILIKE :likeKeyword OR slug ILIKE :likeKeyword)
                """, params, Long.class);
        return new PageResponse<>(items, pageRequest.page(), pageRequest.size(), total == null ? 0 : total);
    }

    /**
     * 根据 ID 查询标签。
     *
     * @param id 标签 ID
     * @return 标签详情；不存在时返回 {@code null}
     */
    public TagModels.TagResponse findById(UUID id) {
        List<TagModels.TagResponse> results = jdbc.query("""
                SELECT t.id, t.name, t.slug, t.parent_id, t.color,
                       t.created_at, t.updated_at,
                       (SELECT COUNT(*) FROM core.document_tags dt WHERE dt.tag_id = t.id)
                         + (SELECT COUNT(*) FROM core.interview_question_tags qt WHERE qt.tag_id = t.id) AS usage_count
                FROM core.tags t WHERE t.id = :id
                """, Map.of("id", id), ROW_MAPPER);
        return results.isEmpty() ? null : results.getFirst();
    }

    /**
     * 批量查询标签。
     *
     * @param ids 标签 ID 集合
     * @return 按名称排序的标签列表
     */
    public List<TagModels.TagResponse> findByIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT t.id, t.name, t.slug, t.parent_id, t.color,
                       t.created_at, t.updated_at,
                       (SELECT COUNT(*) FROM core.document_tags dt WHERE dt.tag_id = t.id)
                         + (SELECT COUNT(*) FROM core.interview_question_tags qt WHERE qt.tag_id = t.id) AS usage_count
                FROM core.tags t WHERE t.id IN (:ids) ORDER BY t.name
                """, Map.of("ids", ids), ROW_MAPPER);
    }

    /**
     * 插入标签记录。
     *
     * @param request 创建请求
     * @return 创建后的标签
     */
    public TagModels.TagResponse insert(TagModels.CreateTagRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO core.tags (id, name, slug, parent_id, color)
                VALUES (:id, :name, :slug, :parentId, :color)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", request.name().trim())
                .addValue("slug", request.slug().trim())
                .addValue("parentId", request.parentId())
                .addValue("color", blankToNull(request.color())));
        return findById(id);
    }

    /**
     * 更新标签记录。
     *
     * @param id 标签 ID
     * @param request 更新请求
     * @return 更新后的标签
     */
    public TagModels.TagResponse update(UUID id, TagModels.UpdateTagRequest request) {
        jdbc.update("""
                UPDATE core.tags
                SET name = COALESCE(:name, name),
                    slug = COALESCE(:slug, slug),
                    parent_id = COALESCE(:parentId, parent_id),
                    color = COALESCE(:color, color),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", blankToNull(request.name()))
                .addValue("slug", blankToNull(request.slug()))
                .addValue("parentId", request.parentId())
                .addValue("color", blankToNull(request.color())));
        return findById(id);
    }

    /**
     * 删除标签记录。
     *
     * @param id 标签 ID
     * @return 是否删除成功
     */
    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM core.tags WHERE id = :id", Map.of("id", id)) > 0;
    }

    /**
     * 判断标签是否存在。
     *
     * @param id 标签 ID
     * @return 标签是否存在
     */
    public boolean exists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM core.tags WHERE id = :id)",
                Map.of("id", id),
                Boolean.class));
    }

    /**
     * 将标签数据库行映射为 API 响应对象。
     *
     * @param resultSet 当前数据库行
     * @param rowNum 当前行号
     * @return 标签响应对象
     * @throws SQLException 读取字段失败时抛出
     */
    private static TagModels.TagResponse mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new TagModels.TagResponse(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("slug"),
                resultSet.getObject("parent_id", UUID.class),
                resultSet.getString("color"),
                resultSet.getLong("usage_count"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    /** 将空字符串规范化为数据库 {@code NULL}。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
