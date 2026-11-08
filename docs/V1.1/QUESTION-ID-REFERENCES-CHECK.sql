-- 只读诊断：列出数据库中引用面试题表的全部外键，不执行迁移或修改数据。
-- 若之前的迁移仍停在失败事务中，先单独执行 ROLLBACK;，再执行本查询。
-- 将本查询的完整结果提供给开发者；不要删除外键或使用 DROP ... CASCADE 绕过检查。

WITH supported_columns AS (
    SELECT to_regclass(target.table_name) AS table_oid, target.column_name
    FROM (VALUES
        ('core.interview_question_tags', 'question_id'),
        ('core.ai_question_drafts', 'accepted_question_id'),
        ('core.interview_question_sources', 'question_id')
    ) AS target(table_name, column_name)
)
SELECT
    ns.nspname AS schema_name,
    tbl.relname AS table_name,
    dependency.conname AS constraint_name,
    pg_get_constraintdef(dependency.oid) AS definition,
    CASE WHEN EXISTS (
        SELECT 1 FROM supported_columns target
        JOIN pg_attribute attribute
            ON attribute.attrelid = target.table_oid AND attribute.attname = target.column_name
                AND NOT attribute.attisdropped
        WHERE dependency.conrelid = target.table_oid
        AND dependency.conkey = ARRAY[attribute.attnum]
        AND dependency.confkey = ARRAY[
            (SELECT attnum FROM pg_attribute
             WHERE attrelid = 'core.interview_questions'::regclass AND attname = 'id')
        ]
    ) THEN 'handled_by_current_migration'
      ELSE 'requires_migration_plan'
    END AS migration_status
FROM pg_constraint dependency
JOIN pg_class tbl ON tbl.oid = dependency.conrelid
JOIN pg_namespace ns ON ns.oid = tbl.relnamespace
WHERE dependency.contype = 'f'
  AND dependency.confrelid = 'core.interview_questions'::regclass
ORDER BY ns.nspname, tbl.relname, dependency.conname;
