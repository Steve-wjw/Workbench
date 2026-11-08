package com.aiworkbench.question;

import java.util.List;
import java.util.UUID;

import com.aiworkbench.common.ApiException;
import com.aiworkbench.common.PageRequest;
import com.aiworkbench.common.PageResponse;
import com.aiworkbench.module.ModuleRepository;
import com.aiworkbench.tag.TagRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 面试题业务服务。
 *
 * <p>负责面试题筛选、引用校验和事务边界。
 */
@Service
public class QuestionService {

    /** 面试题数据访问对象。 */
    private final QuestionRepository repository;

    /** 模块数据访问对象，用于校验所属模块。 */
    private final ModuleRepository moduleRepository;

    /** 标签数据访问对象，用于校验关联标签。 */
    private final TagRepository tagRepository;

    /**
     * 创建面试题服务。
     *
     * @param repository 面试题数据访问对象
     * @param moduleRepository 模块数据访问对象
     * @param tagRepository 标签数据访问对象
     */
    public QuestionService(
            QuestionRepository repository,
            ModuleRepository moduleRepository,
            TagRepository tagRepository) {
        this.repository = repository;
        this.moduleRepository = moduleRepository;
        this.tagRepository = tagRepository;
    }

    /**
     * 按关键词、模块、标签、难度和掌握状态分页查询。
     *
     * @param keyword 标题或问题关键词
     * @param moduleId 模块过滤条件
     * @param tagId 标签过滤条件
     * @param difficulty 难度过滤条件
     * @param masteryStatus 掌握状态过滤条件
     * @param page 页码
     * @param size 每页大小
     * @return 分页面试题列表
     */
    public PageResponse<QuestionModels.QuestionResponse> list(
            String keyword,
            UUID moduleId,
            UUID tagId,
            String difficulty,
            String masteryStatus,
            Integer page,
            Integer size) {
        return repository.findPage(
                keyword,
                moduleId,
                tagId,
                difficulty,
                masteryStatus,
                PageRequest.of(page, size));
    }

    /**
     * 查询面试题详情。
     *
     * @param id 面试题 ID
     * @return 面试题详情
     */
    public QuestionModels.QuestionResponse get(Long id) {
        QuestionModels.QuestionResponse question = repository.findById(id);
        if (question == null) {
            throw notFound(id);
        }
        return question;
    }

    /**
     * 创建面试题。
     *
     * @param request 创建请求
     * @return 创建后的面试题
     */
    @Transactional
    public QuestionModels.QuestionResponse create(QuestionModels.CreateQuestionRequest request) {
        validateReferences(request.moduleId(), request.tagIds());
        return repository.insert(request);
    }

    /**
     * 更新面试题。
     *
     * @param id 面试题 ID
     * @param request 更新请求
     * @return 更新后的面试题
     */
    @Transactional
    public QuestionModels.QuestionResponse update(Long id, QuestionModels.UpdateQuestionRequest request) {
        get(id);
        validateReferences(request.moduleId(), request.tagIds());
        return repository.update(id, request);
    }

    /**
     * 删除面试题。
     *
     * @param id 面试题 ID
     */
    @Transactional
    public void delete(Long id) {
        if (!repository.delete(id)) {
            throw notFound(id);
        }
    }

    /**
     * 校验模块和标签引用。
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
     * 创建面试题不存在异常。
     *
     * @param id 面试题 ID
     * @return 业务异常
     */
    private ApiException notFound(Long id) {
        return new ApiException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND", "面试题不存在: " + id);
    }
}
