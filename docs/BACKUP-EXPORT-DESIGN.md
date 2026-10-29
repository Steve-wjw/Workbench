# 备份与导出设计

> 2026-10-26：当前代码不再提供 AI 查询或草稿接口。旧库中的历史表保留，整库备份时一并保护；新库不要求创建这些表。

本文件记录 V1 的数据保护边界和未来接口约定。当前 V1 不提供浏览器端一键备份或还原接口，避免在本地单用户环境中引入误覆盖风险。

## V1 数据边界

- PostgreSQL 是模块、标签、资料、题目、索引任务与资料片段的业务数据源；旧库可能另外包含查询与草稿历史。
- `data/imports/` 保存通过文件上传导入时保留的原始 Markdown 副本。
- `.env` 含本机连接信息，也可能保留旧模型 API Key，不得进入备份包、导出结果、浏览器响应或普通日志。本地配置另行安全保管。

## 当前备份方式

在停止本地服务后，使用 PostgreSQL 的 `pg_dump` 备份业务数据库，并同时备份实际 `WORKBENCH_DATA_DIR` 下的 `imports/`。粘贴或手工内容在数据库中，没有独立上传副本：

```bash
cd /Users/wjw/Documents/Working/Code/Ai/Agent/Workbench
set -a; source .env; set +a
mkdir -p data/backups
pg_dump "$DATABASE_URL" --format=custom --file "data/backups/workbench-$(date +%Y%m%d-%H%M%S).dump"
```

恢复必须在确认目标数据库允许被覆盖后执行，因此不提供自动化脚本。使用 `pg_restore` 前应先手动新建空数据库，并确认 `.env` 指向正确目标。

## 未来接口预留

未来版本可在 `/api/v1/exports` 下实现以下异步任务：

| 接口 | 语义 | 说明 |
|---|---|---|
| `POST /api/v1/exports` | 创建导出任务 | 支持题目、资料、模块和标签的 JSON/Markdown 导出。 |
| `GET /api/v1/exports/{id}` | 查询导出状态 | 返回状态、文件名、过期时间和错误摘要。 |
| `GET /api/v1/exports/{id}/download` | 下载导出文件 | 只服务本机回环地址，并限制一次性文件大小。 |
| `POST /api/v1/backups` | 创建数据库备份任务 | 封装 `pg_dump`，不在响应中回显连接字符串。 |
| `POST /api/v1/backups/{id}/restore` | 恢复备份 | 必须二次确认、独占停机并写入审计日志。 |

实现时需要：

- 将任务状态写入独立表，不能在 HTTP 请求线程执行长时间的 dump 或 restore。
- 导出前移除 API Key、密码、绝对本机路径和查询日志中的敏感内容。
- 生成完成后将文件存放在 `data/exports/` 或 `data/backups/`，并提供过期清理策略。
- 还原操作仅允许显式本地确认，不允许通过前端静默触发。
