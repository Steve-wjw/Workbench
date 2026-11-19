#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="$ROOT_DIR/data/.pids"
LOG_DIR="$ROOT_DIR/data/logs"

if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  source "$ROOT_DIR/.env"
  set +a
fi

mkdir -p "$PID_DIR" "$LOG_DIR"

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "$1 is required but was not found in PATH." >&2
    exit 1
  }
}

require_command mvn
require_command npm

PYTHON_BIN="$ROOT_DIR/backend-python/.venv/bin/python"
if [[ ! -x "$PYTHON_BIN" ]]; then
  echo "Python virtual environment not found. See docs/V1/本地部署与配置说明.md for setup." >&2
  exit 1
fi

PYTHON_VERSION="$("$PYTHON_BIN" -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')"
if [[ "$PYTHON_VERSION" != "3.14" ]]; then
  echo "backend-python/.venv must use Python 3.14, found Python $PYTHON_VERSION." >&2
  exit 1
fi

start_process() {
  local name="$1"
  local logfile="$2"
  shift 2

  if [[ -f "$PID_DIR/$name.pid" ]]; then
    local existing_pid
    existing_pid="$(cat "$PID_DIR/$name.pid")"
    if kill -0 "$existing_pid" 2>/dev/null; then
      echo "$name is already running (pid $existing_pid)."
      return
    fi
    rm -f "$PID_DIR/$name.pid"
  fi

  "$@" >"$LOG_DIR/$logfile" 2>&1 &
  echo $! >"$PID_DIR/$name.pid"
  echo "started $name (pid $(cat "$PID_DIR/$name.pid"))"
}

start_process "java-api" "java-api.log" \
  bash -c "cd '$ROOT_DIR/backend-java' && exec mvn spring-boot:run"

start_process "python-rag" "python-rag.log" \
  bash -c "cd '$ROOT_DIR/backend-python' && exec '$PYTHON_BIN' -m uvicorn app.main:app --reload --host 127.0.0.1 --port ${PYTHON_RAG_PORT:-8000}"

start_process "python-worker" "python-worker.log" \
  bash -c "cd '$ROOT_DIR/backend-python' && exec '$PYTHON_BIN' -m app.worker"

start_process "frontend" "frontend.log" \
  bash -c "cd '$ROOT_DIR/frontend' && exec npm run dev -- --host 127.0.0.1 --port ${FRONTEND_PORT:-5173}"

cat <<EOF

Local services started:
  Frontend:  http://localhost:${FRONTEND_PORT:-5173}
  Java API:  http://localhost:${JAVA_API_PORT:-8080}/api/v1/health
  Python:    http://localhost:${PYTHON_RAG_PORT:-8000}/internal/v1/health

Logs: $LOG_DIR
Stop: scripts/stop-local.sh
EOF
