#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

required=(
  "AGENTS.md"
  ".agent/PLANS.md"
  "README.md"
  "docs/index.md"
  "docs/product/product-overview.md"
  "docs/product/actors-and-goals.md"
  "docs/product/business-policy-decisions.md"
  "docs/product/end-to-end-flow.md"
  "docs/product/non-goals.md"
  "docs/architecture/architecture-overview.md"
  "docs/architecture/ubiquitous-language.md"
  "docs/architecture/context-map.md"
  "docs/architecture/policy-traceability.md"
  "docs/architecture/aggregate-invariants.md"
  "docs/architecture/transaction-boundaries.md"
  "docs/architecture/state-machines.md"
  "docs/architecture/event-catalog.md"
  "docs/architecture/failure-semantics.md"
  "docs/decisions/README.md"
  "docs/decisions/minor-decisions.md"
  "docs/adr/README.md"
  "docs/api/api-conventions.md"
  "docs/api/error-catalog.md"
  "docs/security/authorization-matrix.md"
  "docs/testing/test-strategy.md"
  "docs/testing/definition-of-done.md"
  "docs/performance/measurement-plan.md"
  "docs/quality/quality-evidence-map.md"
  "docs/review/code-review.md"
  "docs/exec-plans/completed/foundation-domain-model.md"
  "openapi/beanflow-v1.yaml"
)

for file in "${required[@]}"; do
  test -f "$file" || { echo "Missing required file: $file" >&2; exit 1; }
done

python3 - <<'PY'
from collections import Counter
from pathlib import Path
import re
import sys

root = Path('.')
policy = (root / 'docs/product/business-policy-decisions.md').read_text(encoding='utf-8')
ids = re.findall(r'^## (BR-\d{2}) ', policy, flags=re.MULTILINE)
expected = [f'BR-{i:02d}' for i in range(1, 33)]

if set(ids) != set(expected) or any(count != 1 for count in Counter(ids).values()):
    print('Business policy IDs are missing, duplicated, or out of range.', file=sys.stderr)
    print('Found:', ids, file=sys.stderr)
    sys.exit(1)

headings = list(re.finditer(r'^## (BR-\d{2}) ', policy, flags=re.MULTILINE))
for index, heading in enumerate(headings):
    end = headings[index + 1].start() if index + 1 < len(headings) else len(policy)
    section = policy[heading.start():end]
    if 'Revisit Conditions' not in section:
        print(f'{heading.group(1)} has no Revisit Conditions.', file=sys.stderr)
        sys.exit(1)

adr_files = sorted((root / 'docs/adr').glob('ADR-*.md'))
numbers = []
adr_status_by_name = {}
for p in adr_files:
    m = re.match(r'ADR-(\d{3})-', p.name)
    if not m:
        print(f'Invalid ADR filename: {p}', file=sys.stderr)
        sys.exit(1)
    numbers.append(m.group(1))
    body = p.read_text(encoding='utf-8')
    status = re.search(r'^- \*\*Status:\*\* (.+)$', body, flags=re.MULTILINE)
    if not status:
        print(f'ADR has no Status: {p}', file=sys.stderr)
        sys.exit(1)
    adr_status_by_name[p.name] = status.group(1).strip()
if len(numbers) != len(set(numbers)):
    print('Duplicate ADR number.', file=sys.stderr)
    sys.exit(1)

adr_index = (root / 'docs/adr/README.md').read_text(encoding='utf-8')
index_entries = re.findall(
    r'\[(ADR-\d{3})\]\((ADR-\d{3}-[^)]+\.md)\) \| ([A-Za-z]+) \|',
    adr_index,
)
indexed_names = [entry[1] for entry in index_entries]
if set(indexed_names) != set(adr_status_by_name):
    print('ADR index and ADR files differ.', file=sys.stderr)
    print('Indexed:', sorted(indexed_names), file=sys.stderr)
    print('Files:', sorted(adr_status_by_name), file=sys.stderr)
    sys.exit(1)
for _, name, status in index_entries:
    if adr_status_by_name[name] != status:
        print(
            f'ADR status mismatch for {name}: index={status}, file={adr_status_by_name[name]}',
            file=sys.stderr,
        )
        sys.exit(1)

