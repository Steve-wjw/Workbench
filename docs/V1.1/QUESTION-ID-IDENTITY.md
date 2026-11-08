# 面试题自增主键

面试题不再使用 UUID，也不另加题号字段。`core.interview_questions.id` 改为 `BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`，由数据库从 1 开始生成；列表按 `id ASC` 展示，修改答案、追问和掌握状态不会改变题目位置。

以下面试题引用字段同步改为 BIGINT：

| 表与字段 | 删除题目时的行为 |
|---|---|
| `core.interview_question_tags.question_id` | 删除对应标签关联 |
| `core.ai_question_drafts.accepted_question_id`（旧库可选表） | 保留草稿，该字段置空 |
| `core.interview_question_sources.question_id`（旧库可选表） | 删除对应来源记录 |

草稿和来源表不存在时自动跳过，不创建历史表；存在时转换引用列，保留记录及其主键。迁移读取并恢复原外键定义及注释，不硬编码替换删除规则、延迟约束等属性。引用列原位转换，保留可空属性、主键、唯一约束和索引；非空旧 ID 缺少映射时主动报错，不静默转成 NULL。

模块、标签、资料自身的主键不在本次变更范围内，其他外键类型保持不变。

## 旧数据库升级

1. 备份数据库，并停止 Java API 等业务写入。
2. 在同一连接完整执行 `QUESTION-ID-IDENTITY-MIGRATION.sql`。不要只选中部分语句执行。
3. 重启更新后的 Java API，刷新前端。
4. 若还没有导入面试题，再执行更新后的 `AGENT-INTERVIEW-IMPORT.sql`；已导入的题目不需要重新导入。

迁移保留题目正文、答案、详细解释、追问、难度、掌握状态、模块、标签关系、旧版草稿采纳关系、来源关系及时间戳，仅替换面试题标识。旧题按 `created_at ASC, 旧 ID ASC` 从 1 编号；同一事务导入的题目可能具有相同创建时间，因此无法由旧数据推断它们在原始文档中的顺序。新的批量导入脚本按文档顺序生成自增 ID。

迁移在事务中执行，不使用 `CASCADE` 删除未知依赖，遇到额外引用面试题的表会报错并停止。数据库客户端报错后若仍处于未结束事务中，执行 `ROLLBACK;`，排查后重新完整执行。成功后重复执行不会重排编号。

旧 UUID 列不会保留，旧面试题详情地址和外部保存的旧 ID 将失效。浏览器会清除过期的 UUID 详情选择，但仍保留在 AI 面经模块，不会因此跳回首页。

新数据库直接使用更新后的初始化表结构，不需要先建 UUID 表再迁移。仅重新执行初始化建表脚本不能转换已有表，旧库必须执行专用迁移脚本。

## 额外外键报错

旧版迁移只处理标签关联，因此遇到 `core.ai_question_drafts.accepted_question_id` 和 `core.interview_question_sources.question_id` 时会触发 `Additional tables reference interview_questions`。现已根据用户提供的实际外键定义补齐这两处关联，遇到之前的失败后，先回滚失败事务，再完整执行更新后的同名迁移文件，不需要删除历史表或其数据。

如果使用更新后的脚本仍然出现该错误，说明还存在以上三处以外的引用，按下列步骤排查：

1. 若客户端仍处于失败事务中，先在原连接单独执行 `ROLLBACK;`。
2. 执行 `QUESTION-ID-REFERENCES-CHECK.sql`。这是一条只读查询，不修改数据库。
3. 提供完整结果，尤其是 `migration_status` 为 `requires_migration_plan` 的行；需要根据实际列、约束和关联数据补充迁移，再重新执行。

更新后的迁移脚本也会在主报错消息中列出这些表名、约束名和定义，不依赖客户端可能隐藏的 SQL 或 DETAIL 信息。`SQL text omitted from user-facing error` 不足以确定具体依赖；不要通过删除保护判断、删除历史表或 `DROP ... CASCADE` 绕过错误。

## 接口与编号

面试题接口的路径使用整数，例如 `/api/v1/interview-questions/1`。数据库和 Java 分别使用 BIGINT、Long；JSON 响应中 `id` 使用十进制字符串，例如 `"id": "1"`，避免浏览器对大整数的精度截断，不再返回 UUID。前端继续把 ID 当作不透明标识，不在浏览器里转成 Number 排序。

编号用于稳定排序，不保证删除、回滚后仍然连续，也不会因删除题目而给其他题目重新编号。重建 UUID 主键属于数据迁移，不能仅回退代码；如需回滚本次升级，应恢复升级前数据库备份并使用对应旧版代码。

## 验证

此前接口改造已通过后端 45 项测试、前端 35 项测试及前端生产构建。覆盖数据库生成 ID 的调用、标签关联使用返回的整数 ID、分页前按 ID 排序、整数详情和删除路由、大整数 ID 的 JSON 精度，以及刷新时清除旧 UUID 选择但保留当前模块。这些结果不代表本次历史外键迁移已经通过数据库执行验证。

独立 PostgreSQL 验证脚本为 `scripts/tests/question-identity-migration.test.mjs`。在项目根目录执行：

```bash
node --test scripts/tests/question-identity-migration.test.mjs
```

该脚本需要本机具备 `initdb`、`pg_ctl`、`psql`，并有创建共享内存和本地套接字的权限。它新建隔离的临时数据库，只使用临时目录的 Unix 套接字，不读取 `.env` 或连接业务数据库，测试后关闭实例。覆盖已有数据和标签保留、空表从 1 开始、重复迁移、未知外键保护及诊断信息、标签表额外引用保护、中途失败回滚、新版批量导入顺序和去重，以及历史草稿和来源记录、NULL、索引、唯一约束、外键删除行为的保留、只有部分历史表时的迁移和未映射引用的保护。

本次环境中，临时 PostgreSQL 启动仍被共享内存权限限制，提权审批服务也返回错误，10 项测试未能进入数据库执行阶段；不能将后端 SQL 记录测试视作真实数据库迁移验证。已根据用户返回的实际外键清单修订脚本，但业务数据库未被直接连接或修改。已通过测试脚本 JavaScript 语法检查和 `git diff --check`。
