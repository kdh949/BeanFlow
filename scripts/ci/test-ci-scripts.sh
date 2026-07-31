#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CAPTURE="$ROOT/scripts/ci/run-and-capture.sh"
CLASSIFY="$ROOT/scripts/ci/classify-changes.sh"

test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT

assert_equal() {
  local expected="$1"
  local actual="$2"
  local description="$3"

  if [[ "$actual" != "$expected" ]]; then
    echo "FAIL: $description: expected '$expected', got '$actual'" >&2
    exit 1
  fi
}

capture_log="$test_root/capture/success.log"
"$CAPTURE" "$capture_log" bash -c 'printf "captured-success\\n"'
assert_equal "captured-success" "$(tr -d '\r\n' <"$capture_log")" "capture helper writes successful output"

set +e
"$CAPTURE" "$test_root/capture/failure.log" bash -c 'printf "captured-failure\\n"; exit 23'
capture_status=$?
set -e
assert_equal "23" "$capture_status" "capture helper preserves command failure"
assert_equal "captured-failure" "$(tr -d '\r\n' <"$test_root/capture/failure.log")" "capture helper writes failed output"

repo="$test_root/repo"
mkdir -p "$repo"
git -C "$repo" init -q
git -C "$repo" config user.name "CI Script Test"
git -C "$repo" config user.email "ci-script-test@example.invalid"
printf "baseline\n" >"$repo/README.md"
git -C "$repo" add README.md
git -C "$repo" commit -q -m baseline
baseline="$(git -C "$repo" rev-parse HEAD)"

printf "documentation update\n" >>"$repo/README.md"
git -C "$repo" add README.md
git -C "$repo" commit -q -m markdown
markdown_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "docs" "$(cd "$repo" && "$CLASSIFY" "$baseline" "$markdown_head")" "root Markdown changes use docs scope"

mkdir -p "$repo/openapi"
printf "openapi: 3.1.0\n" >"$repo/openapi/api.yaml"
git -C "$repo" add openapi
git -C "$repo" commit -q -m openapi
openapi_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "docs" "$(cd "$repo" && "$CLASSIFY" "$markdown_head" "$openapi_head")" "OpenAPI-only changes use docs scope"

mkdir -p "$repo/docs"
printf "docs\n" >"$repo/docs/guide.md"
git -C "$repo" add docs
git -C "$repo" commit -q -m docs
docs_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "docs" "$(cd "$repo" && "$CLASSIFY" "$baseline" "$docs_head")" "mixed documentation changes use docs scope"

mkdir -p "$repo/src/main/kotlin"
printf "class App\n" >"$repo/src/main/kotlin/App.kt"
git -C "$repo" add src
git -C "$repo" commit -q -m code
code_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "full" "$(cd "$repo" && "$CLASSIFY" "$docs_head" "$code_head")" "Kotlin changes use full scope"

mkdir -p "$repo/docs/archive"
cp "$repo/src/main/kotlin/App.kt" "$repo/docs/archive/AppCopy.md"
git -C "$repo" add docs/archive/AppCopy.md
git -C "$repo" commit -q -m copy
copy_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "full" "$(cd "$repo" && "$CLASSIFY" "$code_head" "$copy_head")" "code-to-docs copy uses full scope"

git -C "$repo" mv src/main/kotlin/App.kt docs/archive/App.md
git -C "$repo" commit -q -m rename
rename_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "full" "$(cd "$repo" && "$CLASSIFY" "$copy_head" "$rename_head")" "code-to-docs rename uses full scope"

mkdir -p "$repo/src/main/resources/db/migration"
printf "SELECT 1;\n" >"$repo/src/main/resources/db/migration/V1__test.sql"
git -C "$repo" add src/main/resources/db/migration
git -C "$repo" commit -q -m migration
migration_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "full" "$(cd "$repo" && "$CLASSIFY" "$rename_head" "$migration_head")" "SQL migration changes use full scope"

printf "plugins {}\n" >"$repo/build.gradle.kts"
git -C "$repo" add build.gradle.kts
git -C "$repo" commit -q -m gradle
gradle_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "full" "$(cd "$repo" && "$CLASSIFY" "$migration_head" "$gradle_head")" "Gradle changes use full scope"

mkdir -p "$repo/.github/workflows"
printf "name: test\n" >"$repo/.github/workflows/ci.yml"
git -C "$repo" add .github/workflows/ci.yml
git -C "$repo" commit -q -m workflow
workflow_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "full" "$(cd "$repo" && "$CLASSIFY" "$gradle_head" "$workflow_head")" "workflow changes use full scope"

mkdir -p "$repo/scripts/ci"
printf "#!/usr/bin/env bash\n" >"$repo/scripts/ci/check.sh"
git -C "$repo" add scripts/ci/check.sh
git -C "$repo" commit -q -m ci-script
ci_script_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "full" "$(cd "$repo" && "$CLASSIFY" "$workflow_head" "$ci_script_head")" "CI script changes use full scope"

printf "metadata\n" >"$repo/.unknown-ci-metadata"
git -C "$repo" add .unknown-ci-metadata
git -C "$repo" commit -q -m unknown
unknown_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "full" "$(cd "$repo" && "$CLASSIFY" "$ci_script_head" "$unknown_head")" "unknown paths use full scope"
assert_equal "full" "$(cd "$repo" && "$CLASSIFY" "$unknown_head" "$unknown_head")" "empty ranges use full scope"
assert_equal "full" "$(cd "$repo" && "$CLASSIFY" missing "$unknown_head")" "missing revisions use full scope"

echo "CI script tests passed."
