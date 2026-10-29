#!/usr/bin/env bash
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

psql "$DATABASE_URL" \
  -v ON_ERROR_STOP=1 \
  -f "$ROOT_DIR/infra/postgres/init/001-create-schemas.sql"

psql "$DATABASE_URL" \
  -v ON_ERROR_STOP=1 \
  --single-transaction \
  -f "$ROOT_DIR/infra/postgres/init/002-create-business-tables.sql"

echo "Local PostgreSQL business tables and document indexing initialized."
