package com.aiworkbench.module;

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

/**
 * 模块 REST API。
 *
 * <p>控制器只负责 HTTP 参数绑定和状态码定义，业务规则由 {@link ModuleService} 处理。
 */
@RestController
@RequestMapping("/api/v1/modules")
@Tag(name = "模块管理", description = "资料和面试题的一级分类管理。")
public class ModuleController {

    /** 模块业务服务。 */
    private final ModuleService service;

    /**
     * 创建模块控制器。
     *
     * @param service 模块业务服务
     */
    public ModuleController(ModuleService service) {
        this.service = service;
    }

    /**
     * 分页查询模块。
     *
     * @param keyword 可选关键词
     * @param page 页码，从 0 开始
     * @param size 每页大小
     * @return 分页模块列表
     */
    @GetMapping
    public PageResponse<ModuleModels.ModuleResponse> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.list(keyword, page, size);
    }

    /**
     * 创建模块。
     *
     * @param request 创建请求
     * @return 创建后的模块
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ModuleModels.ModuleResponse create(@Valid @RequestBody ModuleModels.CreateModuleRequest request) {
        return service.create(request);
    }

    /**
     * 查询单个模块。
     *
     * @param id 模块 ID
     * @return 模块详情
     */
    @GetMapping("/{id}")
    public ModuleModels.ModuleResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /**
     * 部分更新模块。
     *
     * @param id 模块 ID
     * @param request 更新请求
     * @return 更新后的模块
     */
    @PatchMapping("/{id}")
    public ModuleModels.ModuleResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody ModuleModels.UpdateModuleRequest request) {
        return service.update(id, request);
    }

    /**
     * 删除模块。
     *
     * @param id 模块 ID
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
