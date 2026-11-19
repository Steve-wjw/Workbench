-- 已有数据库执行此脚本后，再重启 Java 后端。
-- 仅新增可选字段，不修改已有题目；可重复执行。
-- Execution guide: docs/SQL/数据库脚本使用说明.md.
BEGIN;

ALTER TABLE core.interview_questions
    ADD COLUMN IF NOT EXISTS follow_up_questions TEXT;

COMMENT ON COLUMN core.interview_questions.follow_up_questions
    IS '追问面试题 Markdown 原文，多条追问存于同一字段';

COMMIT;
