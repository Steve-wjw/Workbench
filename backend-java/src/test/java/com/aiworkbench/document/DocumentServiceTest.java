package com.aiworkbench.document;

import java.util.List;
import java.util.UUID;

import com.aiworkbench.common.ApiException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证简化后的资料状态与索引排队规则，不连接真实业务数据库。 */
class DocumentServiceTest {

    /** 记录服务调用的内存仓库。 */
    private final RecordingRepository repository = new RecordingRepository();

    /** 空模块和标签请求无需引用仓库，测试不引入外部服务。 */
    private final DocumentService service = new DocumentService(repository, null, null, "./data");

    /** 新资料不经过草稿环节，直接排入后台处理。 */
    @Test
    void createQueuesIndexingByDefault() {
        service.create(new DocumentModels.CreateDocumentRequest(
                null, "Title", "Content", null, null, null, null, List.of()));

        assertEquals("PENDING_INDEX", repository.document.status());
        assertEquals(1, repository.queuedJobs);
    }

    /** 已归档资料保留内容，不自动发起索引。 */
    @Test
    void createArchivedDoesNotQueueIndexing() {
        service.create(new DocumentModels.CreateDocumentRequest(
                null, "Title", "Content", null, null, null, "ARCHIVED", List.of()));

        assertEquals("ARCHIVED", repository.document.status());
        assertEquals(0, repository.queuedJobs);
    }

    /** 调用方不能创建草稿或伪造系统索引结果。 */
    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "INDEXED", "INDEXING", "FAILED"})
    void rejectsUserControlledIndexStatus(String status) {
        assertThrows(ApiException.class, () -> service.create(new DocumentModels.CreateDocumentRequest(
                null, "Title", "Content", null, null, null, status, List.of())));
        assertThrows(ApiException.class, () -> service.update(repository.id, update("Content", status)));
        assertEquals(0, repository.queuedJobs);
    }

    /** 表单重复提交相同正文不重置已成功的索引。 */
    @Test
    void unchangedContentKeepsIndexState() {
        service.update(repository.id, update("Content", null));

        assertEquals("INDEXED", repository.document.status());
        assertEquals(0, repository.queuedJobs);
    }

    /** 正文修改会使原索引失效并排入任务。 */
    @Test
    void changedContentQueuesIndexing() {
        service.update(repository.id, update("Changed", null));

        assertEquals("PENDING_INDEX", repository.document.status());
        assertEquals(1, repository.queuedJobs);
    }

    /** 归档时不新增任务，恢复时重新处理。 */
    @Test
    void archiveAndRestore() {
        service.update(repository.id, update("Changed", "ARCHIVED"));
        assertEquals("ARCHIVED", repository.document.status());
        assertEquals(0, repository.queuedJobs);
        assertThrows(ApiException.class, () -> service.reindex(repository.id));

        service.update(repository.id, update("Changed", "PENDING_INDEX"));
        assertEquals("PENDING_INDEX", repository.document.status());
        assertEquals(1, repository.queuedJobs);
    }

    /** 构造只提交正文和可选用户状态的更新请求。 */
    private DocumentModels.UpdateDocumentRequest update(String content, String status) {
        return new DocumentModels.UpdateDocumentRequest(
                null, null, content, null, null, null, status, List.of());
    }

    /** 仅实现本组测试需要的仓库操作，避免测试写入本地学习资料。 */
    private static class RecordingRepository extends DocumentRepository {

        /** 当前资料 ID。 */
        private final UUID id = UUID.randomUUID();

        /** 当前资料快照。 */
        private DocumentModels.DocumentResponse document = snapshot("Content", "INDEXED");

        /** 已创建的任务数。 */
        private int queuedJobs;

        /** 禁用真实 JDBC 依赖。 */
        RecordingRepository() {
            super(null, null);
        }

        /** 返回测试资料。 */
        @Override
        public DocumentModels.DocumentResponse findById(UUID documentId) {
            return document;
        }

        /** 模拟仓库默认待索引状态。 */
        @Override
        public DocumentModels.DocumentResponse insert(DocumentModels.CreateDocumentRequest request, String hash) {
            document = snapshot(request.content(), request.status() == null ? "PENDING_INDEX" : request.status());
            return document;
        }

        /** 保留未提交的正文与状态。 */
        @Override
        public DocumentModels.DocumentResponse update(
                UUID documentId, DocumentModels.UpdateDocumentRequest request, String hash) {
            document = snapshot(
                    request.content() == null ? document.content() : request.content(),
                    request.status() == null ? document.status() : request.status());
            return document;
        }

        /** 记录排队动作。 */
        @Override
        public DocumentModels.IndexJobResponse createIndexJob(UUID documentId, boolean forceRebuild) {
            queuedJobs++;
            return null;
        }

        /** 构造不依赖标签或时间戳的资料。 */
        private DocumentModels.DocumentResponse snapshot(String content, String status) {
            return new DocumentModels.DocumentResponse(
                    id, null, null, "Title", content, content, "MANUAL", null, null,
                    null, status, null, null, List.of(), null, null);
        }
    }
}
