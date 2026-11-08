# AI 工作台 V1 统一验证说明

> 合并原阶段 0～6 的有效验证项，以 2026-10-26 简化范围为准。
> 本文是验收步骤，不代表各项已在你的运行环境通过。优先页面操作，数据库查询仅辅助核对，不依赖跨终端的 MODULE_ID 等变量。

## 1. 环境与升级

在项目根目录检查：

```bash
java -version
mvn -version
python3.14 --version
node --version
npm --version
psql --version
```

要求 Java 21、Maven 3.9+、Python 3.14、Node.js 22+、PostgreSQL 16 与 vector 扩展。
配置与安装依赖参见 [运行说明](../PRD/README.md)。

升级前停止服务并备份，随后执行以下命令。不要执行全量清理脚本：

```bash
bash scripts/stop-local.sh
bash scripts/init-local-database.sh
```

预期：不删除资料、题目或旧历史表；旧 `DRAFT` 资料转成待索引并补建任务。

## 2. 自动化检查与启动

```bash
mvn -f backend-java/pom.xml clean test
backend-python/.venv/bin/python -m pytest backend-python/tests
npm --prefix frontend run build
bash scripts/start-local.sh
```

检查健康接口，使用 `-i` 保留 HTTP 状态与错误正文：

```bash
curl -sS -i http://localhost:8080/api/v1/health
curl -sS -i http://localhost:8000/internal/v1/health
```

预期：正常时 HTTP 200，Java 的 database/pythonRag 均为 UP。
worker 的实际可用性还需通过导入资料后任务完成来确认。

## 3. 页面验证

打开 `http://localhost:5173`。测试内容使用独立前缀 `V1验收-`，不要修改真实学习内容。

| 验证项 | 操作 | 预期 |
|---|---|---|
| 导航 | 依次打开五个页面 | 只有首页、AI 面经、资料库、标签管理、模块管理 |
| 首页 | 查看指标、标题和右上角 | 只有资料数和面试题数，无开发阶段说明、全局搜索或新建面试题按钮 |
| 模块 | 新建“V1验收-模块”，填写名称和描述 | 保存成功 |
| 标签 | 新建“V1验收-标签”，选择颜色，再编辑颜色 | 保存与修改成功 |
| 面经 | 在 AI 面经新建面试题，填写问题、答案、详细解释，选择模块、标签、简单、学习中 | 保存后可见；其他页面没有新建面试题入口 |
| 面经筛选 | 分别使用关键词、模块、标签、难度、掌握状态 | 满足条件时可见，不符合时不显示 |
| 面经编辑 | 编辑答案、改为已掌握，重新进入编辑 | 修改持久化 |
| 导入入口 | 资料库为空及非空时分别检查 | 都只有工具栏一个“导入 Markdown”，弹窗区分粘贴内容与选择文件 |
| 粘贴导入 | 标题填“V1验收-粘贴”，粘贴下方样例并选择模块和标签 | 显示资料和索引任务，最终变为已索引 |
| 上传导入 | 上传内容不同的 `.md` 文件 | 保存成功，详情和原文正确，磁盘保留副本 |
| 重复导入 | 再次导入完全相同的未归档正文 | 打开已有资料，不额外生成重复资料 |
| 手工资料 | 创建“V1验收-手工”，保存后刷新 | 自动处理，无草稿选项或草稿状态 |
| 资料搜索 | 搜索现有关键词，再搜索不存在的词 | 分别显示匹配内容或“没有匹配的资料”，不是误提示资料库为空 |
| 资料详情 | 切换预览、原文、规范化文本 | 标题、列表、代码块、加粗、行内代码、链接正确且无脚本执行 |
| 修改正文 | 编辑并保存不同的正文 | 创建索引任务，完成后规范化文本与正文一致 |
| 重复索引 | 同一活跃任务处理期间多次点击重新索引 | 复用活跃任务，不并行积压重复任务 |
| 归档与恢复 | 编辑勾选已归档；刷新；再取消勾选保存 | 归档状态不被任务覆盖；恢复后重新索引 |
| 错误提示 | 新建题目不填问题或答案 | 有校验或明确错误，原数据不丢失 |
| 删除资料/题目 | 删除一条专用资料和专用题目，先取消再确认 | 取消不删除；确认后记录消失，不影响其他题目 |
| 删除模块/标签 | 保留另一条专用资料关联测试分类，删除模块和标签 | 资料仍在，模块变未分类，对应标签移除 |
| 持久化 | 停止并重启服务 | 其他未删除的测试内容仍在 |

