#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo "Usage: $0 <scope> <preflight> <frontend> <backend-build> <test>" >&2
  exit 1
fi

scope="$1"
preflight_result="$2"
frontend_result="$3"
backend_build_result="$4"
test_result="$5"

case "$scope" in
  docs)
    expected=(success skipped skipped skipped)
    ;;
  frontend)
    expected=(success success skipped skipped)
    ;;
  backend)
    expected=(success skipped success success)
    ;;
  full)
    expected=(success success success success)
    ;;
  *)
    echo "Unknown or missing CI scope '$scope'." >&2
    exit 1
    ;;
esac

labels=(preflight frontend backend-build test)
actual=("$preflight_result" "$frontend_result" "$backend_build_result" "$test_result")
failed=false

for index in "${!labels[@]}"; do
  if [[ "${actual[$index]}" != "${expected[$index]}" ]]; then
    echo "${labels[$index]}: expected ${expected[$index]}, got ${actual[$index]:-<empty>}" >&2
    failed=true
  fi
done

if [[ "$failed" == true ]]; then
  exit 1
fi

printf 'Required CI gate passed for scope %s: preflight=%s frontend=%s backend-build=%s test=%s\n' \
  "$scope" "${actual[0]}" "${actual[1]}" "${actual[2]}" "${actual[3]}"
