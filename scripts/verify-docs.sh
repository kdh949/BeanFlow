#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

required=(
  "AGENTS.md"
  ".agent/PLANS.md"
  "docs/product/business-policy-decisions.md"
  "docs/architecture/failure-semantics.md"
  "docs/decisions/README.md"
  "docs/testing/definition-of-done.md"
  "docs/exec-plans/active/foundation-domain-model.md"
  "openapi/beanflow-v1.yaml"
)

for file in "${required[@]}"; do
  test -f "$file" || { echo "Missing required file: $file" >&2; exit 1; }
done

python3 - <<'PY'
from pathlib import Path
import re
import sys

root = Path('.')
policy = (root / 'docs/product/business-policy-decisions.md').read_text(encoding='utf-8')
ids = re.findall(r'^## (BR-\d{2}) ', policy, flags=re.MULTILINE)
expected = [f'BR-{i:02d}' for i in range(1, 33)]

if sorted(ids) != expected:
    print('Business policy IDs are missing, duplicated, or out of range.', file=sys.stderr)
    print('Found:', ids, file=sys.stderr)
    sys.exit(1)

sections = re.split(r'^## BR-\d{2} ', policy, flags=re.MULTILINE)[1:]
for index, section in enumerate(sections, start=1):
    if 'Revisit Conditions' not in section:
        print(f'BR-{index:02d} has no Revisit Conditions.', file=sys.stderr)
        sys.exit(1)

adr_files = sorted((root / 'docs/adr').glob('ADR-*.md'))
numbers = []
for p in adr_files:
    m = re.match(r'ADR-(\d{3})-', p.name)
    if not m:
        print(f'Invalid ADR filename: {p}', file=sys.stderr)
        sys.exit(1)
    numbers.append(m.group(1))
if len(numbers) != len(set(numbers)):
    print('Duplicate ADR number.', file=sys.stderr)
    sys.exit(1)

try:
    import yaml
except ImportError:
    print('PyYAML not installed: OpenAPI YAML parse not run.')
else:
    with (root / 'openapi/beanflow-v1.yaml').open(encoding='utf-8') as f:
        yaml.safe_load(f)
    print('OpenAPI YAML parsed successfully.')

print(f'Validated {len(ids)} business policies and {len(adr_files)} ADRs.')
PY

echo "Document verification completed."
