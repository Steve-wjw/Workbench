package com.aiworkbench.tag;

import java.util.UUID;

import jakarta.validation.Valid;

import com.aiworkbench.common.PageResponse;

import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 标签 REST API。 */
@RestController
@RequestMapping("/api/v1/tags")
@Tag(name = "标签管理", description = "资料和面试题可复用的标签管理。")
public class TagController {

    /** 标签业务服务。 */
    private final TagService service;

    /**
     * 创建标签控制器。
     *
     * @param service 标签业务服务
     */
    public TagController(TagService service) {
        this.service = service;
    }

    /**
     * 分页查询标签。
     *
     * @param keyword 可选关键词
     * @param page 页码，从 0 开始
     * @param size 每页大小
     * @return 分页标签列表
     */
    @GetMapping
    public PageResponse<TagModels.TagResponse> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.list(keyword, page, size);
    }

    /**
     * 创建标签。
     *
     * @param request 创建请求
     * @return 创建后的标签
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TagModels.TagResponse create(@Valid @RequestBody TagModels.CreateTagRequest request) {
        return service.create(request);
    }

    /**
     * 查询单个标签。
     *
     * @param id 标签 ID
     * @return 标签详情
     */
    @GetMapping("/{id}")
    public TagModels.TagResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /**
     * 部分更新标签。
     *
     * @param id 标签 ID
     * @param request 更新请求
     * @return 更新后的标签
     */
    @PatchMapping("/{id}")
    public TagModels.TagResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody TagModels.UpdateTagRequest request) {
        return service.update(id, request);
    }

    /**
     * 删除标签。
     *
     * @param id 标签 ID
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
