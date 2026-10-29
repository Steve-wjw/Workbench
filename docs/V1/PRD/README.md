# AI 工作台 V1 运行说明

本机单用户资料与面试题工作台。当前不需要配置外部大模型。

## 环境

Java 21、Maven 3.9+、Python 3.14、Node.js 22+、npm、PostgreSQL 16 与 pgvector。
所有命令在项目根目录执行，数据库须事先创建。

## 首次配置

仅在 `.env` 不存在时从 `.env.example` 创建本地配置，切勿覆盖已有 `.env`：

```bash
test -f .env || cp .env.example .env
```

填写 `DATABASE_URL`、`SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、
`SPRING_DATASOURCE_PASSWORD` 及相应 PostgreSQL 字段；密码和账号不写入文档或 Git。
`DATABASE_URL` 含特殊字符时需要进行 URL 编码。
建议 `WORKBENCH_DATA_DIR` 使用项目下 `data` 的绝对路径，避免服务工作目录不同。
前端读取 `frontend/.env.local` 或 Vite 进程环境；统一启动脚本会导出根目录 `.env`。

## 安装与构建

```bash
mvn -f backend-java/pom.xml clean test
python3.14 -m venv backend-python/.venv
backend-python/.venv/bin/python -m pip install -e './backend-python[dev]'
npm --prefix frontend ci
npm --prefix frontend run build
```

## 初始化或升级

已有数据库先停止应用并备份，再执行：

```bash
bash scripts/init-local-database.sh
```

脚本启用 vector 扩展并创建当前业务表，可重复执行，不清空数据。
本次升级会将旧草稿资料转为待索引、修改状态约束，并补建缺少的待处理任务。
新库不再创建 AI 草稿和查询表；旧库的历史表不删除。

## 启动与停止

```bash
bash scripts/start-local.sh
```

启动 Java API、Python API、Python worker 和 Vite。
默认地址：前端 `http://localhost:5173`；Java 健康检查 `http://localhost:8080/api/v1/health`；
Python 健康检查 `http://localhost:8000/internal/v1/health`；
接口说明 `http://localhost:8080/swagger-ui.html`。

```bash
bash scripts/stop-local.sh
```

停止脚本管理的应用进程，不停止本机 PostgreSQL。手动启动的服务需在对应终端停止。
日志位于 `data/logs/`，PID 文件位于 `data/.pids/`。脚本使用 Bash，请勿用 `sh` 执行。

## 文档索引

- [产品需求](PRD-v1.md)
- [产品框架](PRD-FRAMEWORK-v1.md)
- [技术架构](TECHNICAL-ARCHITECTURE-v1.md)
- [开发与维护计划](DEVELOPMENT-PLAN-v1.md)
- [产品概览](../USER/PRODUCT-V1-OVERVIEW.md)
- [用户指南](../USER/USER-GUIDE-v1.md)
- [统一验证说明](../USER/VERIFICATION-v1.md)
- [备份与导出设计](../../BACKUP-EXPORT-DESIGN.md)

全量数据清理脚本为 `scripts/clear-local-database-data.sh`，仅用于明确要清空业务数据时，需先停服务并输入 `CLEAR` 确认；日常验证不执行它。
