package com.aiworkbench.module;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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
 * 模块数据访问层。
 *
 * <p>使用 {@link NamedParameterJdbcTemplate} 执行显式 SQL，
 * 将数据库记录映射为模块响应对象。
 */
@Repository
public class ModuleRepository {

    /** 模块查询结果映射器。 */
    private static final RowMapper<ModuleModels.ModuleResponse> ROW_MAPPER = ModuleRepository::mapRow;

    /** 命名参数 JDBC 模板。 */
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 创建模块数据访问对象。
     *
     * @param jdbc 命名参数 JDBC 模板
     */
    public ModuleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按关键词分页查询模块。
     *
     * @param keyword 模块名称或 slug 关键词
     * @param pageRequest 分页参数
     * @return 分页模块列表
     */
    public PageResponse<ModuleModels.ModuleResponse> findPage(String keyword, PageRequest pageRequest) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("keyword", normalizedKeyword)
                .addValue("likeKeyword", "%" + normalizedKeyword + "%")
                .addValue("limit", pageRequest.size())
                .addValue("offset", pageRequest.offset());
        List<ModuleModels.ModuleResponse> items = jdbc.query("""
                SELECT id, name, slug, description, icon, sort_order, status, created_at, updated_at
                FROM core.modules
                WHERE (:keyword = '' OR name ILIKE :likeKeyword OR slug ILIKE :likeKeyword)
                ORDER BY sort_order, name
                LIMIT :limit OFFSET :offset
                """, params, ROW_MAPPER);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM core.modules
                WHERE (:keyword = '' OR name ILIKE :likeKeyword OR slug ILIKE :likeKeyword)
                """, params, Long.class);
        return new PageResponse<>(items, pageRequest.page(), pageRequest.size(), total == null ? 0 : total);
    }

    /**
     * 根据 ID 查询模块。
     *
     * @param id 模块 ID
     * @return 模块详情；不存在时返回 {@code null}
     */
    public ModuleModels.ModuleResponse findById(UUID id) {
        List<ModuleModels.ModuleResponse> results = jdbc.query("""
                SELECT id, name, slug, description, icon, sort_order, status, created_at, updated_at
                FROM core.modules WHERE id = :id
                """, Map.of("id", id), ROW_MAPPER);
        return results.isEmpty() ? null : results.getFirst();
    }

    /**
     * 插入模块记录。
     *
     * @param request 创建请求
     * @return 创建后的模块
     */
    public ModuleModels.ModuleResponse insert(ModuleModels.CreateModuleRequest request) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO core.modules
                    (id, name, slug, description, icon, sort_order, status)
                VALUES
                    (:id, :name, :slug, :description, :icon, :sortOrder, :status)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", request.name().trim())
                .addValue("slug", request.slug().trim())
                .addValue("description", blankToNull(request.description()))
                .addValue("icon", blankToNull(request.icon()))
                .addValue("sortOrder", request.sortOrder() == null ? 0 : request.sortOrder())
                .addValue("status", request.status() == null ? "ACTIVE" : request.status()));
        return findById(id);
    }

    /**
     * 更新模块记录。
     *
     * @param id 模块 ID
     * @param request 更新请求
     * @return 更新后的模块
     */
    public ModuleModels.ModuleResponse update(UUID id, ModuleModels.UpdateModuleRequest request) {
        jdbc.update("""
                UPDATE core.modules
                SET name = COALESCE(:name, name),
                    slug = COALESCE(:slug, slug),
                    description = COALESCE(:description, description),
                    icon = COALESCE(:icon, icon),
                    sort_order = COALESCE(:sortOrder, sort_order),
                    status = COALESCE(:status, status),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", blankToNull(request.name()))
                .addValue("slug", blankToNull(request.slug()))
                .addValue("description", blankToNull(request.description()))
                .addValue("icon", blankToNull(request.icon()))
                .addValue("sortOrder", request.sortOrder())
                .addValue("status", request.status()));
        return findById(id);
    }

    /**
     * 删除模块记录。
     *
     * @param id 模块 ID
     * @return 是否删除成功
     */
    public boolean delete(UUID id) {
        return jdbc.update("DELETE FROM core.modules WHERE id = :id", Map.of("id", id)) > 0;
    }

    /**
     * 将模块数据库行映射为 API 响应对象。
     *
     * @param resultSet 当前数据库行
     * @param rowNum 当前行号
     * @return 模块响应对象
     * @throws SQLException 读取字段失败时抛出
     */
    private static ModuleModels.ModuleResponse mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new ModuleModels.ModuleResponse(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("name"),
                resultSet.getString("slug"),
                resultSet.getString("description"),
                resultSet.getString("icon"),
                resultSet.getInt("sort_order"),
                resultSet.getString("status"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    /** 将空字符串规范化为数据库 {@code NULL}。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
