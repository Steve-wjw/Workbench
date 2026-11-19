package com.aiworkbench.github;

import com.aiworkbench.common.PageRequest;
import com.aiworkbench.common.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/github-projects")
@Tag(name = "GitHub 项目", description = "自研与开源项目的分类管理、概况和个人备注。")
public class GithubController {
    private final GithubService service;

    public GithubController(GithubService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<GithubModels.ProjectResponse> list(
            @RequestParam GithubModels.Category category,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.list(category, keyword, PageRequest.of(page, size));
    }

    @GetMapping("/counts")
    public GithubModels.Counts counts() {
        return service.counts();
    }

    @PostMapping("/preview")
    public GithubModels.PreviewResponse preview(@Valid @RequestBody GithubModels.PreviewRequest request) {
        return service.preview(request.repositoryUrl());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GithubModels.ProjectResponse create(@Valid @RequestBody GithubModels.CreateRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    public GithubModels.ProjectResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    public GithubModels.ProjectResponse update(@PathVariable Long id, @Valid @RequestBody GithubModels.UpdateRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/sync")
    public GithubModels.ProjectResponse sync(@PathVariable Long id) {
        return service.sync(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
