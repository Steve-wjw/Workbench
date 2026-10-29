#!/usr/bin/env bash
#
# 清空本地 AI Workbench 的全部业务数据。
# 保留数据库、schema、表结构、索引、约束和 PostgreSQL 扩展。
#
# 使用方式：
#   bash scripts/clear-local-database-data.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  source "$ROOT_DIR/.env"
  set +a
fi

if [[ -z "${DATABASE_URL:-}" ]]; then
  : "${POSTGRES_USER:?POSTGRES_USER must be set in .env}"
  : "${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set in .env}"
  : "${POSTGRES_DB:?POSTGRES_DB must be set in .env}"
  : "${POSTGRES_PORT:=5432}"
  DATABASE_URL="postgresql://${POSTGRES_USER}:${POSTGRES_PASSWORD}@localhost:${POSTGRES_PORT}/${POSTGRES_DB}"
fi

command -v psql >/dev/null 2>&1 || {
  echo "psql is required but was not found in PATH." >&2
  exit 1
}

cat <<'WARNING'
This action permanently deletes all business data in the core and rag schemas.
Database schemas, tables, indexes, constraints and extensions will be preserved.
WARNING

read -r -p "Type CLEAR to continue: " confirmation
if [[ "$confirmation" != "CLEAR" ]]; then
  echo "Cancelled. No data was deleted."
  exit 0
fi

psql "$DATABASE_URL" \
  -v ON_ERROR_STOP=1 \
  -c "
    DO \$\$
    DECLARE
      table_names TEXT;
    BEGIN
      SELECT string_agg(format('%I.%I', schemaname, tablename), ', ' ORDER BY schemaname, tablename)
      INTO table_names
      FROM pg_tables
      WHERE schemaname IN ('core', 'rag');

      IF table_names IS NOT NULL THEN
        EXECUTE 'TRUNCATE TABLE ' || table_names || ' RESTART IDENTITY CASCADE';
      END IF;
    END
    \$\$;
  "

psql "$DATABASE_URL" \
  -v ON_ERROR_STOP=1 \
  -P pager=off \
  -c "
    SELECT schemaname AS schema_name,
           relname AS table_name,
           n_live_tup AS estimated_row_count
    FROM pg_stat_user_tables
    WHERE schemaname IN ('core', 'rag')
    ORDER BY schemaname, relname;
  "

echo "All local business data has been cleared."
