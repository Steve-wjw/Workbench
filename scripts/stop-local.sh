#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="$ROOT_DIR/data/.pids"

if [[ ! -d "$PID_DIR" ]]; then
  echo "No local service PID directory found."
  exit 0
fi

stop_process_tree() {
  for child_pid in $(pgrep -P "$1" 2>/dev/null || true); do
    stop_process_tree "$child_pid"
  done
  kill "$1" 2>/dev/null || true
}

for pid_file in "$PID_DIR"/*.pid; do
  [[ -e "$pid_file" ]] || continue
  name="$(basename "$pid_file" .pid)"
  pid="$(cat "$pid_file")"
  if kill -0 "$pid" 2>/dev/null; then
    stop_process_tree "$pid"
    echo "stopped $name (pid $pid)"
  else
    echo "$name was not running."
  fi
  rm -f "$pid_file"
done
