# AI 工作台 V2 技术架构方案

## 1. 架构目标

V2 在保留本地优先边界的前提下，解决四类工程问题：

1. 内容量增加后仍能分页、搜索和批量处理。
2. 导入、索引、导出、备份和 AI 操作采用统一任务模型。
3. 搜索和 AI 结果具备来源、版本和可回溯性。
4. 复习数据从当前题目状态扩展为可计算的时间序列。

## 2. 目标运行架构

```text
React / TypeScript / Vite
  └─ Java 21 / Spring Boot
       ├─ PostgreSQL 16 + pgvector
       ├─ 本地文件目录
       ├─ Python RAG / Parser API
       └─ 本地任务 Worker

Python
  ├─ Parser Provider: Markdown / Text / HTML / PDF
  ├─ Embedding Provider: lexical fallback / local model / optional remote model
  ├─ Retrieval service: lexical + vector hybrid search
  └─ AI Provider: optional chat completion with source constraints
```

V2 初期继续使用 PostgreSQL 作为业务库、任务队列和检索存储，不引入 Redis、Kafka 或独立向量数据库。只有在实际数据量和任务吞吐证明需要时再拆分基础设施。

## 3. 数据模型演进

### 3.1 迁移方式

V1 使用显式 SQL 初始化和兼容升级脚本。V2 应引入 Flyway 或同等级的数据库迁移工具：

- V1 现有初始化 SQL 保留为新库基线。
- V2 变化拆成有版本号的增量迁移。
- 每次迁移先备份，再在测试数据库执行。
- 不删除旧 AI 表，不重命名 V1 核心字段；通过兼容视图或新字段逐步迁移。

### 3.2 复习相关表

```text
core.review_cards
  id, question_id, state, due_at, last_reviewed_at,
  review_count, fsrs_state, created_at, updated_at

core.review_logs
  id, card_id, question_id, rating, scheduled_days,
  elapsed_days, source, reviewed_at
```

约束：

- `question_id` 唯一，题目删除时级联删除卡片和复习记录，或根据审计需要软删除。
- `review_logs` 只追加，不更新历史记录。
- `fsrs_state` 使用 JSONB 保存调度算法所需的版本化状态，便于升级算法。
- `due_at` 建立索引，支持今日队列按到期时间分页。

调度器使用 FSRS 兼容实现。业务层只负责把用户反馈映射为标准评分，并保存调度结果，不在 SQL 或 Controller 中实现算法。

### 3.3 来源关系表

```text
core.content_sources
  id, source_type, document_id, chunk_id, question_id,
  relation_type, quote_text, status, created_by,
  created_at, updated_at
```

建议的 `relation_type`：

- `SUPPORTS_ANSWER`
- `GENERATED_FROM`
- `RELATED_TO`
- `USER_REFERENCE`

约束：

- 一个来源关系至少关联一个题目和一个资料或片段。
- `quote_text` 保存用户接受时的短引文快照，避免源文变化后引用完全失真。
- 资料删除不级联删除题目，只将关系置为 `BROKEN`。

### 3.4 通用任务模型

当前 `core.index_jobs` 的约束只允许 `INDEX_DOCUMENT`。V2 可以有两种实现路径：

**推荐路径：扩展现有表**

- 将 `job_type` 从固定约束扩展为 `IMPORT_BATCH`、`PARSE_DOCUMENT`、`INDEX_DOCUMENT`、`EXPORT_DATA`、`BACKUP_DATABASE`、`AI_GENERATION`。
- `resource_type` 支持 `DOCUMENT`、`IMPORT_BATCH`、`EXPORT`、`BACKUP`、`AI_RUN`。
- 保留 `force_rebuild`，新增 `idempotency_key`、`progress`、`parent_job_id`、`metadata`。

**不推荐路径：每类任务独立建表**

只有当任务生命周期和保留策略完全不同，或任务吞吐需要独立扩展时才拆表，否则会重复实现领取、重试、日志和状态查询。

