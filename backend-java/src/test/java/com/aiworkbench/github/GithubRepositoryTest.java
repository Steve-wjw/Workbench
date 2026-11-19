package com.aiworkbench.github;

import java.util.ArrayList;
import java.util.List;

import com.aiworkbench.common.PageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import static org.junit.jupiter.api.Assertions.*;

class GithubRepositoryTest {
    private final RecordingJdbc jdbc = new RecordingJdbc();
    private final GithubRepository repository = new GithubRepository(jdbc) {
        @Override public GithubModels.StoredProject findById(Long id) {
            return new GithubModels.StoredProject(null, null);
        }
    };

    @Test
    void listAndCountBothRequireTheCategoryBeforePagination() {
        repository.findPage(GithubModels.Category.LEARNING, "agent", PageRequest.of(2, 20));
        assertEquals(2, jdbc.sql.size());
        for (String sql : jdbc.sql) {
            assertTrue(sql.contains("WHERE category = :category"));
            assertTrue(sql.contains("notes ILIKE :likeKeyword"));
        }
        assertEquals("LEARNING", jdbc.parameters.getFirst().getValue("category"));
        assertEquals(40, jdbc.parameters.getFirst().getValue("offset"));
        assertTrue(jdbc.sql.getFirst().contains("ORDER BY id DESC LIMIT :limit OFFSET :offset"));
    }

    @Test
    void duplicateLookupChecksNameAndRemoteIdentityWithinOneCategory() {
        repository.exists(GithubModels.Category.PERSONAL_DEMO, "Owner/Project", 123L, 5L);
        assertTrue(jdbc.sql.getFirst().contains("lower(full_name) = lower(:fullName)"));
        assertTrue(jdbc.sql.getFirst().contains("github_repo_id = :remoteId"));
        assertTrue(jdbc.sql.getFirst().contains("id <> :excludedId"));
        assertEquals("PERSONAL_DEMO", jdbc.parameters.getFirst().getValue("category"));
        assertEquals(123L, jdbc.parameters.getFirst().getValue("remoteId"));
        assertEquals(5L, jdbc.parameters.getFirst().getValue("excludedId"));
    }

    @Test
    void failedSyncSqlNeverOverwritesPreviouslyFetchedDataOrPersonalContent() {
        repository.markFailed(1L, "Unavailable");
        String update = jdbc.sql.getFirst();
        assertTrue(update.contains("sync_status = 'FAILED'"));
        for (String field : List.of("description =", "notes =", "category =", "last_synced_at =", "github_repo_id =")) {
            assertFalse(update.contains(field));
        }
    }

    @Test
    void editingCanClearNotesButCannotWriteRemoteMetadata() {
        repository.update(1L, new GithubModels.UpdateRequest(null, ""));
        String update = jdbc.sql.getFirst();
        assertEquals("", jdbc.parameters.getFirst().getValue("notes"));
        assertNull(jdbc.parameters.getFirst().getValue("category"));
        for (String field : List.of("description =", "stars =", "last_synced_at =", "repository_url =")) {
            assertFalse(update.contains(field));
        }
    }

    private static class RecordingJdbc extends NamedParameterJdbcTemplate {
        private final List<String> sql = new ArrayList<>();
        private final List<SqlParameterSource> parameters = new ArrayList<>();
        RecordingJdbc() { super(new JdbcTemplate()); }
        @Override public <T> List<T> query(String statement, SqlParameterSource params, RowMapper<T> mapper) {
            sql.add(statement); parameters.add(params); return List.of();
        }
        @Override public <T> List<T> query(String statement, java.util.Map<String, ?> params, RowMapper<T> mapper) {
            return List.of();
        }
        @Override public <T> T queryForObject(String statement, SqlParameterSource params, Class<T> type) {
            sql.add(statement); parameters.add(params); return type.cast(0L);
        }
        @Override public int update(String statement, SqlParameterSource params) {
            sql.add(statement); parameters.add(params); return 1;
        }
    }
}
