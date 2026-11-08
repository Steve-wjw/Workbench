package com.aiworkbench.document;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.aiworkbench.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

/**
 * 资料 REST API。
 *
 * <p>支持手工资料 CRUD、Markdown 导入、资料重新索引和索引任务查询。
 */
@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "资料库", description = "资料管理、Markdown 导入和索引任务查询。")
public class DocumentController {

    /** 资料业务服务。 */
    private final DocumentService service;

    /**
     * 创建资料控制器。
     *
     * @param service 资料业务服务
     */
    public DocumentController(DocumentService service) {
        this.service = service;
    }

    /**
     * 按条件分页查询资料。
     *
     * @param keyword 标题或正文关键词
     * @param moduleId 模块过滤条件
     * @param status 状态过滤条件
     * @param tagId 标签过滤条件
     * @param page 页码，从 0 开始
     * @param size 每页大小
     * @return 分页资料列表
     */
    @GetMapping
    @Operation(summary = "分页查询资料")
    public PageResponse<DocumentModels.DocumentResponse> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) UUID moduleId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID tagId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.list(keyword, moduleId, status, tagId, page, size);
    }

    /**
     * 创建手工资料。
     *
     * @param request 创建请求
     * @return 创建后的资料
     */
    @PostMapping
    @Operation(summary = "创建手工资料")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentModels.DocumentResponse create(
            @Valid @RequestBody DocumentModels.CreateDocumentRequest request) {
        return service.create(request);
    }

    /**
     * 通过粘贴 Markdown 内容导入资料。
     *
     * @param request Markdown 导入请求
     * @return 导入结果
     */
    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "粘贴 Markdown 导入资料")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentModels.ImportDocumentResponse importMarkdown(
            @Valid @RequestBody DocumentModels.ImportMarkdownRequest request) {
        return service.importMarkdown(request);
    }

    /**
     * 通过上传 Markdown 文件导入资料。
     *
     * @param file Markdown 文件
     * @param title 可选标题；未提供时使用文件名
     * @param moduleId 所属模块 ID
     * @param tagIds 关联标签 ID
     * @return 导入结果
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传 Markdown 文件导入资料")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentModels.ImportDocumentResponse importMarkdownFile(
            @RequestParam MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) UUID moduleId,
            @RequestParam(required = false) List<UUID> tagIds) {
        return service.importMarkdownFile(file, title, moduleId, tagIds);
    }

    /**
     * 查询资料详情。
     *
     * @param id 资料 ID
     * @return 资料详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "查询资料详情")
    public DocumentModels.DocumentResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    /**
     * 部分更新资料。
     *
     * @param id 资料 ID
     * @param request 更新请求
     * @return 更新后的资料
     */
    @PatchMapping("/{id}")
    @Operation(summary = "更新资料")
    public DocumentModels.DocumentResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody DocumentModels.UpdateDocumentRequest request) {
        return service.update(id, request);
    }

    /**
     * 查询资料索引任务历史。
     *
     * @param id 资料 ID
     * @return 索引任务列表
     */
    @GetMapping("/{id}/jobs")
    @Operation(summary = "查询资料索引任务")
    public List<DocumentModels.IndexJobResponse> jobs(@PathVariable UUID id) {
        return service.listJobs(id);
    }

    /** 根据资料生成不落库的面经草稿。 */
    @PostMapping("/{id}/generate-interview-questions")
    @Operation(summary = "根据资料生成面经")
    public InterviewGenerationModels.GeneratedQuestionsResponse generateInterviewQuestions(@PathVariable UUID id) {
        return service.generateInterviewQuestions(id);
    }

    /**
     * 手动触发资料重新索引。
     *
     * @param id 资料 ID
     * @return 新建索引任务
     */
    @PostMapping("/{id}/reindex")
    @Operation(summary = "创建资料重建索引任务")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentModels.IndexJobResponse reindex(@PathVariable UUID id) {
        return service.reindex(id);
    }

    /**
     * 删除资料及其标签关系。
     *
     * @param id 资料 ID
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除资料", description = "同时清理关联索引任务和资料片段。")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
