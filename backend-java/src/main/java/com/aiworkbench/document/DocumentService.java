package com.aiworkbench.document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.aiworkbench.common.ApiException;
import com.aiworkbench.common.PageRequest;
import com.aiworkbench.common.PageResponse;
import com.aiworkbench.module.ModuleRepository;
import com.aiworkbench.tag.TagRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 资料业务服务。
 *
 * <p>负责资料和模块、标签之间的引用校验，并在保存时计算正文摘要。
 */
@Service
public class DocumentService {

    /** 资料数据访问对象。 */
    private final DocumentRepository repository;

    /** 模块数据访问对象，用于校验所属模块。 */
    private final ModuleRepository moduleRepository;

    /** 标签数据访问对象，用于校验关联标签。 */
    private final TagRepository tagRepository;

    /** 本地数据目录，用于保存上传的原始 Markdown 文件。 */
    private final Path dataDir;

    /**
     * 创建资料服务。
     *
     * @param repository 资料数据访问对象
     * @param moduleRepository 模块数据访问对象
     * @param tagRepository 标签数据访问对象
     */
    public DocumentService(
            DocumentRepository repository,
            ModuleRepository moduleRepository,
            TagRepository tagRepository,
            @Value("${workbench.data-dir}") String dataDir) {
        this.repository = repository;
        this.moduleRepository = moduleRepository;
        this.tagRepository = tagRepository;
        this.dataDir = Path.of(dataDir).toAbsolutePath().normalize();
    }

    /**
     * 按条件分页查询资料。
     *
     * @param keyword 标题或正文关键词
     * @param moduleId 模块过滤条件
     * @param status 状态过滤条件
     * @param tagId 标签过滤条件
     * @param page 页码
     * @param size 每页大小
     * @return 分页资料列表
     */
    public PageResponse<DocumentModels.DocumentResponse> list(
            String keyword,
            UUID moduleId,
            String status,
            UUID tagId,
            Integer page,
            Integer size) {
        return repository.findPage(keyword, moduleId, status, tagId, PageRequest.of(page, size));
    }

    /**
     * 查询资料详情。
     *
     * @param id 资料 ID
     * @return 资料详情
     */
    public DocumentModels.DocumentResponse get(UUID id) {
        DocumentModels.DocumentResponse document = repository.findById(id);
        if (document == null) {
            throw notFound(id);
        }
        return document;
    }

    /**
     * 创建资料，并计算正文 SHA-256 摘要。
     *
     * @param request 创建请求
     * @return 创建后的资料
     */
    @Transactional
    public DocumentModels.DocumentResponse create(DocumentModels.CreateDocumentRequest request) {
        validateReferences(request.moduleId(), request.tagIds());
        validateSourcePath(request.sourcePath());
        validateEditableStatus(request.status());
        DocumentModels.DocumentResponse document = repository.insert(request, hash(request.content()));
        if (!"ARCHIVED".equals(document.status())) {
            repository.createIndexJob(document.id(), false);
        }
        return document;
    }

    /**
     * 通过粘贴 Markdown 导入资料，并创建待处理的索引任务。
     *
     * @param request Markdown 导入请求
     * @return 导入结果
     */
    @Transactional
    public DocumentModels.ImportDocumentResponse importMarkdown(DocumentModels.ImportMarkdownRequest request) {
        validateReferences(request.moduleId(), request.tagIds());
        String contentHash = hash(request.markdown());
        DocumentModels.DocumentResponse existing = repository.findByContentHash(contentHash);
        if (existing != null) {
            return new DocumentModels.ImportDocumentResponse(
                    existing,
                    repository.findLatestJob(existing.id()),
                    true,
                    existing.id());
        }
        DocumentModels.DocumentResponse document = repository.insert(
                new DocumentModels.CreateDocumentRequest(
                        request.moduleId(),
                        request.title(),
                        request.markdown(),
                        "MARKDOWN",
                        request.sourceName(),
                        null,
                        "PENDING_INDEX",
                        request.tagIds()),
                contentHash);
        DocumentModels.IndexJobResponse job = repository.createIndexJob(document.id(), false);
        return new DocumentModels.ImportDocumentResponse(document, job, false, null);
    }

