package com.aiworkbench.module;

import java.util.UUID;

import com.aiworkbench.common.ApiException;
import com.aiworkbench.common.PageRequest;
import com.aiworkbench.common.PageResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模块业务服务。
 *
 * <p>负责模块资源的存在性校验、分页查询和事务边界。
 */
@Service
public class ModuleService {

    /** 模块数据访问对象。 */
    private final ModuleRepository repository;

    /**
     * 创建模块服务。
     *
     * @param repository 模块数据访问对象
     */
    public ModuleService(ModuleRepository repository) {
        this.repository = repository;
    }

    /**
     * 分页查询模块。
     *
     * @param keyword 可选关键词
     * @param page 页码
     * @param size 每页大小
     * @return 分页模块列表
     */
    public PageResponse<ModuleModels.ModuleResponse> list(String keyword, Integer page, Integer size) {
        return repository.findPage(keyword, PageRequest.of(page, size));
    }

    /**
     * 查询模块详情，不存在时抛出业务异常。
     *
     * @param id 模块 ID
     * @return 模块详情
     */
    public ModuleModels.ModuleResponse get(UUID id) {
        ModuleModels.ModuleResponse module = repository.findById(id);
        if (module == null) {
            throw notFound(id);
        }
        return module;
    }

    /**
     * 创建模块。
     *
     * @param request 创建请求
     * @return 创建后的模块
     */
    @Transactional
    public ModuleModels.ModuleResponse create(ModuleModels.CreateModuleRequest request) {
        return repository.insert(request);
    }

    /**
     * 更新模块。
     *
     * @param id 模块 ID
     * @param request 更新请求
     * @return 更新后的模块
     */
    @Transactional
    public ModuleModels.ModuleResponse update(UUID id, ModuleModels.UpdateModuleRequest request) {
        get(id);
        return repository.update(id, request);
    }

    /**
     * 删除模块。
     *
     * @param id 模块 ID
     */
    @Transactional
    public void delete(UUID id) {
        if (!repository.delete(id)) {
            throw notFound(id);
        }
    }

    /**
     * 创建模块不存在异常。
     *
     * @param id 模块 ID
     * @return 模块不存在业务异常
     */
    private ApiException notFound(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "MODULE_NOT_FOUND", "模块不存在: " + id);
    }
}
