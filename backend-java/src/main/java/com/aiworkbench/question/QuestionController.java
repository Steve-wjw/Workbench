package com.aiworkbench.question;

import java.util.UUID;

import jakarta.validation.Valid;

import com.aiworkbench.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
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

/** 面试题 REST API。 */
@RestController
@RequestMapping("/api/v1/interview-questions")
@Tag(name = "AI 面经", description = "正式面试题的创建、筛选、编辑和删除。")
public class QuestionController {

    /** 面试题业务服务。 */
    private final QuestionService service;

    /**
     * 创建面试题控制器。
     *
     * @param service 面试题业务服务
     */
    public QuestionController(QuestionService service) {
        this.service = service;
    }

    /**
     * 按关键词、模块、标签、难度和掌握状态分页查询面试题。
     *
     * @param keyword 标题或问题关键词
     * @param moduleId 模块过滤条件
     * @param tagId 标签过滤条件
     * @param difficulty 难度过滤条件
     * @param masteryStatus 掌握状态过滤条件
     * @param page 页码，从 0 开始
     * @param size 每页大小
     * @return 分页面试题列表
     */
    @GetMapping
    @Operation(summary = "分页查询面试题")
    public PageResponse<QuestionModels.QuestionResponse> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UUID moduleId,
            @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String masteryStatus,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.list(keyword, moduleId, tagId, difficulty, masteryStatus, page, size);
    }

    /**
     * 创建面试题。
     *
     * @param request 创建请求
     * @return 创建后的面试题
     */
    @PostMapping
    @Operation(summary = "创建面试题")
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionModels.QuestionResponse create(
            @Valid @RequestBody QuestionModels.CreateQuestionRequest request) {
        return service.create(request);
    }

    /**
     * 查询面试题详情。
     *
     * @param id 面试题 ID
     * @return 面试题详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询面试题详情")
    public QuestionModels.QuestionResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /**
     * 部分更新面试题。
     *
     * @param id 面试题 ID
     * @param request 更新请求
     * @return 更新后的面试题
     */
    @PatchMapping("/{id}")
    @Operation(summary = "更新面试题")
    public QuestionModels.QuestionResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody QuestionModels.UpdateQuestionRequest request) {
        return service.update(id, request);
    }

    /**
     * 删除面试题及其标签关系。
     *
     * @param id 面试题 ID
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除面试题")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
