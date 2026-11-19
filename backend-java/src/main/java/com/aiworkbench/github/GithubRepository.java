package com.aiworkbench.github;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.aiworkbench.common.PageRequest;
import com.aiworkbench.common.PageResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GithubRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public GithubRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PageResponse<GithubModels.ProjectResponse> findPage(
            GithubModels.Category category, String keyword, PageRequest page) {
        String search = keyword == null ? "" : keyword.trim();
        var params = new MapSqlParameterSource().addValue("category", category.name())
                .addValue("keyword", search).addValue("likeKeyword", "%" + search + "%")
                .addValue("limit", page.size()).addValue("offset", page.offset());
        String filter = """
                FROM core.github_projects
                WHERE category = :category
                  AND (:keyword = '' OR full_name ILIKE :likeKeyword
                       OR description ILIKE :likeKeyword OR notes ILIKE :likeKeyword)
                """;
        var items = jdbc.query("SELECT * " + filter + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params, (rs, row) -> mapRow(rs));
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + filter, params, Long.class);
        return new PageResponse<>(items, page.page(), page.size(), total == null ? 0 : total);
    }

    public GithubModels.Counts counts() {
        var result = jdbc.query("""
                SELECT COUNT(*) FILTER (WHERE category = 'PERSONAL_DEMO') AS personal_demo,
                       COUNT(*) FILTER (WHERE category = 'LEARNING') AS learning
                FROM core.github_projects
                """, Map.of(), (rs, row) -> new GithubModels.Counts(rs.getLong("personal_demo"), rs.getLong("learning")));
        return result.getFirst();
    }

    public GithubModels.StoredProject findById(Long id) {
        var rows = jdbc.query("SELECT * FROM core.github_projects WHERE id = :id", Map.of("id", id),
                (rs, row) -> new GithubModels.StoredProject(mapRow(rs), rs.getString("etag")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean exists(GithubModels.Category category, String fullName, Long remoteId, Long excludedId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM core.github_projects
                WHERE category = :category
                  AND (lower(full_name) = lower(:fullName)
                       OR (CAST(:remoteId AS bigint) IS NOT NULL AND github_repo_id = :remoteId))
                  AND (CAST(:excludedId AS bigint) IS NULL OR id <> :excludedId)
                """, new MapSqlParameterSource().addValue("category", category.name())
                .addValue("fullName", fullName).addValue("remoteId", remoteId).addValue("excludedId", excludedId), Long.class);
        return count != null && count > 0;
    }

    public GithubModels.ProjectResponse insert(GithubModels.CreateRequest request, GithubAddress address,
                                               GithubClient.Snapshot snapshot, String warning) {
        var params = metadataParams(snapshot).addValue("category", request.category().name())
                .addValue("fullName", snapshot == null ? address.fullName() : snapshot.metadata().fullName())
                .addValue("repositoryUrl", snapshot == null ? address.url() : snapshot.metadata().repositoryUrl())
                .addValue("notes", request.notes() == null ? "" : request.notes())
                .addValue("syncStatus", snapshot == null ? "PENDING" : "SUCCESS").addValue("syncError", warning);
        Long id = jdbc.queryForObject("""
                INSERT INTO core.github_projects
                    (category, full_name, repository_url, github_repo_id, description, language, stars, forks,
                     topics, archived, pushed_at, notes, sync_status, sync_error, etag, last_synced_at)
                VALUES (:category, :fullName, :repositoryUrl, :githubRepoId, :description, :language, :stars, :forks,
                        CAST(:topics AS text[]), :archived, :pushedAt, :notes, :syncStatus, :syncError, :etag, :lastSyncedAt)
                RETURNING id
                """, params, Long.class);
        return findById(id).project();
    }

    public GithubModels.ProjectResponse update(Long id, GithubModels.UpdateRequest request) {
        jdbc.update("""
                UPDATE core.github_projects
                SET category = COALESCE(:category, category), notes = COALESCE(:notes, notes),
                    updated_at = CURRENT_TIMESTAMP WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("category", request.category() == null ? null : request.category().name())
                .addValue("notes", request.notes()));
        return findById(id).project();
    }

    public GithubModels.ProjectResponse sync(Long id, GithubClient.Snapshot snapshot) {
        jdbc.update("""
                UPDATE core.github_projects
                SET full_name = :fullName, repository_url = :repositoryUrl, github_repo_id = :githubRepoId,
                    description = :description, language = :language, stars = :stars, forks = :forks,
                    topics = CAST(:topics AS text[]), archived = :archived, pushed_at = :pushedAt,
                    etag = :etag, last_synced_at = :lastSyncedAt, sync_status = 'SUCCESS', sync_error = NULL,
                    updated_at = CURRENT_TIMESTAMP WHERE id = :id
                """, metadataParams(snapshot).addValue("id", id)
                .addValue("fullName", snapshot.metadata().fullName())
                .addValue("repositoryUrl", snapshot.metadata().repositoryUrl()));
        return findById(id).project();
    }

    public GithubModels.ProjectResponse markFailed(Long id, String warning) {
        jdbc.update("""
                UPDATE core.github_projects SET sync_status = 'FAILED', sync_error = :warning,
                    updated_at = CURRENT_TIMESTAMP WHERE id = :id
                """, Map.of("id", id, "warning", warning));
        return findById(id).project();
    }

    public boolean delete(Long id) {
        return jdbc.update("DELETE FROM core.github_projects WHERE id = :id", Map.of("id", id)) > 0;
    }

    private MapSqlParameterSource metadataParams(GithubClient.Snapshot snapshot) {
        var meta = snapshot == null ? null : snapshot.metadata();
        return new MapSqlParameterSource().addValue("githubRepoId", meta == null ? null : meta.githubRepoId())
                .addValue("description", meta == null ? null : meta.description())
                .addValue("language", meta == null ? null : meta.language())
                .addValue("stars", meta == null ? 0L : meta.stars()).addValue("forks", meta == null ? 0L : meta.forks())
                .addValue("topics", meta == null ? new String[0] : meta.topics().toArray(String[]::new))
                .addValue("archived", meta != null && meta.archived()).addValue("pushedAt", meta == null ? null : meta.pushedAt())
                .addValue("etag", snapshot == null ? null : snapshot.etag())
                .addValue("lastSyncedAt", snapshot == null ? null : snapshot.fetchedAt());
    }

    private static GithubModels.ProjectResponse mapRow(ResultSet rs) throws SQLException {
        var array = rs.getArray("topics");
        List<String> topics = array == null ? List.of() : Arrays.asList((String[]) array.getArray());
        if (array != null) array.free();
        return new GithubModels.ProjectResponse(rs.getLong("id"), GithubModels.Category.valueOf(rs.getString("category")),
                rs.getString("full_name"), rs.getString("repository_url"), rs.getObject("github_repo_id", Long.class),
                rs.getString("description"), rs.getString("language"), rs.getLong("stars"), rs.getLong("forks"),
                topics, rs.getBoolean("archived"), rs.getObject("pushed_at", OffsetDateTime.class), rs.getString("notes"),
                GithubModels.SyncStatus.valueOf(rs.getString("sync_status")), rs.getString("sync_error"),
                rs.getObject("last_synced_at", OffsetDateTime.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}
