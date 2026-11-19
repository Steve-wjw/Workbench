package com.aiworkbench.github;

import com.aiworkbench.common.ApiException;
import com.aiworkbench.common.PageRequest;
import com.aiworkbench.common.PageResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class GithubService {
    private final GithubRepository repository;
    private final GithubClient client;

    public GithubService(GithubRepository repository, GithubClient client) {
        this.repository = repository;
        this.client = client;
    }

    public PageResponse<GithubModels.ProjectResponse> list(GithubModels.Category category, String keyword, PageRequest page) {
        return repository.findPage(category, keyword, page);
    }

    public GithubModels.Counts counts() {
        return repository.counts();
    }

    public GithubModels.ProjectResponse get(Long id) {
        return stored(id).project();
    }

    public GithubModels.PreviewResponse preview(String url) {
        GithubAddress address = GithubAddress.parse(url);
        try {
            var snapshot = client.fetch(address, null, null, false);
            return new GithubModels.PreviewResponse(snapshot.metadata().fullName(),
                    snapshot.metadata().repositoryUrl(), snapshot.metadata(), null);
        } catch (ApiException exception) {
            return new GithubModels.PreviewResponse(address.fullName(), address.url(), null, exception.getMessage());
        }
    }

    public GithubModels.ProjectResponse create(GithubModels.CreateRequest request) {
        GithubAddress address = GithubAddress.parse(request.repositoryUrl());
        checkDuplicate(request.category(), address.fullName(), null, null);
        GithubClient.Snapshot snapshot = null;
        String warning = null;
        try {
            snapshot = client.fetch(address, null, null, false);
        } catch (ApiException exception) {
            warning = exception.getMessage();
        }
        if (snapshot != null) {
            checkDuplicate(request.category(), snapshot.metadata().fullName(), snapshot.metadata().githubRepoId(), null);
        }
        try {
            return repository.insert(request, address, snapshot, warning);
        } catch (DataIntegrityViolationException exception) {
            throw duplicate();
        }
    }

    public GithubModels.ProjectResponse update(Long id, GithubModels.UpdateRequest request) {
        var project = get(id);
        if (request.category() != null) {
            checkDuplicate(request.category(), project.fullName(), project.githubRepoId(), id);
        }
        try {
            return repository.update(id, request);
        } catch (DataIntegrityViolationException exception) {
            throw duplicate();
        }
    }

    public GithubModels.ProjectResponse sync(Long id) {
        var stored = stored(id);
        var project = stored.project();
        var previous = project.githubRepoId() == null ? null : new GithubModels.Metadata(
                project.githubRepoId(), project.fullName(), project.repositoryUrl(), project.description(), project.language(),
                project.stars(), project.forks(), project.topics(), project.archived(), project.pushedAt());
        GithubClient.Snapshot snapshot;
        try {
            snapshot = client.fetch(GithubAddress.parse(project.repositoryUrl()), stored.etag(), previous, true);
        } catch (ApiException exception) {
            return repository.markFailed(id, exception.getMessage());
        }
        checkDuplicate(project.category(), snapshot.metadata().fullName(), snapshot.metadata().githubRepoId(), id);
        try {
            return repository.sync(id, snapshot);
        } catch (DataIntegrityViolationException exception) {
            throw duplicate();
        }
    }

    public void delete(Long id) {
        if (!repository.delete(id)) throw missing();
    }

    private GithubModels.StoredProject stored(Long id) {
        var stored = repository.findById(id);
        if (stored == null) throw missing();
        return stored;
    }

    private void checkDuplicate(GithubModels.Category category, String name, Long remoteId, Long excludedId) {
        if (repository.exists(category, name, remoteId, excludedId)) throw duplicate();
    }

    private static ApiException duplicate() {
        return new ApiException(HttpStatus.CONFLICT, "GITHUB_PROJECT_EXISTS", "该分类中已存在此 GitHub 项目。");
    }

    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "GITHUB_PROJECT_NOT_FOUND", "GitHub 项目不存在。");
    }
}
