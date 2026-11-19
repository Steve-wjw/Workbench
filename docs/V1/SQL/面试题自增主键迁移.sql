-- 面试题主键：UUID -> 从 1 开始的 BIGINT 自增主键。
-- 先备份数据库，停止 Java API 等业务写入，再在同一连接完整执行本文件。
-- 同步转换标签、旧版草稿和题目来源中的面试题引用；模块、标签、资料的主键不变。
-- 旧版表不存在时自动跳过，存在时保留其数据、索引及原有外键删除行为。
-- 旧题按 created_at、旧 ID 顺序编号，不能据此还原原始文档顺序。
-- 不保留旧 UUID 列；成功后旧的面试题详情地址不再有效。
-- 事务中任一步失败将回滚。已完成本迁移时，重复执行不重排 ID。

-- Execution guide: docs/SQL/数据库脚本使用说明.md.
BEGIN;

LOCK TABLE core.interview_questions, core.interview_question_tags
    IN ACCESS EXCLUSIVE MODE;

DO $question_identity$
DECLARE
    question_id_type TEXT;
    question_identity TEXT;
    reference_column RECORD;
    reference_type OID;
    reference_constraint RECORD;
    migrated_count BIGINT;
    unsupported_references TEXT;
BEGIN
    SELECT data_type, is_identity
    INTO STRICT question_id_type, question_identity
    FROM information_schema.columns
    WHERE table_schema = 'core' AND table_name = 'interview_questions' AND column_name = 'id';

    CREATE TEMP TABLE question_id_columns (
        table_oid OID NOT NULL,
        column_name NAME NOT NULL,
        PRIMARY KEY (table_oid, column_name)
    ) ON COMMIT DROP;

    INSERT INTO question_id_columns (table_oid, column_name)
    SELECT to_regclass(target.table_name), target.column_name
    FROM (VALUES
        ('core.interview_question_tags', 'question_id'),
        ('core.ai_question_drafts', 'accepted_question_id'),
        ('core.interview_question_sources', 'question_id')
    ) AS target(table_name, column_name)
    WHERE to_regclass(target.table_name) IS NOT NULL;

    FOR reference_column IN SELECT * FROM question_id_columns ORDER BY table_oid, column_name
    LOOP
        EXECUTE format('LOCK TABLE %s IN ACCESS EXCLUSIVE MODE', reference_column.table_oid::regclass);
        SELECT atttypid INTO reference_type FROM pg_attribute
        WHERE attrelid = reference_column.table_oid AND attname = reference_column.column_name
          AND NOT attisdropped;
        IF reference_type IS NULL
            OR (question_id_type = 'uuid' AND reference_type <> 'uuid'::regtype)
            OR (question_id_type = 'bigint' AND reference_type <> 'bigint'::regtype) THEN
            RAISE EXCEPTION 'Unexpected type for %.%: %. Migration cancelled.',
                reference_column.table_oid::regclass, reference_column.column_name, reference_type::regtype;
        END IF;
    END LOOP;

    IF question_id_type = 'bigint' AND question_identity = 'YES' THEN
        RAISE NOTICE 'Question IDs already use BIGINT identity; no changes.';
        RETURN;
    END IF;

    IF question_id_type <> 'uuid' THEN
        RAISE EXCEPTION 'Unexpected question ID type: %. Migration cancelled.', question_id_type;
    END IF;

    -- 只处理已知的三个引用列，不静默删除其他外键。
    SELECT string_agg(
        format('%I.%I [%I]: %s', ns.nspname, tbl.relname, dependency.conname,
            pg_get_constraintdef(dependency.oid)),
        E'\n' ORDER BY ns.nspname, tbl.relname, dependency.conname
    ) INTO unsupported_references
    FROM pg_constraint dependency
    JOIN pg_class tbl ON tbl.oid = dependency.conrelid
    JOIN pg_namespace ns ON ns.oid = tbl.relnamespace
    WHERE dependency.contype = 'f'
          AND dependency.confrelid = 'core.interview_questions'::regclass
          AND NOT EXISTS (
              SELECT 1 FROM question_id_columns target
              JOIN pg_attribute attribute
                ON attribute.attrelid = target.table_oid AND attribute.attname = target.column_name
                  AND NOT attribute.attisdropped
              WHERE dependency.conrelid = target.table_oid
              AND dependency.conkey = ARRAY[attribute.attnum]
              AND dependency.confkey = ARRAY[
                  (SELECT attnum FROM pg_attribute
                   WHERE attrelid = 'core.interview_questions'::regclass AND attname = 'id')
              ]
          );
    IF unsupported_references IS NOT NULL THEN
        -- 放进主错误消息，不依赖可能被客户端隐藏的 SQL 或 DETAIL 字段。
        RAISE EXCEPTION E'Additional tables reference interview_questions. Migration cancelled; handle their references first.\n%',
            unsupported_references;
    END IF;

    CREATE TEMP TABLE question_id_mapping (
        old_id UUID PRIMARY KEY,
        new_id BIGINT NOT NULL UNIQUE
    ) ON COMMIT DROP;

    INSERT INTO question_id_mapping (old_id, new_id)
    SELECT id, row_number() OVER (ORDER BY created_at, id)
    FROM core.interview_questions;
    GET DIAGNOSTICS migrated_count = ROW_COUNT;

    CREATE TEMP TABLE question_id_foreign_keys ON COMMIT DROP AS
    SELECT conrelid AS table_oid, conname AS constraint_name,
        pg_get_constraintdef(oid) AS definition, obj_description(oid, 'pg_constraint') AS description
    FROM pg_constraint
    WHERE contype = 'f' AND confrelid = 'core.interview_questions'::regclass;

    -- ALTER COLUMN TYPE 原位转换，保留主键、唯一约束、索引和列的可空属性。
    -- STRICT 保留 NULL；非空旧 ID 缺少映射时主动报错，不能静默写成 NULL。
    CREATE FUNCTION pg_temp.workbench_question_id(old_uuid UUID)
    RETURNS BIGINT LANGUAGE plpgsql STABLE STRICT AS $map_question_id$
    DECLARE
        mapped_id BIGINT;
    BEGIN
        SELECT new_id INTO STRICT mapped_id
        FROM pg_temp.question_id_mapping WHERE old_id = old_uuid;
        RETURN mapped_id;
    EXCEPTION WHEN NO_DATA_FOUND THEN
        RAISE EXCEPTION 'Unmapped question reference: %. Migration cancelled.', old_uuid;
    END;
    $map_question_id$;

    FOR reference_constraint IN SELECT * FROM question_id_foreign_keys
    LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I',
            reference_constraint.table_oid::regclass, reference_constraint.constraint_name);
    END LOOP;

    ALTER TABLE core.interview_questions ALTER COLUMN id DROP DEFAULT;
    ALTER TABLE core.interview_questions ALTER COLUMN id TYPE BIGINT
        USING pg_temp.workbench_question_id(id);
    ALTER TABLE core.interview_questions ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY;

    FOR reference_column IN SELECT * FROM question_id_columns ORDER BY table_oid, column_name
    LOOP
        EXECUTE format('ALTER TABLE %s ALTER COLUMN %I TYPE BIGINT USING pg_temp.workbench_question_id(%I)',
            reference_column.table_oid::regclass, reference_column.column_name, reference_column.column_name);
    END LOOP;

    FOR reference_constraint IN SELECT * FROM question_id_foreign_keys
    LOOP
        EXECUTE format('ALTER TABLE %s ADD CONSTRAINT %I %s',
            reference_constraint.table_oid::regclass, reference_constraint.constraint_name,
            reference_constraint.definition);
        IF reference_constraint.description IS NOT NULL THEN
            EXECUTE format('COMMENT ON CONSTRAINT %I ON %s IS %L',
                reference_constraint.constraint_name, reference_constraint.table_oid::regclass,
                reference_constraint.description);
        END IF;
    END LOOP;

    DROP FUNCTION pg_temp.workbench_question_id(UUID);

    -- 最后一条旧题后继续自增；空表的第一次 INSERT 生成 1。
    PERFORM setval(
        pg_get_serial_sequence('core.interview_questions', 'id')::regclass,
        (SELECT COALESCE(max(id), 0) + 1 FROM core.interview_questions),
        false
    );

    RAISE NOTICE 'Migrated % questions; answers, follow-ups, tags, legacy drafts and sources preserved.', migrated_count;
END;
$question_identity$;

COMMIT;