粘贴样例：

````markdown
# V1验收-位置编码

**自注意力**本身不包含序列位置信息。

- 顺序信息
- 位置编码

```python
print("position")
```

补充参考：[示例链接](https://example.com)
````

窄窗口也检查导航可滚动、按钮文字完整、表单和弹窗可滚动，正文或长标题不撑破页面。

## 4. 数据库辅助验证

在数据库客户端连接本地 workbench 数据库，直接执行下列只读 SQL。
若重复测试，可复用测试模块和标签，或先在页面删除旧测试项。

### 资料、分类与标签

```sql
SELECT d.id, d.title, d.status, m.name AS module_name,
       d.index_error, d.indexed_at
FROM core.documents d
LEFT JOIN core.modules m ON m.id = d.module_id
WHERE d.title LIKE 'V1验收-%'
ORDER BY d.updated_at DESC;

SELECT d.title, t.name AS tag_name
FROM core.documents d
JOIN core.document_tags dt ON dt.document_id = d.id
JOIN core.tags t ON t.id = dt.tag_id
WHERE d.title LIKE 'V1验收-%';

SELECT id, title FROM core.documents WHERE status = 'DRAFT';
```

最后一条应为零行；新资料完成索引后为 INDEXED，归档资料保持 ARCHIVED。
删除模块后 module_name 为空，删除标签后标签关联行消失。

### 题目与掌握状态

```sql
SELECT q.id, q.question, q.answer, q.detailed_answer,
       q.difficulty, q.mastery_status, m.name AS module_name
FROM core.interview_questions q
LEFT JOIN core.modules m ON m.id = q.module_id
WHERE q.question LIKE 'V1验收-%';

SELECT q.question, t.name AS tag_name
FROM core.interview_questions q
JOIN core.interview_question_tags qt ON qt.question_id = q.id
JOIN core.tags t ON t.id = qt.tag_id
WHERE q.question LIKE 'V1验收-%';
```

结果应与页面编辑和筛选条件一致。

### 索引任务与片段

```sql
SELECT d.title, j.status, j.attempt_count, j.max_attempts, j.error_message
FROM core.index_jobs j
JOIN core.documents d ON d.id = j.resource_id
WHERE j.job_type = 'INDEX_DOCUMENT' AND d.title LIKE 'V1验收-%'
ORDER BY j.created_at DESC;

SELECT d.title, c.chunk_index, c.title_path,
       c.embedding IS NOT NULL AS has_vector
FROM rag.document_chunks c
JOIN core.documents d ON d.id = c.document_id
WHERE d.title LIKE 'V1验收-%'
ORDER BY d.title, c.chunk_index;

SELECT resource_id, COUNT(*) AS active_jobs
FROM core.index_jobs
WHERE job_type = 'INDEX_DOCUMENT'
  AND status IN ('PENDING', 'RUNNING', 'RETRY_WAITING')
GROUP BY resource_id HAVING COUNT(*) > 1;

SELECT j.id
FROM core.index_jobs j
LEFT JOIN core.documents d ON d.id = j.resource_id
WHERE j.resource_type = 'DOCUMENT' AND d.id IS NULL;
```

新完成资料应有 SUCCEEDED 任务与片段；重复活跃任务查询应为零行。
本轮新删除资料不应遗留孤立任务；旧版本遗留项需单独核对，不作为本次清空对象。

## 5. 接口与排错

Swagger 中应只出现当前业务接口，无 AI 查询、草稿审核、提炼或模型配置接口。
导入失败可查看 `data/logs/java-api.log`；任务失败查看 `python-worker.log`。
日志的 `request_id` 可关联失败请求；日志、响应与文档不得包含本地密码和模型 Key。

不要用 `curl -f` 丢弃错误正文后继续解析 JSON。HTTP 400 通常检查参数，409 检查重复或归档限制，
500 按请求 ID 查 Java 日志；待索引不动时查 worker 和数据库连接。

## 6. 清理验证数据

在页面只删除带 `V1验收-` 前缀的资料和题目，再删除对应标签与模块。
上传副本按需要人工核对后清理，勿删除其他学习资料。
不使用全量数据清理脚本作为日常验收收尾。

旧 AI 草稿、查询表可能仍在已有数据库里，属于兼容历史数据，不是本轮页面验证对象；
新数据库不要求存在这些表。备份策略见 [备份设计](../../BACKUP-EXPORT-DESIGN.md)。