markdown_files = sorted(root.rglob('*.md'))
link_pattern = re.compile(r'\[[^\]]+\]\(([^)]+)\)')
broken_links = []
for path in markdown_files:
    for target in link_pattern.findall(path.read_text(encoding='utf-8')):
        if target.startswith(('http://', 'https://', 'mailto:', '#')):
            continue
        local_target = target.split('#', 1)[0]
        if local_target and not (path.parent / local_target).resolve().exists():
            broken_links.append((path, target))
if broken_links:
    print('Broken Markdown links:', file=sys.stderr)
    for path, target in broken_links:
        print(f'  {path}: {target}', file=sys.stderr)
    sys.exit(1)

try:
    import yaml
except ImportError:
    print('OpenAPI YAML parse: Not configured (PyYAML is not installed).')
else:
    with (root / 'openapi/beanflow-v1.yaml').open(encoding='utf-8') as f:
        spec = yaml.safe_load(f)
    if spec.get('openapi') != '3.1.0':
        print('OpenAPI version must be 3.1.0.', file=sys.stderr)
        sys.exit(1)

    required_paths = {
        '/stores/nearby',
        '/stores/{storeId}/menus',
        '/stores/{storeId}/pickup-slots',
        '/orders',
        '/orders/{orderId}',
        '/orders/{orderId}/cancellations',
        '/orders/{orderId}/payment-confirmations',
        '/payments/{paymentId}/refunds',
        '/store-orders/{orderId}/status',
        '/point-accounts/{accountId}',
        '/point-accounts/{accountId}/transactions',
        '/stores/{storeId}/settlements',
        '/settlement-items/{itemId}/disputes',
    }
    actual_paths = set(spec.get('paths', {}))
    if not required_paths <= actual_paths:
        print('OpenAPI required paths are missing.', file=sys.stderr)
        print('Missing:', sorted(required_paths - actual_paths), file=sys.stderr)
        sys.exit(1)

    def resolve_local_ref(ref):
        if not ref.startswith('#/'):
            raise ValueError(f'Only local OpenAPI refs are allowed: {ref}')
        node = spec
        for part in ref[2:].split('/'):
            part = part.replace('~1', '/').replace('~0', '~')
            node = node[part]
        return node

    def walk(value):
        if isinstance(value, dict):
            if '$ref' in value:
                resolve_local_ref(value['$ref'])
            for child in value.values():
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    try:
        walk(spec)
    except (KeyError, TypeError, ValueError) as exc:
        print(f'Invalid OpenAPI reference: {exc}', file=sys.stderr)
        sys.exit(1)

    mutation_operations = [
        ('/orders', 'post'),
        ('/orders/{orderId}/cancellations', 'post'),
        ('/orders/{orderId}/payment-confirmations', 'post'),
        ('/payments/{paymentId}/refunds', 'post'),
        ('/store-orders/{orderId}/status', 'patch'),
        ('/settlement-items/{itemId}/disputes', 'post'),
    ]
    idempotency_ref = '#/components/parameters/IdempotencyKey'
    for path, method in mutation_operations:
        operation = spec['paths'][path][method]
        refs = {
            item.get('$ref')
            for item in operation.get('parameters', [])
            if isinstance(item, dict)
        }
        if idempotency_ref not in refs:
            print(f'{method.upper()} {path} has no Idempotency-Key.', file=sys.stderr)
            sys.exit(1)
        if 'responses' not in operation:
            print(f'{method.upper()} {path} has no responses.', file=sys.stderr)
            sys.exit(1)

    error_required = set(spec['components']['schemas']['Error'].get('required', []))
    if error_required != {'code', 'message', 'correlationId', 'details'}:
        print('OpenAPI Error envelope does not match API conventions.', file=sys.stderr)
        sys.exit(1)

    print(
        f'OpenAPI YAML and local contract checks passed '
        f'({len(actual_paths)} paths, '
        f'{len(spec["components"]["schemas"])} schemas).'
    )

print(
    f'Validated {len(ids)} business policies, {len(adr_files)} ADRs, '
    f'and {len(markdown_files)} Markdown files.'
)
PY

echo "Document verification completed."
