package com.aiworkbench.question;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.aiworkbench.common.PageRequest;
import com.aiworkbench.tag.TagModels;
import com.aiworkbench.tag.TagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import static org.junit.jupiter.api.Assertions.*;

/** 记录 SQL 和参数，不连接或修改真实数据库。 */
class QuestionRepositoryTest {
    private final RecordingJdbc jdbc = new RecordingJdbc();
    private final QuestionRepository repository = new QuestionRepository(jdbc, new TagRepository(null) {
        @Override
        public List<TagModels.TagResponse> findByIds(Collection<UUID> ids) {
            return List.of();
        }
    });

    @Test
    void createPreservesMarkdownWhitespaceAndMapsItBackToResponse() {
        String markdown = "\n## Follow-up\n\n    indented code\n\n";
        var response = repository.insert(new QuestionModels.CreateQuestionRequest(
                null, "Question", "Answer", null, markdown, null, null, null, null));

        assertEquals(markdown, jdbc.writtenFollowUps);
        assertEquals(markdown, response.followUpQuestions());
        assertTrue(jdbc.statements.getFirst().contains(":followUpQuestions"));
    }

    @Test
    void createUsesDatabaseGeneratedIdAndReusesItForTagRelations() {
        UUID tagId = UUID.randomUUID();
        var response = repository.insert(new QuestionModels.CreateQuestionRequest(
                null, "Question", "Answer", null, null, null, null, List.of(tagId), null));

        assertEquals(jdbc.id, response.id());
        assertTrue(jdbc.statements.getFirst().contains("RETURNING id"));
        assertFalse(jdbc.statements.getFirst().contains(":id"));
        assertEquals(List.of(jdbc.id), jdbc.tagQuestionIds);
        assertEquals(List.of(tagId), jdbc.tagIds);
    }

    @Test
    void listOrdersByPrimaryKeyBeforePagination() {
        repository.findPage(null, null, null, null, null, PageRequest.of(1, 20));

        String sql = jdbc.statements.getFirst();
        assertTrue(sql.contains("ORDER BY q.id ASC"));
        assertTrue(sql.indexOf("ORDER BY q.id ASC") < sql.indexOf("LIMIT :limit OFFSET :offset"));
        assertFalse(sql.contains("ORDER BY q.updated_at"));
    }

    @Test
    void patchCanClearFollowUpsWithAnEmptyStringWithoutReplacingTags() {
        var response = repository.update(jdbc.id, patch(""));

        assertEquals("", jdbc.writtenFollowUps);
        assertEquals("", response.followUpQuestions());
        assertTrue(jdbc.statements.getFirst()
                .contains("follow_up_questions = COALESCE(:followUpQuestions, follow_up_questions)"));
        assertFalse(jdbc.statements.stream().anyMatch(sql -> sql.startsWith("DELETE")));
    }

    @Test
    void patchPreservesStructuredFollowUpsInTheExistingTextColumn() {
        String payload = """
                {"version":1,"items":[{"question":"**Follow-up**","answer":"## Answer\\n\\n```java\\ncode\\n```"}]}
                """;
        var response = repository.update(jdbc.id, patch(payload));

        assertEquals(payload, jdbc.writtenFollowUps);
        assertEquals(payload, response.followUpQuestions());
    }

    @Test
    void omittedFollowUpsRemainNullSoPartialUpdatesKeepExistingContent() {
        jdbc.storedFollowUps = "## Existing";
        var response = repository.update(jdbc.id, patch(null));

        assertNull(jdbc.writtenFollowUps);
        assertEquals("## Existing", response.followUpQuestions());
    }

    @Test
    void listAndCountBothSearchFollowUpsAndListMapsField() {
        jdbc.storedFollowUps = "## Searchable follow-up";
        var page = repository.findPage("follow-up", null, null, null, null, PageRequest.of(0, 20));

        assertEquals("## Searchable follow-up", page.items().getFirst().followUpQuestions());
        assertEquals(2, jdbc.statements.stream().filter(sql -> sql.contains("q.follow_up_questions ILIKE :likeKeyword")).count());
    }