统一任务字段建议：

```text
status: PENDING | RUNNING | SUCCEEDED | FAILED | RETRY_WAITING | CANCELLED
attempt_count
max_attempts
next_run_at
progress
error_code
error_message
idempotency_key
parent_job_id
metadata
```

### 3.5 检索字段

现有 `rag.document_chunks` 已有 `search_vector` 和 `embedding vector(1024)`。V2 增加：

- `embedding_provider`
- `embedding_model`
- `embedding_dimension`
- `embedding_version`
- `source_locator`，保存页码、行号或 HTML 锚点等定位信息

不要继续用旧的 `BAAI/bge-m3` 标识代表哈希向量。迁移期应明确标识：

- `hash-v1`：离线确定性哈希，仅作为无模型回退。
- `local-<model>-<version>`：实际加载的本地模型。
- `remote-<provider>-<model>`：外部服务生成的向量。

不同维度或不同模型的向量不能混用。模型变化时创建新的索引版本，后台批量回填，完成后切换 active version。

## 4. 检索方案

### 4.1 查询链路

```text
用户查询
  -> Java Search API
  -> Python 生成查询向量（可选）
  -> PostgreSQL 关键词召回 + 向量召回
  -> 统一去重和排序
  -> 返回片段、来源、命中类型和定位信息
```

### 4.2 排序策略

第一版使用 Reciprocal Rank Fusion（RRF）合并两类结果，避免关键词分数和向量距离不可直接比较：

- 关键词召回：`tsvector` + `websearch_to_tsquery`。
- 语义召回：pgvector cosine distance。
- 结果按 `document_id/chunk_id` 去重。
- 对标题路径、资料标题、题目标题做轻量加权。
- 过滤条件在召回前执行，避免将归档资料或用户不可见内容带入结果。

没有可用 Embedding Provider 时只执行关键词召回，并在响应中标识降级状态。

### 4.3 API

```text
GET /api/v1/search
  q, scope, moduleId, tagId, type, status, page, size

GET /api/v1/search/documents/{id}/matches
  q, page, size
```

响应包含：

```json
{
  "items": [
    {
      "type": "DOCUMENT_CHUNK",
      "documentId": "...",
      "chunkId": "...",
      "title": "...",
      "titlePath": "...",
      "snippet": "...",
      "matchTypes": ["KEYWORD", "SEMANTIC"],
      "sourceLocator": {"page": 3}
    }
  ],
  "degraded": false
}
```

## 5. 导入与解析方案

### 5.1 Parser Provider

Python 侧定义统一接口：

```python
class ParserProvider(Protocol):
    def supports(self, content_type: str, filename: str) -> bool: ...
    def parse(self, payload: bytes, filename: str) -> ParsedDocument: ...
```

`ParsedDocument` 统一输出：

- 标题和正文。
- 规范化文本。
- 章节路径。
- 分块。
- 来源定位信息。
- 解析警告。

首批适配器：

- Markdown：复用当前解析器并增强表格、引用和列表信息。
- Text：按段落和标题启发式切分。
- HTML：去除脚本和样式，保留标题、正文、链接文本。
- PDF：按页提取文本，保留页码；扫描件 OCR 不作为 V2.0 必须项。

### 5.2 批量导入状态

```text
CREATED -> SCANNING -> PREVIEW_READY -> RUNNING
        -> PARTIAL_SUCCEEDED | SUCCEEDED | FAILED | CANCELLED
```

每个文件有子任务，批次只汇总状态。任务必须使用文件哈希和批次 ID 做幂等键，避免重复点击导致重复资料和重复索引。

## 6. AI Provider 方案

### 6.1 接口边界

Python 侧提供：

```text
EmbeddingProvider.embed(texts)
ChatProvider.generate(messages, response_schema)
```

Java 负责业务权限、正式内容写入、候选接受和审计；Python 负责模型调用、结构化输出和检索上下文组装。

### 6.2 配置原则

