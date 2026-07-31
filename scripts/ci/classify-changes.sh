#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "full"
  echo "Change classification requires <base-sha> and <head-sha>; using full scope." >&2
  exit 0
fi

base_sha="$1"
head_sha="$2"

if ! git cat-file -e "${base_sha}^{commit}" 2>/dev/null ||
  ! git cat-file -e "${head_sha}^{commit}" 2>/dev/null; then
  echo "full"
  echo "A compare revision is unavailable; using full scope." >&2
  exit 0
fi

changes_file="$(mktemp)"
trap 'rm -f "$changes_file"' EXIT

if ! git diff --name-status -z --find-renames --find-copies-harder "$base_sha" "$head_sha" >"$changes_file"; then
  echo "full"
  echo "Unable to read the compare range; using full scope." >&2
  exit 0
fi

is_docs_path() {
  local path="$1"

  case "$path" in
    docs/* | openapi/* | .agent/* | .github/pull_request_template.md)
      return 0
      ;;
  esac

  [[ "$path" != */* && "$path" == *.md ]]
}

scope="docs"
path_count=0

exec 3<"$changes_file"
while IFS= read -r -d '' status <&3; do
  paths=()
  case "$status" in
    R* | C*)
      IFS= read -r -d '' source_path <&3 || {
        echo "full"
        echo "Malformed rename/copy record; using full scope." >&2
        exit 0
      }
      IFS= read -r -d '' destination_path <&3 || {
        echo "full"
        echo "Malformed rename/copy record; using full scope." >&2
        exit 0
      }
      paths=("$source_path" "$destination_path")
      ;;
    A | D | M | T | U | X | B)
      IFS= read -r -d '' path <&3 || {
        echo "full"
        echo "Malformed change record; using full scope." >&2
        exit 0
      }
      paths=("$path")
      ;;
    *)
      echo "full"
      echo "Unknown git change status '$status'; using full scope." >&2
      exit 0
      ;;
  esac

  for path in "${paths[@]}"; do
    path_count=$((path_count + 1))
    if ! is_docs_path "$path"; then
      scope="full"
    fi
  done
done

if [[ "$path_count" -eq 0 ]]; then
  echo "full"
  echo "The compare range contains no changed paths; using full scope." >&2
  exit 0
fi

echo "$scope"
