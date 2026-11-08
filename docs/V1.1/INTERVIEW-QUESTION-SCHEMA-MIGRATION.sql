-- 面试题和标签表结构精简迁移。
-- 请先备份数据库，再在目标数据库中执行本脚本。
-- 只删除不再使用的字段，保留问题、答案、详细解释、分类和关系数据。

BEGIN;

-- 标签和模块都不再使用 slug。删除标签列会同时删除它的唯一约束。
ALTER TABLE core.tags
    DROP COLUMN IF EXISTS slug;
ALTER TABLE core.modules
    DROP COLUMN IF EXISTS slug;

-- 将原“简短答案”改为“答案”。已有内容会保留。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'core'
          AND table_name = 'interview_questions'
          AND column_name = 'short_answer'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'core'
          AND table_name = 'interview_questions'
          AND column_name = 'answer'
    ) THEN
        ALTER TABLE core.interview_questions
            RENAME COLUMN short_answer TO answer;
    END IF;
END $$;

-- 兼容已完成过部分迁移的数据库：若两个字段同时存在，删除旧字段。
ALTER TABLE core.interview_questions
    DROP COLUMN IF EXISTS short_answer;

-- 旧题目没有简短答案时保持 NULL，应用层新建和编辑会要求填写答案。
ALTER TABLE core.interview_questions
    DROP COLUMN IF EXISTS title,
    DROP COLUMN IF EXISTS key_points,
    DROP COLUMN IF EXISTS source_type,
    DROP COLUMN IF EXISTS status;

COMMIT;