    /**
     * 通过上传 Markdown 文件导入资料，并创建待处理的索引任务。
     *
     * @param file Markdown 文件
     * @param title 资料标题
     * @param moduleId 所属模块 ID
     * @param tagIds 关联标签 ID
     * @return 导入结果
     */
    @Transactional
    public DocumentModels.ImportDocumentResponse importMarkdownFile(
            MultipartFile file,
            String title,
            UUID moduleId,
            List<UUID> tagIds) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "Markdown 文件不能为空");
        }
        String originalName = sanitizeFilename(file.getOriginalFilename());
        String resolvedTitle = title == null || title.isBlank()
                ? stripMarkdownExtension(originalName)
                : title.trim();
        try {
            String content = new String(file.getBytes(), StandardCharsets.UTF_8);
            String contentHash = hash(content);
            DocumentModels.DocumentResponse existing = repository.findByContentHash(contentHash);
            if (existing != null) {
                return new DocumentModels.ImportDocumentResponse(
                        existing,
                        repository.findLatestJob(existing.id()),
                        true,
                        existing.id());
            }
            Path savedPath = saveMarkdownFile(originalName, content);
            validateReferences(moduleId, tagIds);
            DocumentModels.DocumentResponse document = repository.insert(
                    new DocumentModels.CreateDocumentRequest(
                            moduleId,
                            resolvedTitle,
                            content,
                            "MARKDOWN",
                            originalName,
                            savedPath.toString(),
                            "PENDING_INDEX",
                            tagIds),
                    contentHash);
            DocumentModels.IndexJobResponse job = repository.createIndexJob(document.id(), false);
            return new DocumentModels.ImportDocumentResponse(document, job, false, null);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MARKDOWN_IMPORT_FAILED", "Markdown 文件导入失败");
        }
    }

    /**
     * 更新资料；正文发生变化时重新计算摘要。
     *
     * @param id 资料 ID
     * @param request 更新请求
     * @return 更新后的资料
     */
    @Transactional
    public DocumentModels.DocumentResponse update(UUID id, DocumentModels.UpdateDocumentRequest request) {
        DocumentModels.DocumentResponse current = get(id);
        validateReferences(request.moduleId(), request.tagIds());
        validateSourcePath(request.sourcePath());
        validateEditableStatus(request.status());
        String contentHash = request.content() == null ? null : hash(request.content());
        boolean contentChanged = request.content() != null && !request.content().equals(current.content());
        boolean archived = "ARCHIVED".equals(request.status())
                || (request.status() == null && "ARCHIVED".equals(current.status()));
        boolean needsIndex = !archived && (contentChanged || "PENDING_INDEX".equals(request.status()));
        DocumentModels.UpdateDocumentRequest effectiveRequest = new DocumentModels.UpdateDocumentRequest(
                        request.moduleId(),
                        request.title(),
                        contentChanged ? request.content() : null,
                        request.sourceType(),
                        request.sourceName(),
                        request.sourcePath(),
                        archived ? "ARCHIVED" : needsIndex ? "PENDING_INDEX" : null,
                        request.tagIds());
        DocumentModels.DocumentResponse document = repository.update(id, effectiveRequest, contentHash);
        if (needsIndex) {
            repository.createIndexJob(id, false);
        }
        return document;
    }

    /**
     * 查询资料的索引任务历史。
     *
     * @param id 资料 ID
     * @return 索引任务列表
     */
    public List<DocumentModels.IndexJobResponse> listJobs(UUID id) {
        get(id);
        return repository.findJobs(id);
    }

    /**
     * 创建资料重新索引任务。
     *
     * @param id 资料 ID
     * @return 新建任务
     */
    @Transactional
    public DocumentModels.IndexJobResponse reindex(UUID id) {
        if ("ARCHIVED".equals(get(id).status())) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_ARCHIVED", "请先取消归档，再重新索引");
        }
        DocumentModels.IndexJobResponse activeJob = repository.findActiveIndexJob(id);
        if (activeJob != null) {
            return activeJob;
        }
        repository.updateIndexStatus(id, "PENDING_INDEX", null);
        return repository.createIndexJob(id, true);
    }

    /**
     * 删除资料。
     *
     * @param id 资料 ID
     */
    @Transactional
    public void delete(UUID id) {
        if (!repository.delete(id)) {
            throw notFound(id);
        }
    }

    /**
     * 校验模块和标签引用，避免数据库外键错误转换为不明确的异常。
     *
     * @param moduleId 模块 ID
     * @param tagIds 标签 ID
     */
    private void validateReferences(UUID moduleId, List<UUID> tagIds) {
        if (moduleId != null && moduleRepository.findById(moduleId) == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MODULE_NOT_FOUND", "所属模块不存在");
        }
        if (tagIds != null && tagIds.stream().distinct().anyMatch(tagId -> !tagRepository.exists(tagId))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TAG_NOT_FOUND", "关联标签不存在");
        }
    }

    /**
     * 索引状态只由后台维护，调用方只能归档或提交待索引资料。
     *
     * @param status 可选的用户目标状态
     */
    private void validateEditableStatus(String status) {
        if (status != null && !status.isBlank()
                && !"PENDING_INDEX".equals(status) && !"ARCHIVED".equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT_STATUS", "资料只允许设置为待索引或已归档");
        }
    }

    /**
     * 校验调用方传入的本地路径不能逃出数据目录。
     *
     * @param sourcePath 来源路径
     */
    private void validateSourcePath(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return;
        }
        Path path = Path.of(sourcePath).toAbsolutePath().normalize();
        if (!path.startsWith(dataDir)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_PATH", "资料来源路径必须位于本地数据目录内");
        }
    }

    /**
     * 保存上传的 Markdown 原始文件。
     *
     * @param originalName 原始文件名
     * @param content 文件内容
     * @return 保存后的安全路径
     */
    private Path saveMarkdownFile(String originalName, String content) throws java.io.IOException {
        Path importDir = dataDir.resolve("imports").normalize();
        Files.createDirectories(importDir);
        Path target = importDir.resolve(UUID.randomUUID() + "-" + originalName).normalize();
        if (!target.startsWith(importDir)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_PATH", "Markdown 文件路径无效");
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return target;
    }

    /**
     * 清洗上传文件名，避免路径穿越字符进入保存路径。
     *
     * @param filename 原始文件名
     * @return 安全文件名
     */
    private String sanitizeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "import.md" : filename;
        String sanitized = value.replace("\\", "/");
        sanitized = sanitized.substring(sanitized.lastIndexOf('/') + 1);
        sanitized = sanitized.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!sanitized.endsWith(".md") && !sanitized.endsWith(".markdown")) {
            sanitized = sanitized + ".md";
        }
        return sanitized;
    }

    /**
     * 从 Markdown 文件名生成默认资料标题。
     *
     * @param filename 文件名
     * @return 去掉扩展名的标题
     */
    private String stripMarkdownExtension(String filename) {
        return filename.replaceFirst("(?i)\\.markdown$", "").replaceFirst("(?i)\\.md$", "");
    }

    /**
     * 创建资料不存在异常。
     *
     * @param id 资料 ID
     * @return 业务异常
     */
    private ApiException notFound(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "资料不存在: " + id);
    }

    /**
     * 计算正文的 SHA-256 十六进制摘要。
     *
     * @param content 正文
     * @return 内容摘要
     */
    private String hash(String content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
