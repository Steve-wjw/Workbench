# 个人 AI 工作台 V1 技术架构

> 当前范围：2026-10-26 简化版，仅描述正在使用的实现。

## 1. 运行架构

```text
浏览器 React / TypeScript / Vite
  └─ Java 21 / Spring Boot / Maven
       ├─ PostgreSQL 16 + pgvector
       ├─ 本地上传目录
       └─ Python 健康检查

Python 3.14 / FastAPI：Markdown 解析预览、内部索引接口
Python Worker：轮询 PostgreSQL 任务表，解析资料并写入片段与索引状态
```

四个本机进程加一个 PostgreSQL 实例，不依赖 Docker、Redis、消息队列或外部大模型。
Python 不是闲置预留服务，它仍实际承担文档解析和后台索引。

## 2. 代码边界

| 目录 | 职责 |
|---|---|
| `frontend/src/` | 首页、面经、资料库、模块、标签和共用表单 |
| `backend-java/` | 业务 API、引用校验、事务、错误处理、OpenAPI |
| `backend-python/app/` | Markdown 规范化、切分、离线向量、worker、内部接口 |
| `infra/postgres/init/` | 显式初始化与兼容性 SQL |
| `scripts/` | 本地启停、初始化、需确认的全量数据清理 |
| `data/` | 本地上传副本、日志、PID 与备份，不纳入 Git |

已移除 Java 查询/草稿包、Python 检索/模型调用代码、相关评测和闲置配置示例。Python 开发测试使用 `httpx`，运行依赖不再直接要求它。

## 3. 数据与索引

当前表为 `core.modules`、`core.tags`、`core.documents`、`core.interview_questions`、
`core.document_tags`、`core.interview_question_tags`、`core.index_jobs`、`rag.document_chunks`。

新数据库不创建旧 AI 查询、草稿和生成来源表。已有这些表的数据库不执行删除，保留历史数据及其既有外键行为。

资料保存与任务创建共用 Java 事务；worker 使用 `FOR UPDATE SKIP LOCKED` 领取任务。
任务状态为 `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`RETRY_WAITING`，默认最多尝试三次。
重新索引复用已有活跃任务。

Markdown 保留标题路径、列表、段落与代码块，切分结果写入 `rag.document_chunks`。
默认向量是 1024 维离线确定性哈希向量，不是真正的 BGE 模型。为兼容已有数据，保留 `EMBEDDING_MODEL` 旧标识；不下载模型，也不调用网络 API。当前页面关键词搜索不使用向量相似度。

资料状态由后台维护；归档不被后台索引状态覆盖。取消归档后重新索引。
初始化 SQL 将旧 `DRAFT` 转成 `PENDING_INDEX`，修改约束和默认值，并为缺少活跃任务的待索引资料补建任务。
升级 SQL 在单个事务内执行，需先停服务再显式运行。

## 4. 接口与配置

Java 入口 `/api/v1`：`health`、`modules`、`tags`、`documents`、`interview-questions`。
资料支持 JSON/文件两种导入、任务历史和重新索引。
OpenAPI 位于 `/v3/api-docs`，Swagger UI 位于 `/swagger-ui.html`。

Python 保留 `/internal/v1/health`、`/health`、
`/internal/v1/parse/markdown`、`/internal/v1/index/documents`。
不再提供查询、问答、模型配置或提炼接口。

分页响应为 `{items, page, size, total}`。错误响应含 `code`、`message`、`requestId`、`details`。
Java 日志输出 `request_id`。综合健康检查覆盖数据库和 Python API，不代表 worker 已正常消费任务。

`.env` 为本地实际配置；`.env.example` 为无密码模板。
旧 `.env` 中的模型参数不修改、不删除，当前服务忽略它们。
初始化与启停方法见 [运行说明](README.md)，备份边界见 [备份设计](../../BACKUP-EXPORT-DESIGN.md)。
