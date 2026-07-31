#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <log-path> <command> [args...]" >&2
  exit 64
fi

log_path="$1"
shift

mkdir -p "$(dirname "$log_path")"

set +e
"$@" 2>&1 | tee "$log_path"
pipeline_status=("${PIPESTATUS[@]}")
set -e

command_status="${pipeline_status[0]}"
tee_status="${pipeline_status[1]}"

if [[ "$command_status" -ne 0 ]]; then
  exit "$command_status"
fi

exit "$tee_status"