    @Test
    void omittedSourceDefaultsToManual() {
        var response = repository.insert(new QuestionModels.CreateQuestionRequest(
                null, "Question", "Answer", null, null, null, null, null, null));

        assertEquals("MANUAL", jdbc.storedSourceType);
        assertEquals("MANUAL", response.sourceType());
        assertTrue(jdbc.statements.getFirst().contains(":sourceType"));
    }

    @Test
    void documentSourceIsPersistedAndIncludedInDetailAndList() {
        var response = repository.insert(new QuestionModels.CreateQuestionRequest(
                null, "Question", "Answer", null, null, null, null, null, "DOCUMENT"));

        assertEquals("DOCUMENT", jdbc.storedSourceType);
        assertEquals("DOCUMENT", response.sourceType());
        assertEquals("DOCUMENT", repository.findById(jdbc.id).sourceType());
        assertEquals("DOCUMENT", repository.findPage(
                null, null, null, null, null, PageRequest.of(1, 20)).items().getFirst().sourceType());
        assertTrue(jdbc.statements.stream().filter(sql -> sql.contains("SELECT q.id"))
                .allMatch(sql -> sql.contains("q.source_type")));
    }

    @Test
    void editingDocumentQuestionPreservesOriginalSource() {
        jdbc.storedSourceType = "DOCUMENT";
        var response = repository.update(jdbc.id, patch("Updated follow-up"));

        assertEquals("DOCUMENT", response.sourceType());
        assertFalse(jdbc.statements.getFirst().contains("source_type"));
    }

    private QuestionModels.UpdateQuestionRequest patch(String followUps) {
        return new QuestionModels.UpdateQuestionRequest(null, null, null, null, followUps, null, null, null);
    }

    private static class RecordingJdbc extends NamedParameterJdbcTemplate {
        private final Long id = 42L;
        private final List<String> statements = new ArrayList<>();
        private final List<Long> tagQuestionIds = new ArrayList<>();
        private final List<UUID> tagIds = new ArrayList<>();
        private String writtenFollowUps;
        private String storedFollowUps;
        private String storedSourceType = "MANUAL";

        RecordingJdbc() {
            super(new JdbcTemplate());
        }

        @Override
        public int update(String sql, SqlParameterSource params) {
            statements.add(sql);
            if (params.hasValue("followUpQuestions")) {
                writtenFollowUps = (String) params.getValue("followUpQuestions");
                if (writtenFollowUps != null) storedFollowUps = writtenFollowUps;
            }
            if (params.hasValue("sourceType")) {
                storedSourceType = (String) params.getValue("sourceType");
            }
            return 1;
        }

        @Override
        public <T> List<T> query(String sql, Map<String, ?> params, RowMapper<T> mapper) {
            return mappedRows(sql, mapper);
        }

        @Override
        public <T> List<T> query(String sql, SqlParameterSource params, RowMapper<T> mapper) {
            return mappedRows(sql, mapper);
        }

        private <T> List<T> mappedRows(String sql, RowMapper<T> mapper) {
            statements.add(sql);
            ResultSet row = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch ((String) args[0]) {
                        case "id" -> id;
                        case "question" -> "Question";
                        case "answer" -> "Answer";
                        case "follow_up_questions" -> storedFollowUps;
                        case "source_type" -> storedSourceType;
                        default -> null;
                    });
            try {
                return List.of(mapper.mapRow(row, 0));
            } catch (SQLException exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public <T> T queryForObject(String sql, SqlParameterSource params, Class<T> type) {
            statements.add(sql);
            if (sql.stripLeading().startsWith("INSERT")) {
                assertFalse(params.hasValue("id"));
                writtenFollowUps = (String) params.getValue("followUpQuestions");
                storedFollowUps = writtenFollowUps;
                storedSourceType = (String) params.getValue("sourceType");
                return type.cast(id);
            }
            return type.cast(1L);
        }

        @Override
        public int[] batchUpdate(String sql, SqlParameterSource[] batchArgs) {
            statements.add(sql);
            for (SqlParameterSource params : batchArgs) {
                tagQuestionIds.add((Long) params.getValue("questionId"));
                tagIds.add((UUID) params.getValue("tagId"));
            }
            return new int[batchArgs.length];
        }

        @Override
        public <T> List<T> queryForList(String sql, Map<String, ?> params, Class<T> type) {
            return List.of();
        }
    }
}
