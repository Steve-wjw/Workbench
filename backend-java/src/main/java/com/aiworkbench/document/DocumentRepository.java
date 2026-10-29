package com.aiworkbench.document;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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
 * 资料数据访问层。
 *
 * <p>负责资料主表和资料标签关系表的读写，并在返回结果时组装模块名称和标签详情。
 */
@Repository
public class DocumentRepository {

    /** 命名参数 JDBC 模板。 */
    private final NamedParameterJdbcTemplate jdbc;

    /** 标签数据访问对象，用于组装标签详情。 */
    private final TagRepository tagRepository;

    /**
     * 创建资料数据访问对象。
     *
     * @param jdbc 命名参数 JDBC 模板
     * @param tagRepository 标签数据访问对象
     */
    public DocumentRepository(NamedParameterJdbcTemplate jdbc, TagRepository tagRepository) {
        this.jdbc = jdbc;
        this.tagRepository = tagRepository;
    }

    /**
     * 按关键词、模块、状态和标签分页查询资料。
     *
     * @param keyword 标题或正文关键词
     * @param moduleId 模块过滤条件
     * @param status 状态过滤条件
     * @param tagId 标签过滤条件
     * @param pageRequest 分页参数
     * @return 分页资料列表
     */
    public PageResponse<DocumentModels.DocumentResponse> findPage(
            String keyword,
            UUID moduleId,
            String status,
            UUID tagId,
            PageRequest pageRequest) {
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        MapSqlParameterSource params = baseParams(normalizedKeyword, moduleId, status, tagId)
                .addValue("limit", pageRequest.size())
                .addValue("offset", pageRequest.offset());
        List<DocumentModels.DocumentResponse> items = jdbc.query("""
                SELECT d.id, d.module_id, m.name AS module_name, d.title, d.content, d.normalized_content,
                       d.source_type, d.source_name, d.source_path, d.content_hash,
                       d.status, d.index_error, d.indexed_at, d.created_at, d.updated_at
                FROM core.documents d
                LEFT JOIN core.modules m ON m.id = d.module_id
                WHERE (:keyword = '' OR d.title ILIKE :likeKeyword OR d.content ILIKE :likeKeyword
                       OR COALESCE(d.normalized_content, '') ILIKE :likeKeyword)
                  AND (CAST(:moduleId AS uuid) IS NULL OR d.module_id = CAST(:moduleId AS uuid))
                  AND (CAST(:status AS varchar) IS NULL OR d.status = CAST(:status AS varchar))
                  AND (CAST(:tagId AS uuid) IS NULL OR EXISTS (
                      SELECT 1 FROM core.document_tags dt_filter
                      WHERE dt_filter.document_id = d.id AND dt_filter.tag_id = CAST(:tagId AS uuid)
                  ))
                ORDER BY d.updated_at DESC
                LIMIT :limit OFFSET :offset
                """, params, rowMapper());
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM core.documents d
                WHERE (:keyword = '' OR d.title ILIKE :likeKeyword OR d.content ILIKE :likeKeyword
                       OR COALESCE(d.normalized_content, '') ILIKE :likeKeyword)
                  AND (CAST(:moduleId AS uuid) IS NULL OR d.module_id = CAST(:moduleId AS uuid))
                  AND (CAST(:status AS varchar) IS NULL OR d.status = CAST(:status AS varchar))
                  AND (CAST(:tagId AS uuid) IS NULL OR EXISTS (
                      SELECT 1 FROM core.document_tags dt_filter
                      WHERE dt_filter.document_id = d.id AND dt_filter.tag_id = CAST(:tagId AS uuid)
                  ))
                """, params, Long.class);
        return new PageResponse<>(
                items.stream().map(this::withTags).toList(),
                pageRequest.page(),
                pageRequest.size(),
                total == null ? 0 : total);
    }

    /**
     * 根据 ID 查询资料详情。
     *
     * @param id 资料 ID
     * @return 资料详情；不存在时返回 {@code null}
     */
    public DocumentModels.DocumentResponse findById(UUID id) {
        List<DocumentModels.DocumentResponse> results = jdbc.query("""
                SELECT d.id, d.module_id, m.name AS module_name, d.title, d.content, d.normalized_content,
                       d.source_type, d.source_name, d.source_path, d.content_hash,
                       d.status, d.index_error, d.indexed_at, d.created_at, d.updated_at
                FROM core.documents d
                LEFT JOIN core.modules m ON m.id = d.module_id
                WHERE d.id = :id
                """, Map.of("id", id), rowMapper());
        return results.isEmpty() ? null : withTags(results.getFirst());
    }

    /**
     * 根据内容哈希查找未归档资料。
     *
     * @param contentHash 内容 SHA-256 摘要
     * @return 已存在的资料；不存在时返回 {@code null}
     */
    public DocumentModels.DocumentResponse findByContentHash(String contentHash) {
        List<DocumentModels.DocumentResponse> results = jdbc.query("""
                SELECT d.id, d.module_id, m.name AS module_name, d.title, d.content, d.normalized_content,
                       d.source_type, d.source_name, d.source_path, d.content_hash,
                       d.status, d.index_error, d.indexed_at, d.created_at, d.updated_at
                FROM core.documents d
                LEFT JOIN core.modules m ON m.id = d.module_id
                WHERE d.content_hash = :contentHash AND d.status <> 'ARCHIVED'
                ORDER BY d.updated_at DESC
                LIMIT 1
                """, Map.of("contentHash", contentHash), rowMapper());
        return results.isEmpty() ? null : withTags(results.getFirst());
    }

    /**
     * 插入资料及其标签关系。
     *
     * @param request 创建请求
     * @param contentHash 正文摘要
     * @return 创建后的资料
     */
    public DocumentModels.DocumentResponse insert(
            DocumentModels.CreateDocumentRequest request,
            String contentHash) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO core.documents
                    (id, module_id, title, content, normalized_content, source_type, source_name, source_path,
                     content_hash, status)
                VALUES
                    (:id, :moduleId, :title, :content, :normalizedContent, :sourceType, :sourceName, :sourcePath,
                     :contentHash, :status)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("moduleId", request.moduleId())
                .addValue("title", request.title().trim())
                .addValue("content", request.content())
                .addValue("normalizedContent", request.content())
                .addValue("sourceType", defaultValue(request.sourceType(), "MANUAL"))
                .addValue("sourceName", blankToNull(request.sourceName()))
                .addValue("sourcePath", blankToNull(request.sourcePath()))
                .addValue("contentHash", contentHash)
                .addValue("status", defaultValue(request.status(), "PENDING_INDEX")));
        replaceTags(id, request.tagIds());
        return findById(id);
    }

    /**
     * 更新资料及其标签关系。
     *
     * @param id 资料 ID
     * @param request 更新请求
     * @param contentHash 新正文摘要；正文未变化时可以为 null
     * @return 更新后的资料
     */
    public DocumentModels.DocumentResponse update(
            UUID id,
            DocumentModels.UpdateDocumentRequest request,
            String contentHash) {
        jdbc.update("""
                UPDATE core.documents
                SET module_id = COALESCE(:moduleId, module_id),
                    title = COALESCE(:title, title),
                    content = COALESCE(:content, content),
                    normalized_content = COALESCE(:normalizedContent, normalized_content),
                    source_type = COALESCE(:sourceType, source_type),
                    source_name = COALESCE(:sourceName, source_name),
                    source_path = COALESCE(:sourcePath, source_path),
                    content_hash = COALESCE(:contentHash, content_hash),
                    index_error = CASE WHEN :content IS NULL THEN index_error ELSE NULL END,
                    indexed_at = CASE WHEN :content IS NULL THEN indexed_at ELSE NULL END,
                    status = COALESCE(:status, status),
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("moduleId", request.moduleId())
                .addValue("title", blankToNull(request.title()))
                .addValue("content", request.content())
                .addValue("normalizedContent", request.content())
                .addValue("sourceType", blankToNull(request.sourceType()))
                .addValue("sourceName", blankToNull(request.sourceName()))
                .addValue("sourcePath", blankToNull(request.sourcePath()))
                .addValue("contentHash", contentHash)
                .addValue("status", blankToNull(request.status())));
        if (request.tagIds() != null) {
            replaceTags(id, request.tagIds());
        }
        return findById(id);
    }

    /**
     * 删除资料记录。
     *
     * <p>标签关系由数据库级联删除。
     *
     * @param id 资料 ID
     * @return 是否删除成功
     */
    public boolean delete(UUID id) {
        jdbc.update(
                "DELETE FROM core.index_jobs WHERE resource_type = 'DOCUMENT' AND resource_id = :id",
                Map.of("id", id));
        return jdbc.update("DELETE FROM core.documents WHERE id = :id", Map.of("id", id)) > 0;
    }

    /**
     * 设置资料索引状态。
     *
     * @param id 资料 ID
     * @param status 索引状态
     * @param errorMessage 错误信息
     */
    public void updateIndexStatus(UUID id, String status, String errorMessage) {
        jdbc.update("""
                UPDATE core.documents
                SET status = :status,
                    index_error = :errorMessage,
                    indexed_at = CASE WHEN :status = 'INDEXED' THEN CURRENT_TIMESTAMP ELSE indexed_at END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status)
                .addValue("errorMessage", blankToNull(errorMessage)));
    }

    /**
     * 创建资料索引任务。
     *
     * @param documentId 资料 ID
     * @param forceRebuild 是否强制重建索引
     * @return 新建任务
     */
    public DocumentModels.IndexJobResponse createIndexJob(UUID documentId, boolean forceRebuild) {
        DocumentModels.IndexJobResponse activeJob = findActiveIndexJob(documentId);
        if (activeJob != null) {
            return activeJob;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO core.index_jobs
                    (id, job_type, resource_type, resource_id, force_rebuild)
                VALUES
                    (:id, 'INDEX_DOCUMENT', 'DOCUMENT', :resourceId, :forceRebuild)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("resourceId", documentId)
                .addValue("forceRebuild", forceRebuild));
        return findJobById(id);
    }

    /**
     * 查找一份资料当前正在处理或等待处理的索引任务。
     *
     * <p>重复点击重新索引时复用已有任务，避免为同一资料排入并行的重复工作。
     *
     * @param documentId 资料 ID
     * @return 活跃任务；不存在时返回 {@code null}
     */
    public DocumentModels.IndexJobResponse findActiveIndexJob(UUID documentId) {
        List<DocumentModels.IndexJobResponse> jobs = jdbc.query("""
                SELECT id, job_type, resource_type, resource_id, status, attempt_count, max_attempts,
                       force_rebuild, error_message, created_at, updated_at, started_at, finished_at
                FROM core.index_jobs
                WHERE resource_type = 'DOCUMENT'
                  AND resource_id = :documentId
                  AND job_type = 'INDEX_DOCUMENT'
                  AND status IN ('PENDING', 'RUNNING', 'RETRY_WAITING')
                ORDER BY created_at DESC
                LIMIT 1
                """, Map.of("documentId", documentId), DocumentRepository::mapJobRow);
        return jobs.isEmpty() ? null : jobs.getFirst();
    }

    /**
     * 查询资料的索引任务历史。
     *
     * @param documentId 资料 ID
     * @return 任务列表，按创建时间倒序
     */
    public List<DocumentModels.IndexJobResponse> findJobs(UUID documentId) {
        return jdbc.query("""
                SELECT id, job_type, resource_type, resource_id, status, attempt_count, max_attempts,
                       force_rebuild, error_message, created_at, updated_at, started_at, finished_at
                FROM core.index_jobs
                WHERE resource_type = 'DOCUMENT' AND resource_id = :documentId
                ORDER BY created_at DESC
                """, Map.of("documentId", documentId), DocumentRepository::mapJobRow);
    }

    /**
     * 查询资料最近一次索引任务。
     *
     * @param documentId 资料 ID
     * @return 最近任务；不存在时返回 {@code null}
     */
    public DocumentModels.IndexJobResponse findLatestJob(UUID documentId) {
        List<DocumentModels.IndexJobResponse> jobs = jdbc.query("""
                SELECT id, job_type, resource_type, resource_id, status, attempt_count, max_attempts,
                       force_rebuild, error_message, created_at, updated_at, started_at, finished_at
                FROM core.index_jobs
                WHERE resource_type = 'DOCUMENT' AND resource_id = :documentId
                ORDER BY created_at DESC
                LIMIT 1
                """, Map.of("documentId", documentId), DocumentRepository::mapJobRow);
        return jobs.isEmpty() ? null : jobs.getFirst();
    }

    /**
     * 根据任务 ID 查询任务。
     *
     * @param id 任务 ID
     * @return 任务响应
     */
    private DocumentModels.IndexJobResponse findJobById(UUID id) {
        return jdbc.queryForObject("""
                SELECT id, job_type, resource_type, resource_id, status, attempt_count, max_attempts,
                       force_rebuild, error_message, created_at, updated_at, started_at, finished_at
                FROM core.index_jobs
                WHERE id = :id
                """, Map.of("id", id), DocumentRepository::mapJobRow);
    }

    /**
     * 完整替换某份资料的标签关系。
     *
     * @param documentId 资料 ID
     * @param tagIds 新的标签 ID 集合
     */
    private void replaceTags(UUID documentId, Collection<UUID> tagIds) {
        jdbc.update("DELETE FROM core.document_tags WHERE document_id = :documentId", Map.of("documentId", documentId));
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO core.document_tags (document_id, tag_id) VALUES (:documentId, :tagId)",
                tagIds.stream().distinct().map(tagId -> new MapSqlParameterSource()
                        .addValue("documentId", documentId)
                        .addValue("tagId", tagId))
                        .toArray(MapSqlParameterSource[]::new));
    }

    /**
     * 查询资料关联的标签并组装到响应对象。
     *
     * @param document 原始资料响应
     * @return 包含标签详情的资料响应
     */
    private DocumentModels.DocumentResponse withTags(DocumentModels.DocumentResponse document) {
        List<UUID> ids = jdbc.queryForList(
                "SELECT tag_id FROM core.document_tags WHERE document_id = :documentId",
                Map.of("documentId", document.id()),
                UUID.class);
        return new DocumentModels.DocumentResponse(
                document.id(),
                document.moduleId(),
                document.moduleName(),
                document.title(),
                document.content(),
                document.normalizedContent(),
                document.sourceType(),
                document.sourceName(),
                document.sourcePath(),
                document.contentHash(),
                document.status(),
                document.indexError(),
                document.indexedAt(),
                tagRepository.findByIds(ids),
                document.createdAt(),
                document.updatedAt());
    }

    /** 返回资料行映射器。 */
    private RowMapper<DocumentModels.DocumentResponse> rowMapper() {
        return DocumentRepository::mapRow;
    }

    /**
     * 将数据库行映射为资料响应。
     *
     * @param resultSet 当前数据库行
     * @param rowNum 当前行号
     * @return 资料响应
     * @throws SQLException 读取字段失败时抛出
     */
    private static DocumentModels.DocumentResponse mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new DocumentModels.DocumentResponse(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("module_id", UUID.class),
                resultSet.getString("module_name"),
                resultSet.getString("title"),
                resultSet.getString("content"),
                resultSet.getString("normalized_content"),
                resultSet.getString("source_type"),
                resultSet.getString("source_name"),
                resultSet.getString("source_path"),
                resultSet.getString("content_hash"),
                "DRAFT".equals(resultSet.getString("status")) ? "PENDING_INDEX" : resultSet.getString("status"),
                resultSet.getString("index_error"),
                resultSet.getObject("indexed_at", OffsetDateTime.class),
                List.<TagModels.TagResponse>of(),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class));
    }

    /**
     * 将索引任务数据库行映射为 API 响应。
     *
     * @param resultSet 当前数据库行
     * @param rowNum 当前行号
     * @return 索引任务响应
     * @throws SQLException 读取字段失败时抛出
     */
    private static DocumentModels.IndexJobResponse mapJobRow(ResultSet resultSet, int rowNum) throws SQLException {
        return new DocumentModels.IndexJobResponse(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("job_type"),
                resultSet.getString("resource_type"),
                resultSet.getObject("resource_id", UUID.class),
                resultSet.getString("status"),
                resultSet.getInt("attempt_count"),
                resultSet.getInt("max_attempts"),
                resultSet.getBoolean("force_rebuild"),
                resultSet.getString("error_message"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("updated_at", OffsetDateTime.class),
                resultSet.getObject("started_at", OffsetDateTime.class),
                resultSet.getObject("finished_at", OffsetDateTime.class));
    }

    /**
     * 构造资料列表查询的通用参数。
     *
     * @param keyword 规范化后的关键词
     * @param moduleId 模块 ID
     * @param status 状态
     * @param tagId 标签 ID
     * @return SQL 命名参数
     */
    private MapSqlParameterSource baseParams(String keyword, UUID moduleId, String status, UUID tagId) {
        return new MapSqlParameterSource()
                .addValue("keyword", keyword)
                .addValue("likeKeyword", "%" + keyword + "%")
                .addValue("moduleId", moduleId, Types.OTHER)
                .addValue("status", blankToNull(status), Types.VARCHAR)
                .addValue("tagId", tagId, Types.OTHER);
    }

    /** 返回非空字符串，否则使用默认值。 */
    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 将空字符串规范化为数据库 NULL。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
