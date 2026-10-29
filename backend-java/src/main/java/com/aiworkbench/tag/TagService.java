package com.aiworkbench.tag;

import java.util.UUID;

import com.aiworkbench.common.ApiException;
import com.aiworkbench.common.PageRequest;
import com.aiworkbench.common.PageResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签业务服务。
 *
 * <p>除基本 CRUD 外，还负责校验父标签关系，避免产生无效层级。
 */
@Service
public class TagService {

    /** 标签数据访问对象。 */
    private final TagRepository repository;

    /**
     * 创建标签服务。
     *
     * @param repository 标签数据访问对象
     */
    public TagService(TagRepository repository) {
        this.repository = repository;
    }

    /**
     * 分页查询标签。
     *
     * @param keyword 可选关键词
     * @param page 页码
     * @param size 每页大小
     * @return 分页标签列表
     */
    public PageResponse<TagModels.TagResponse> list(String keyword, Integer page, Integer size) {
        return repository.findPage(keyword, PageRequest.of(page, size));
    }

    /**
     * 查询标签详情。
     *
     * @param id 标签 ID
     * @return 标签详情
     */
    public TagModels.TagResponse get(UUID id) {
        TagModels.TagResponse tag = repository.findById(id);
        if (tag == null) {
            throw notFound(id);
        }
        return tag;
    }

    /**
     * 创建标签并校验父标签。
     *
     * @param request 创建请求
     * @return 创建后的标签
     */
    @Transactional
    public TagModels.TagResponse create(TagModels.CreateTagRequest request) {
        if (request.parentId() != null && !repository.exists(request.parentId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PARENT_TAG_NOT_FOUND", "父标签不存在");
        }
        return repository.insert(request);
    }

    /**
     * 更新标签并校验父标签不能指向自身或不存在的标签。
     *
     * @param id 标签 ID
     * @param request 更新请求
     * @return 更新后的标签
     */
    @Transactional
    public TagModels.TagResponse update(UUID id, TagModels.UpdateTagRequest request) {
        get(id);
        if (request.parentId() != null) {
            if (id.equals(request.parentId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARENT_TAG", "标签不能将自身作为父标签");
            }
            if (!repository.exists(request.parentId())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "PARENT_TAG_NOT_FOUND", "父标签不存在");
            }
        }
        return repository.update(id, request);
    }

    /**
     * 删除标签。
     *
     * @param id 标签 ID
     */
    @Transactional
    public void delete(UUID id) {
        if (!repository.delete(id)) {
            throw notFound(id);
        }
    }

    /**
     * 创建标签不存在异常。
     *
     * @param id 标签 ID
     * @return 标签不存在业务异常
     */
    private ApiException notFound(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "TAG_NOT_FOUND", "标签不存在: " + id);
    }
}
