#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CAPTURE="$ROOT/scripts/ci/run-and-capture.sh"
CLASSIFY="$ROOT/scripts/ci/classify-changes.sh"
REQUIRED_GATE="$ROOT/scripts/ci/verify-required-gate.sh"
SUMMARIZE="$ROOT/scripts/ci/summarize-test-results.py"
BUILD_WEIGHTS="$ROOT/scripts/ci/build-test-weights.py"
WORKFLOW="$ROOT/.github/workflows/ci.yml"
STORYBOOK_PREVIEW="$ROOT/frontend/.storybook/preview.tsx"

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

assert_status() {
  local expected="$1"
  shift

  set +e
  "$@"
  local actual=$?
  set -e
  assert_equal "$expected" "$actual" "command status: $*"
}

assert_contains() {
  local expected="$1"
  local file="$2"
  local description="$3"

  if ! grep -Fq -- "$expected" "$file"; then
    echo "FAIL: $description: '$expected' is missing from $file" >&2
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

mkdir -p "$test_root/test-results"
printf '%s\n' \
  '<testsuite name="example.SlowTest" tests="2" failures="1" errors="0" skipped="0" time="2.5"></testsuite>' \
  >"$test_root/test-results/TEST-example.SlowTest.xml"
printf '%s\n' \
  '<testsuite name="example.FastTest" tests="1" failures="0" errors="0" skipped="1" time="0.125"></testsuite>' \
  >"$test_root/test-results/TEST-example.FastTest.xml"
python3 "$SUMMARIZE" \
  --results-dir "$test_root/test-results" \
  --output "$test_root/timings.tsv" \
  --summary "$test_root/summary.md"
assert_equal \
  $'example.SlowTest\t2\t1\t0\t0\t2.500' \
  "$(sed -n '2p' "$test_root/timings.tsv")" \
  "timing summary sorts slow classes first and preserves failures"
assert_equal \
  $'example.FastTest\t1\t0\t0\t1\t0.125' \
  "$(sed -n '3p' "$test_root/timings.tsv")" \
  "timing summary preserves skipped tests"
grep -Fq 'Classes: `2`; tests: `3`; failures: `1`' "$test_root/summary.md" || {
  echo "FAIL: timing Markdown totals are missing" >&2
  exit 1
}

mkdir -p "$test_root/partial-results"
cp "$test_root/test-results/TEST-example.SlowTest.xml" "$test_root/partial-results/"
assert_status 0 python3 "$SUMMARIZE" \
  --results-dir "$test_root/partial-results" \
  --output "$test_root/partial-timings.tsv" \
  --summary "$test_root/partial-summary.md"
grep -Fq 'Classes: `1`; tests: `2`; failures: `1`' "$test_root/partial-summary.md" || {
  echo "FAIL: partial timing evidence was not summarized" >&2
  exit 1
}

mkdir -p "$test_root/empty-results"
assert_status 1 python3 "$SUMMARIZE" \
  --results-dir "$test_root/empty-results" \
  --output "$test_root/empty.tsv"

mkdir -p "$test_root/malformed-results"
printf '%s\n' '<testsuite' >"$test_root/malformed-results/TEST-broken.xml"
assert_status 1 python3 "$SUMMARIZE" \
  --results-dir "$test_root/malformed-results" \
  --output "$test_root/malformed.tsv"

mkdir -p "$test_root/non-finite-results"
printf '%s\n' '<testsuite name="example.InvalidTest" tests="1" time="nan"></testsuite>' \
  >"$test_root/non-finite-results/TEST-example.InvalidTest.xml"
assert_status 1 python3 "$SUMMARIZE" \
  --results-dir "$test_root/non-finite-results" \
  --output "$test_root/non-finite.tsv"

for run in 1 2 3; do
  mkdir -p "$test_root/weights/run-$run/shard-0/.ci-artifacts"
  {
    printf 'class_name\ttests\tfailures\terrors\tskipped\tseconds\n'
    printf 'example.AlphaTest\t1\t0\t0\t0\t%s\n' "$run"
    printf 'example.BetaTest\t1\t0\t0\t0\t%s\n' "$((run * 2))"
  } >"$test_root/weights/run-$run/shard-0/.ci-artifacts/gradle-test-timings.tsv"
done
python3 "$BUILD_WEIGHTS" \
  --run-dir "$test_root/weights/run-1" \
  --run-dir "$test_root/weights/run-2" \
  --run-dir "$test_root/weights/run-3" \
  --output "$test_root/weights/median.tsv"
assert_equal \
  $'example.AlphaTest\t2.000' \
  "$(sed -n '2p' "$test_root/weights/median.tsv")" \
  "weight builder records the class median"
assert_equal \
  $'example.BetaTest\t4.000' \
  "$(sed -n '3p' "$test_root/weights/median.tsv")" \
  "weight builder sorts class names deterministically"

sed -i.bak '/example.BetaTest/d' "$test_root/weights/run-3/shard-0/.ci-artifacts/gradle-test-timings.tsv"
assert_status 1 python3 "$BUILD_WEIGHTS" \
  --run-dir "$test_root/weights/run-1" \
  --run-dir "$test_root/weights/run-2" \
  --run-dir "$test_root/weights/run-3" \
  --output "$test_root/weights/partial.tsv"

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
assert_equal "backend" "$(cd "$repo" && "$CLASSIFY" "$docs_head" "$code_head")" "Kotlin changes use backend scope"

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
assert_equal "backend" "$(cd "$repo" && "$CLASSIFY" "$rename_head" "$migration_head")" "SQL migration changes use backend scope"

printf "plugins {}\n" >"$repo/build.gradle.kts"
git -C "$repo" add build.gradle.kts
git -C "$repo" commit -q -m gradle
gradle_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "backend" "$(cd "$repo" && "$CLASSIFY" "$migration_head" "$gradle_head")" "Gradle changes use backend scope"

mkdir -p "$repo/frontend/src"
printf "export const app = true;\n" >"$repo/frontend/src/app.ts"
git -C "$repo" add frontend/src/app.ts
git -C "$repo" commit -q -m frontend
frontend_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "frontend" "$(cd "$repo" && "$CLASSIFY" "$gradle_head" "$frontend_head")" "frontend changes use frontend scope"

printf "frontend docs\n" >"$repo/docs/frontend.md"
git -C "$repo" add docs/frontend.md
git -C "$repo" commit -q -m frontend-docs
frontend_docs_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "frontend" "$(cd "$repo" && "$CLASSIFY" "$gradle_head" "$frontend_docs_head")" "docs plus frontend changes use frontend scope"

printf "class BackendWithDocs\n" >"$repo/src/main/kotlin/BackendWithDocs.kt"
printf "backend docs\n" >"$repo/docs/backend.md"
git -C "$repo" add src/main/kotlin/BackendWithDocs.kt docs/backend.md
git -C "$repo" commit -q -m backend-docs
backend_docs_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "backend" "$(cd "$repo" && "$CLASSIFY" "$frontend_docs_head" "$backend_docs_head")" "docs plus backend changes use backend scope"

printf "class MoreBackend\n" >"$repo/src/main/kotlin/MoreBackend.kt"
printf "export const more = true;\n" >"$repo/frontend/src/more.ts"
git -C "$repo" add src/main/kotlin/MoreBackend.kt frontend/src/more.ts
git -C "$repo" commit -q -m mixed
mixed_head="$(git -C "$repo" rev-parse HEAD)"
assert_equal "full" "$(cd "$repo" && "$CLASSIFY" "$backend_docs_head" "$mixed_head")" "mixed frontend and backend changes use full scope"

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

scopes=(docs frontend backend full)
expected_rows=(
  "success skipped skipped skipped"
  "success success skipped skipped"
  "success skipped success success"
  "success success success success"
)
statuses=(success failure cancelled skipped)

for index in "${!scopes[@]}"; do
  scope="${scopes[$index]}"
  read -r -a expected <<<"${expected_rows[$index]}"
  assert_status 0 "$REQUIRED_GATE" "$scope" "${expected[@]}"

  for position in 0 1 2 3; do
    for status in "${statuses[@]}"; do
      [[ "$status" == "${expected[$position]}" ]] && continue
      actual=("${expected[@]}")
      actual[$position]="$status"
      assert_status 1 "$REQUIRED_GATE" "$scope" "${actual[@]}"
    done
  done
done

assert_status 1 "$REQUIRED_GATE" unknown success success success success
assert_status 1 "$REQUIRED_GATE" full success success success

assert_contains \
  'VITE_STORYBOOK_A11Y_TEST' \
  "$STORYBOOK_PREVIEW" \
  "Storybook preview exposes the CI-only accessibility mode"
assert_contains \
  '? "off" : "error"' \
  "$STORYBOOK_PREVIEW" \
  "Storybook accessibility remains error by default"
assert_contains \
  'name: Run required Storybook interaction tests' \
  "$WORKFLOW" \
  "required Storybook interactions are explicit"
assert_contains \
  'VITE_STORYBOOK_A11Y_TEST: "off"' \
  "$WORKFLOW" \
  "required Storybook interactions disable only accessibility assertions"
assert_contains \
  'name: Run advisory Storybook accessibility tests' \
  "$WORKFLOW" \
  "Storybook accessibility still runs in CI"
assert_contains \
  'continue-on-error: true' \
  "$WORKFLOW" \
  "Storybook accessibility cannot block the required frontend gate"
assert_contains \
  'retention-days: 14' \
  "$WORKFLOW" \
  "Storybook accessibility evidence is retained for fourteen days"

echo "CI script tests passed."