- V2.0 基础功能不要求模型 Key。
- V2.1 允许通过本地 `.env` 指定 Provider、模型、超时和限流。
- API Key 不写入数据库，不返回前端，不出现在导出包和普通日志。
- 模型调用失败时，保留候选任务和错误原因，允许重试。
- 提示词模板和响应 schema 有版本号，写入 AI 运行记录。

### 6.3 AI 运行记录

```text
core.ai_runs
  id, operation, provider, model, prompt_version,
  input_refs, output_json, status, error_message,
  created_at, finished_at
```

接受 AI 结果时写入 `content_sources`，并将 `ai_run_id` 写入正式内容的变更审计记录。

## 7. Java API 组织

新增模块：

```text
search/
review/
importjob/
exportjob/
backup/
source/
ai/
audit/
```

建议接口：

```text
GET  /api/v1/search
GET  /api/v1/review/queue
GET  /api/v1/review/stats
POST /api/v1/review/cards/{id}/reviews
GET  /api/v1/review/cards/{id}/history

POST /api/v1/import-batches
GET  /api/v1/import-batches/{id}
POST /api/v1/import-batches/{id}/retry
POST /api/v1/import-batches/{id}/cancel

GET  /api/v1/sources?questionId=...
POST /api/v1/sources
DELETE /api/v1/sources/{id}

POST /api/v1/exports
GET  /api/v1/exports/{id}
GET  /api/v1/exports/{id}/download
POST /api/v1/backups
GET  /api/v1/backups/{id}
POST /api/v1/backups/{id}/restore

POST /api/v1/ai/runs
GET  /api/v1/ai/runs/{id}
POST /api/v1/ai/runs/{id}/accept
POST /api/v1/ai/runs/{id}/reject
```

所有列表接口采用统一分页结构。长任务接口统一返回任务 ID，不在 HTTP 请求中执行解析、导出、备份或模型生成。

## 8. 前端结构

### 8.1 新页面

- 全局搜索。
- 今日复习。
- 导入批次。
- 导出与备份。
- AI 候选结果。

### 8.2 现有页面改造

- 首页增加待复习、逾期复习、失败任务和最近学习活动。
- 资料库改为服务端搜索和分页，支持批量选择。
- 面经改为服务端过滤，支持开始复习和查看复习历史。
- 资料详情显示来源引用、命中片段和“生成候选题”。
- 模块和标签页面补齐更新、层级和批量操作。

### 8.3 前端数据访问

不要继续在 `App.tsx` 中集中加载全部资源。建议：

- 每个页面管理自己的查询条件和分页状态。
- 抽出 API client 和资源类型。
- 搜索、复习、导入批次各自维护 loading/error/empty 状态。
- 后续可引入轻量查询缓存，但不需要在 V2 初期引入复杂全局状态框架。

## 9. 可靠性与安全

- Worker 使用 `FOR UPDATE SKIP LOCKED` 领取任务，延续当前实现。
- 增加 `next_run_at` 和指数退避，避免失败任务固定频率重试。
- 每个任务 handler 都必须幂等；重复执行不能产生重复片段、重复导出或重复复习记录。
- 备份恢复前强制停写或获得独占锁。
- 所有上传文件名清洗，来源路径必须位于数据目录内。
- Markdown/HTML 预览继续使用安全渲染，不直接注入任意 HTML。
- AI 输出中的链接、代码和引用都按不可信内容处理。

## 10. 兼容与升级策略

1. 为 V1 数据库创建版本基线。
2. 增加复习、来源、任务扩展字段，不改动现有 CRUD 接口语义。
3. 为所有存量面试题惰性创建复习卡片，首次进入复习页时补建也可以。
4. 将现有 `embedding_model` 的哈希数据迁移标记为 `hash-v1`，不假装已经是 BGE。
5. 新增语义模型后按版本重建片段向量，旧向量在迁移完成前继续提供回退。
6. V1 的 `/documents`、`/interview-questions`、`/modules`、`/tags` 接口继续可用。

