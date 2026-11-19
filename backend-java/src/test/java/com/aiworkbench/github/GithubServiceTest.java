package com.aiworkbench.github;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.aiworkbench.common.ApiException;
import com.aiworkbench.common.GlobalExceptionHandler;
import com.aiworkbench.common.PageRequest;
import com.aiworkbench.common.PageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** 不连接业务数据库或真实 GitHub。 */
class GithubServiceTest {
    private final MemoryRepository repository = new MemoryRepository();
    private final FakeClient client = new FakeClient();
    private final GithubService service = new GithubService(repository, client);
    private final MockMvc mvc = standaloneSetup(new GithubController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    private static final String URL = "https://github.com/owner/project";

    @Test
    void categoriesAreSeparatedAndTheSameRepositoryCanAppearInBoth() {
        service.create(create(GithubModels.Category.PERSONAL_DEMO));
        service.create(create(GithubModels.Category.LEARNING));
        for (var category : GithubModels.Category.values()) {
            var page = service.list(category, null, PageRequest.of(0, 20));
            assertEquals(1, page.total());
            assertEquals(category, page.items().getFirst().category());
        }
        assertEquals(new GithubModels.Counts(1, 1), service.counts());
    }

    @Test
    void normalizedAddressesAndRemoteIdsPreventDuplicateCreationAndMoves() {
        var personal = service.create(create(GithubModels.Category.PERSONAL_DEMO));
        var learned = service.create(create(GithubModels.Category.LEARNING));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> service.create(
                new GithubModels.CreateRequest(GithubModels.Category.PERSONAL_DEMO, "https://github.com/OWNER/PROJECT.git/", ""))).getStatus());
        assertThrows(ApiException.class, () -> service.update(learned.id(),
                new GithubModels.UpdateRequest(GithubModels.Category.PERSONAL_DEMO, "new notes")));
        assertEquals("**My notes**", service.get(personal.id()).notes());
        assertEquals(GithubModels.Category.LEARNING, service.get(learned.id()).category());
        client.snapshot = snapshot("Owner/Renamed");
        assertThrows(ApiException.class, () -> service.create(new GithubModels.CreateRequest(
                GithubModels.Category.PERSONAL_DEMO, "https://github.com/owner/renamed", "")));
    }

    @Test
    void failedLookupCanSavePendingAddressWithoutInventingMetadata() {
        client.failure = true;
        var preview = service.preview(URL);
        assertNull(preview.metadata());
        assertNotNull(preview.warning());
        var project = service.create(create(GithubModels.Category.LEARNING));
        assertEquals(GithubModels.SyncStatus.PENDING, project.syncStatus());
        assertNull(project.githubRepoId());
        assertNull(project.lastSyncedAt());
        assertEquals(URL, project.repositoryUrl());
        assertEquals("**My notes**", project.notes());
    }

    @Test
    void failedSyncRetainsMetadataPersonalNotesCategoryAndLastSuccessTime() {
        var first = service.create(create(GithubModels.Category.PERSONAL_DEMO));
        client.failure = true;
        var failed = service.sync(first.id());
        assertEquals(GithubModels.SyncStatus.FAILED, failed.syncStatus());
        assertEquals(first.notes(), failed.notes());
        assertEquals(first.description(), failed.description());
        assertEquals(first.lastSyncedAt(), failed.lastSyncedAt());
        assertEquals(first.category(), failed.category());
        assertNotNull(failed.syncError());
    }

    @Test
    void successfulSyncPreservesNotesAndCategoryAndEditingCanClearNotes() {
        var first = service.create(create(GithubModels.Category.PERSONAL_DEMO));
        client.snapshot = snapshot("Owner/Renamed");
        var updated = service.sync(first.id());
        assertEquals("Owner/Renamed", updated.fullName());
        assertEquals(first.notes(), updated.notes());
        assertEquals(first.category(), updated.category());
        assertEquals(GithubModels.SyncStatus.SUCCESS, updated.syncStatus());
        assertEquals("", service.update(first.id(), new GithubModels.UpdateRequest(null, "")).notes());
        assertEquals("Owner/Renamed", service.get(first.id()).fullName());
    }

    @Test
    void previewDoesNotWriteAndDeletingIsOnlyLocal() {
        service.preview(URL);
        assertTrue(repository.rows.isEmpty());
        var project = service.create(create(GithubModels.Category.LEARNING));
        service.delete(project.id());
        assertThrows(ApiException.class, () -> service.get(project.id()));
        assertThrows(ApiException.class, () -> service.delete(project.id()));
    }

    @Test
    void httpValidationRequiresCategoryAndRestrictsUrlsAndCategories() throws Exception {
        mvc.perform(post("/api/v1/github-projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryUrl\":\"" + URL + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/github-projects").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"OTHER\",\"repositoryUrl\":\"" + URL + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/github-projects/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryUrl\":\"https://localhost/admin\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/github-projects")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/github-projects").param("category", "LEARNING").param("size", "101"))
                .andExpect(status().isBadRequest());
        assertTrue(repository.rows.isEmpty());
    }

    @Test
    void apiReturnsStringIdsCountsAndRealCategoryFilters() throws Exception {
        var personal = service.create(create(GithubModels.Category.PERSONAL_DEMO));
        service.create(create(GithubModels.Category.LEARNING));
        mvc.perform(get("/api/v1/github-projects").param("category", "PERSONAL_DEMO"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].category").value("PERSONAL_DEMO"))
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[0].githubRepoId").value("9007199254740993"));
        mvc.perform(get("/api/v1/github-projects/{id}", personal.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(personal.id().toString()));
        mvc.perform(get("/api/v1/github-projects/counts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.personalDemo").value(1)).andExpect(jsonPath("$.learning").value(1));
        mvc.perform(delete("/api/v1/github-projects/{id}", personal.id())).andExpect(status().isNoContent());
    }

    private static GithubModels.CreateRequest create(GithubModels.Category category) {
        return new GithubModels.CreateRequest(category, URL, "**My notes**");
    }

    private static GithubClient.Snapshot snapshot(String name) {
        return new GithubClient.Snapshot(new GithubModels.Metadata(9_007_199_254_740_993L, name,
                "https://github.com/" + name.toLowerCase(), "Description", "Java", 10, 2, List.of("agent"), false, null),
                "\"version\"", OffsetDateTime.parse("2026-11-08T00:00:00Z"));
    }

    private static class FakeClient extends GithubClient {
        private boolean failure;
        private Snapshot snapshot = GithubServiceTest.snapshot("Owner/Project");
        FakeClient() { super(null, "", Clock.systemUTC()); }
        @Override public Snapshot fetch(GithubAddress address, String etag, GithubModels.Metadata previous, boolean force) {
            if (failure) throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_UNAVAILABLE", "Network unavailable");
            return snapshot;
        }
    }

    private static class MemoryRepository extends GithubRepository {
        private final Map<Long, GithubModels.StoredProject> rows = new LinkedHashMap<>();
        private long sequence = 9_007_199_254_740_993L;
        MemoryRepository() { super(null); }

        @Override public GithubModels.StoredProject findById(Long id) { return rows.get(id); }
        @Override public boolean delete(Long id) { return rows.remove(id) != null; }
        @Override public GithubModels.Counts counts() {
            return new GithubModels.Counts(rows.values().stream().filter(r -> r.project().category() == GithubModels.Category.PERSONAL_DEMO).count(),
                    rows.values().stream().filter(r -> r.project().category() == GithubModels.Category.LEARNING).count());
        }
        @Override public PageResponse<GithubModels.ProjectResponse> findPage(GithubModels.Category category, String keyword, PageRequest page) {
            var projects = rows.values().stream().map(GithubModels.StoredProject::project).filter(p -> p.category() == category).toList();
            return new PageResponse<>(projects, page.page(), page.size(), projects.size());
        }
        @Override public boolean exists(GithubModels.Category category, String name, Long remoteId, Long excludedId) {
            return rows.values().stream().map(GithubModels.StoredProject::project).anyMatch(p -> p.category() == category
                    && !p.id().equals(excludedId) && (p.fullName().equalsIgnoreCase(name) || remoteId != null && remoteId.equals(p.githubRepoId())));
        }
        @Override public GithubModels.ProjectResponse insert(GithubModels.CreateRequest request, GithubAddress address, GithubClient.Snapshot snapshot, String warning) {
            return write(sequence++, request.category(), address.fullName(), address.url(), request.notes(), snapshot,
                    snapshot == null ? GithubModels.SyncStatus.PENDING : GithubModels.SyncStatus.SUCCESS, warning);
        }
        @Override public GithubModels.ProjectResponse update(Long id, GithubModels.UpdateRequest request) {
            var old = rows.get(id);
            return write(id, request.category() == null ? old.project().category() : request.category(),
                    old.project().fullName(), old.project().repositoryUrl(),
                    request.notes() == null ? old.project().notes() : request.notes(), oldSnapshot(old), old.project().syncStatus(), old.project().syncError());
        }
        @Override public GithubModels.ProjectResponse sync(Long id, GithubClient.Snapshot snapshot) {
            var old = rows.get(id).project();
            return write(id, old.category(), old.fullName(), old.repositoryUrl(), old.notes(), snapshot, GithubModels.SyncStatus.SUCCESS, null);
        }
        @Override public GithubModels.ProjectResponse markFailed(Long id, String warning) {
            var old = rows.get(id);
            return write(id, old.project().category(), old.project().fullName(), old.project().repositoryUrl(),
                    old.project().notes(), oldSnapshot(old), GithubModels.SyncStatus.FAILED, warning);
        }
        private GithubClient.Snapshot oldSnapshot(GithubModels.StoredProject stored) {
            var p = stored.project();
            return p.githubRepoId() == null ? null : new GithubClient.Snapshot(new GithubModels.Metadata(p.githubRepoId(),
                    p.fullName(), p.repositoryUrl(), p.description(), p.language(), p.stars(), p.forks(), p.topics(), p.archived(), p.pushedAt()),
                    stored.etag(), p.lastSyncedAt());
        }
        private GithubModels.ProjectResponse write(Long id, GithubModels.Category category, String fullName, String url,
                String notes, GithubClient.Snapshot snapshot, GithubModels.SyncStatus status, String error) {
            var m = snapshot == null ? null : snapshot.metadata();
            var project = new GithubModels.ProjectResponse(id, category, m == null ? fullName : m.fullName(),
                    m == null ? url : m.repositoryUrl(), m == null ? null : m.githubRepoId(), m == null ? null : m.description(),
                    m == null ? null : m.language(), m == null ? 0 : m.stars(), m == null ? 0 : m.forks(),
                    m == null ? List.of() : m.topics(), m != null && m.archived(), m == null ? null : m.pushedAt(),
                    notes == null ? "" : notes, status, error, snapshot == null ? null : snapshot.fetchedAt(), null, null);
            rows.put(id, new GithubModels.StoredProject(project, snapshot == null ? null : snapshot.etag()));
            return project;
        }
    }
}
