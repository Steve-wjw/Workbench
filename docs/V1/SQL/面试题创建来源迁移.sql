-- 面试题创建来源：MANUAL = 手动增加（默认），DOCUMENT = 资料库生成。
-- 请先备份数据库，停止业务写入后完整执行，再重启更新后的 Java API。
-- 如还需执行“面试题与分类字段精简迁移.sql”，请先执行该历史脚本，
-- 最后执行本脚本；旧的字段精简脚本会删除 source_type。

-- Execution guide: docs/SQL/数据库脚本使用说明.md.
BEGIN;

ALTER TABLE core.interview_questions
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL';

-- 首次新增列时，所有已有题目由默认值标为 MANUAL。
-- 重复执行仅补齐空值，不覆盖之后生成的 DOCUMENT 标识。
UPDATE core.interview_questions
SET source_type = 'MANUAL'
WHERE source_type IS NULL;

ALTER TABLE core.interview_questions
    ALTER COLUMN source_type SET DEFAULT 'MANUAL',
    ALTER COLUMN source_type SET NOT NULL;

ALTER TABLE core.interview_questions
    DROP CONSTRAINT IF EXISTS questions_source_type_check;
ALTER TABLE core.interview_questions
    ADD CONSTRAINT questions_source_type_check
        CHECK (source_type IN ('MANUAL', 'DOCUMENT'));

COMMENT ON COLUMN core.interview_questions.source_type
    IS 'Creation source: MANUAL (default) or DOCUMENT; preserved on edits';

COMMIT;
