-- GitHub 项目独立建表，不更改现有资料、面试题或标签数据。
-- 先备份，在目标数据库完整执行，再启动更新后的 Java API。
-- Execution guide: docs/SQL/数据库脚本使用说明.md.
BEGIN;

CREATE TABLE IF NOT EXISTS core.github_projects (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category VARCHAR(32) NOT NULL,
    full_name VARCHAR(240) NOT NULL,
    repository_url VARCHAR(500) NOT NULL,
    github_repo_id BIGINT,
    description TEXT,
    language VARCHAR(120),
    stars BIGINT NOT NULL DEFAULT 0,
    forks BIGINT NOT NULL DEFAULT 0,
    topics TEXT[] NOT NULL DEFAULT '{}',
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    pushed_at TIMESTAMPTZ,
    notes TEXT NOT NULL DEFAULT '',
    sync_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    sync_error TEXT,
    etag TEXT,
    last_synced_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT github_projects_category_check CHECK (category IN ('PERSONAL_DEMO', 'LEARNING')),
    CONSTRAINT github_projects_sync_status_check CHECK (sync_status IN ('PENDING', 'SUCCESS', 'FAILED')),
    CONSTRAINT github_projects_counts_check CHECK (stars >= 0 AND forks >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS github_projects_category_name_key
    ON core.github_projects (category, lower(full_name));
CREATE UNIQUE INDEX IF NOT EXISTS github_projects_category_remote_key
    ON core.github_projects (category, github_repo_id) WHERE github_repo_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS github_projects_category_id_idx ON core.github_projects (category, id DESC);

COMMIT;
