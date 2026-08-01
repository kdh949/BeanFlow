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
  "docs/adr/ADR-071-settlement-input-snapshot-foundation.md"
  "docs/adr/ADR-072-execplan-unattended-execution-and-migration-lane.md"
  "docs/api/api-conventions.md"
  "docs/api/error-catalog.md"
  "docs/security/authorization-matrix.md"
  "docs/testing/test-strategy.md"
  "docs/testing/definition-of-done.md"
  "docs/performance/measurement-plan.md"
  "docs/quality/quality-evidence-map.md"
  "docs/quality/customer-order-cancellation-readiness.md"
  "docs/quality/customer-order-cancellation-release-evidence.md"
  "docs/decisions/customer-order-cancellation-decision-closure.md"
  "docs/exec-plans/active/customer-order-cancellation-and-recovery.md"
  "docs/exec-plans/completed/customer-order-cancellation-00-contract-baseline.md"
  "docs/exec-plans/completed/customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md"
  "docs/exec-plans/completed/customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md"
  "docs/exec-plans/completed/customer-order-cancellation-12-partial-refund-allocation-and-restoration.md"
  "docs/exec-plans/active/customer-order-cancellation-13-refund-earned-point-recovery-foundation.md"
  "docs/exec-plans/active/customer-order-cancellation-14-point-account-read-vertical-slice.md"
  "docs/exec-plans/active/customer-order-cancellation-15-settlement-input-snapshot-foundation.md"
  "docs/exec-plans/active/customer-order-cancellation-16-immutable-refund-and-loyalty-event-producer.md"
  "docs/exec-plans/active/customer-order-cancellation-20-settlement-foundation.md"
  "docs/exec-plans/active/customer-order-cancellation-30-order-compensation-foundation.md"
  "docs/exec-plans/active/customer-order-cancellation-40-command.md"
  "docs/exec-plans/active/customer-order-cancellation-50-recovery.md"
  "docs/exec-plans/active/loyalty-point-adjustment-foundation.md"
  "docs/exec-plans/completed/signed-cursor-foundation.md"
  "docs/exec-plans/completed/ci-pr-validation.md"
  "docs/review/code-review.md"
  "docs/exec-plans/completed/foundation-domain-model.md"
  "openapi/beanflow-v1.yaml"
  "openapi/beanflow-v1-deployed.yaml"
  "scripts/ci/classify-changes.sh"
  "scripts/ci/run-and-capture.sh"
  "scripts/ci/requirements-docs.txt"
  "scripts/ci/test-ci-scripts.sh"
)

for file in "${required[@]}"; do
  test -f "$file" || { echo "Missing required file: $file" >&2; exit 1; }
done

python3 - <<'PY'
from collections import Counter
from datetime import date
from pathlib import Path
import re
import sys

root = Path('.')
policy = (root / 'docs/product/business-policy-decisions.md').read_text(encoding='utf-8')
api_conventions = (root / 'docs/api/api-conventions.md').read_text(encoding='utf-8')
normalized_api_conventions = re.sub(r'\s+', ' ', api_conventions)
ids = re.findall(r'^## (BR-\d{2}) ', policy, flags=re.MULTILINE)
expected = [f'BR-{i:02d}' for i in range(1, 33)]

if set(ids) != set(expected) or any(count != 1 for count in Counter(ids).values()):
    print('Business policy IDs are missing, duplicated, or out of range.', file=sys.stderr)
    print('Found:', ids, file=sys.stderr)
    sys.exit(1)

plan_files = sorted((root / 'docs/exec-plans').glob('*/*.md'))
plan_metadata_pattern = re.compile(
    r'# [^\n]+\n\n'
    r'> \*\*Status:\*\* `(ACTIVE|COMPLETED)`\n'
    r'> \*\*Kind:\*\* `(IMPLEMENTATION|ORCHESTRATION)`\n'
    r'> \*\*Implementation-Ready:\*\* `(true|false)`\n'
    r'> \*\*Writes-Migration:\*\* `(true|false)`\n'
    r'> \*\*Depends-On:\*\* (.+)\n'
    r'> \*\*Completed-At:\*\* `([^`]+)`\n'
)
plan_metadata = {}
metadata_errors = []
for plan_file in plan_files:
    relative_path = plan_file.as_posix()
    match = plan_metadata_pattern.match(plan_file.read_text(encoding='utf-8'))
    if match is None:
        metadata_errors.append(f'{relative_path}: title 바로 아래 canonical metadata가 없습니다.')
        continue
    status, kind, implementation_ready, writes_migration, depends_on_field, completed_at = match.groups()
    directory = plan_file.parent.name
    expected_status = {'active': 'ACTIVE', 'completed': 'COMPLETED'}.get(directory)
    if expected_status is None or status != expected_status:
        metadata_errors.append(
            f'{relative_path}: directory {directory!r}와 Status {status!r}가 일치하지 않습니다.'
        )
    if status == 'ACTIVE':
        if completed_at != '—':
            metadata_errors.append(f'{relative_path}: ACTIVE plan의 Completed-At은 —여야 합니다.')
    else:
        try:
            date.fromisoformat(completed_at)
        except ValueError:
            metadata_errors.append(
                f'{relative_path}: COMPLETED plan의 Completed-At은 ISO-8601 date여야 합니다.'
            )
    if kind == 'ORCHESTRATION' and (
        implementation_ready != 'false' or writes_migration != 'false'
    ):
        metadata_errors.append(
            f'{relative_path}: ORCHESTRATION plan은 Implementation-Ready/Writes-Migration 모두 false여야 합니다.'
        )
    if depends_on_field == '—':
        dependencies = []
    else:
        dependencies = re.findall(r'`([^`]+)`', depends_on_field)
        canonical_field = ', '.join(f'`{dependency}`' for dependency in dependencies)
        if not dependencies or depends_on_field != canonical_field:
            metadata_errors.append(
                f'{relative_path}: Depends-On은 comma-separated repository-relative backtick path 또는 —여야 합니다.'
            )
    plan_metadata[relative_path] = {
        'status': status,
        'kind': kind,
        'implementation_ready': implementation_ready == 'true',
        'writes_migration': writes_migration == 'true',
        'dependencies': dependencies,
    }

if metadata_errors:
    print('Invalid ExecPlan canonical metadata:', file=sys.stderr)
    for error in metadata_errors:
        print(f'  {error}', file=sys.stderr)
    sys.exit(1)

for relative_path, metadata in plan_metadata.items():
    for dependency in metadata['dependencies']:
        if dependency not in plan_metadata:
            print(
                f'ExecPlan dependency does not name an ExecPlan: {relative_path} -> {dependency}',
                file=sys.stderr,
            )
            sys.exit(1)
        if dependency == relative_path:
            print(f'ExecPlan cannot depend on itself: {relative_path}', file=sys.stderr)
            sys.exit(1)
        if (
            metadata['status'] == 'COMPLETED'
            and plan_metadata[dependency]['status'] != 'COMPLETED'
        ):
            print(
                f'Completed ExecPlan cannot depend on an active plan: {relative_path} -> {dependency}',
                file=sys.stderr,
            )
            sys.exit(1)
    if (
        metadata['status'] == 'ACTIVE'
        and metadata['implementation_ready']
        and metadata['kind'] != 'IMPLEMENTATION'
    ):
        print(
            f'Only active IMPLEMENTATION ExecPlans may be Implementation-Ready: {relative_path}',
            file=sys.stderr,
        )
        sys.exit(1)
    if metadata['status'] == 'ACTIVE' and metadata['implementation_ready']:
        incomplete_dependencies = [
            dependency
            for dependency in metadata['dependencies']
            if plan_metadata[dependency]['status'] != 'COMPLETED'
        ]
        if incomplete_dependencies:
            print(
                f'Implementation-Ready ExecPlan has incomplete direct dependencies: '
                f'{relative_path} -> {", ".join(incomplete_dependencies)}',
                file=sys.stderr,
            )
            sys.exit(1)

visiting = set()
visited = set()

def visit_exec_plan(relative_path, trail):
    if relative_path in visiting:
        cycle_start = trail.index(relative_path)
        cycle = trail[cycle_start:] + [relative_path]
        print(f'ExecPlan dependency cycle: {" -> ".join(cycle)}', file=sys.stderr)
        sys.exit(1)
    if relative_path in visited:
        return
    visiting.add(relative_path)
    for dependency in plan_metadata[relative_path]['dependencies']:
        visit_exec_plan(dependency, trail + [relative_path])
    visiting.remove(relative_path)
    visited.add(relative_path)

for relative_path in plan_metadata:
    visit_exec_plan(relative_path, [])

expected_execution_metadata = {
    'customer-order-cancellation-and-recovery.md': ('ORCHESTRATION', False, []),
    'signed-cursor-foundation.md': ('IMPLEMENTATION', False, []),
    'customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md': (
        'IMPLEMENTATION', True, [
            'customer-order-cancellation-00-contract-baseline.md',
        ],
    ),
    'customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md': (
        'IMPLEMENTATION', True, ['customer-order-cancellation-00-contract-baseline.md'],
    ),
    'customer-order-cancellation-12-partial-refund-allocation-and-restoration.md': (
        'IMPLEMENTATION', True, [
            'customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md',
            'customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md',
        ],
    ),
    'customer-order-cancellation-13-refund-earned-point-recovery-foundation.md': (
        'IMPLEMENTATION', True, ['customer-order-cancellation-12-partial-refund-allocation-and-restoration.md'],
    ),
    'customer-order-cancellation-14-point-account-read-vertical-slice.md': (
        'IMPLEMENTATION', True, [
            'customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md',
            'customer-order-cancellation-13-refund-earned-point-recovery-foundation.md',
            'signed-cursor-foundation.md',
        ],
    ),
    'customer-order-cancellation-15-settlement-input-snapshot-foundation.md': (
        'IMPLEMENTATION', True, [
            'customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md',
        ],
    ),
    'customer-order-cancellation-16-immutable-refund-and-loyalty-event-producer.md': (
        'IMPLEMENTATION', False, [
            'customer-order-cancellation-12-partial-refund-allocation-and-restoration.md',
            'customer-order-cancellation-13-refund-earned-point-recovery-foundation.md',
            'customer-order-cancellation-15-settlement-input-snapshot-foundation.md',
        ],
    ),
    'customer-order-cancellation-20-settlement-foundation.md': (
        'IMPLEMENTATION', True, [
            'customer-order-cancellation-15-settlement-input-snapshot-foundation.md',
            'customer-order-cancellation-16-immutable-refund-and-loyalty-event-producer.md',
            'signed-cursor-foundation.md',
        ],
    ),
    'customer-order-cancellation-30-order-compensation-foundation.md': (
        'IMPLEMENTATION', True, [
            'customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md',
            'customer-order-cancellation-20-settlement-foundation.md',
        ],
    ),
    'customer-order-cancellation-40-command.md': ('IMPLEMENTATION', True, [
        'customer-order-cancellation-30-order-compensation-foundation.md',
    ]),
    'customer-order-cancellation-50-recovery.md': ('IMPLEMENTATION', True, [
        'customer-order-cancellation-40-command.md',
    ]),
}
plan_paths_by_filename = {}
for relative_path in plan_metadata:
    filename = Path(relative_path).name
    if filename in plan_paths_by_filename:
        print(f'ExecPlan filename is ambiguous: {filename}', file=sys.stderr)
        sys.exit(1)
    plan_paths_by_filename[filename] = relative_path

for filename, expected_metadata in expected_execution_metadata.items():
    relative_path = plan_paths_by_filename.get(filename)
    if relative_path is None:
        print(f'Missing execution-plan metadata target: {filename}', file=sys.stderr)
        sys.exit(1)
    expected_kind, expected_writes, expected_dependency_filenames = expected_metadata
    expected_dependencies = []
    for dependency_filename in expected_dependency_filenames:
        dependency_path = plan_paths_by_filename.get(dependency_filename)
        if dependency_path is None:
            print(f'Missing execution-plan dependency target: {dependency_filename}', file=sys.stderr)
            sys.exit(1)
        expected_dependencies.append(dependency_path)
    actual = plan_metadata[relative_path]
    if (
        actual['kind'] != expected_kind
        or actual['writes_migration'] != expected_writes
        or actual['dependencies'] != expected_dependencies
    ):
        print(f'Customer cancellation execution metadata is stale: {relative_path}', file=sys.stderr)
        sys.exit(1)

plan10_path = plan_paths_by_filename[
    'customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md'
]
if plan_metadata[plan10_path]['status'] != 'COMPLETED':
    print(
        'Plan 10 must be completed after its only direct Plan 00 dependency is complete.',
        file=sys.stderr,
    )
    sys.exit(1)

plan12_path = plan_paths_by_filename[
    'customer-order-cancellation-12-partial-refund-allocation-and-restoration.md'
]
plan13_path = plan_paths_by_filename[
    'customer-order-cancellation-13-refund-earned-point-recovery-foundation.md'
]
plan16_path = plan_paths_by_filename[
    'customer-order-cancellation-16-immutable-refund-and-loyalty-event-producer.md'
]
if plan_metadata[plan12_path]['status'] != 'COMPLETED':
    print('Plan 12 must be completed after its Plan 10/11 dependencies and validation pass.', file=sys.stderr)
    sys.exit(1)
if not plan_metadata[plan13_path]['implementation_ready']:
    print('Plan 13 must become implementation-ready after Plan 12 completes.', file=sys.stderr)
    sys.exit(1)
if plan_metadata[plan16_path]['implementation_ready']:
    print('Plan 16 must remain blocked while Plan 13 and Plan 15 are active.', file=sys.stderr)
    sys.exit(1)

master_plan = (
    root / 'docs/exec-plans/active/customer-order-cancellation-and-recovery.md'
).read_text(encoding='utf-8')
if (
    '[PointLot issuer provenance foundation을 만든다](../completed/customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md) — 00'
    not in master_plan
    or 'customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md) — 00, cursor'
    in master_plan
):
    print('Master plan must keep Plan 10 dependent on Plan 00 only.', file=sys.stderr)
    sys.exit(1)
execution_dependency_adr = (
    root / 'docs/adr/ADR-072-execplan-unattended-execution-and-migration-lane.md'
).read_text(encoding='utf-8')
if (
    'Plan 00 -> Plan 10 issuer -> Plan 15 snapshot' not in execution_dependency_adr
    or re.search(r'signed[- ]cursor foundation\s*(?:->|→)\s*Plan 10', execution_dependency_adr)
):
    print('ADR-072 must not use signed cursor as a Plan 10 queue dependency.', file=sys.stderr)
    sys.exit(1)

traceability = (root / 'docs/architecture/policy-traceability.md').read_text(encoding='utf-8')
br14_row = next((line for line in traceability.splitlines() if line.startswith('| BR-14 |')), '')
if 'Blocked by' not in br14_row:
    print('BR-14 traceability must expose its implementation prerequisites.', file=sys.stderr)
    sys.exit(1)
for br_id, required_record in {
    'BR-18': 'ADR-071',
    'BR-19': 'ADR-071',
    'BR-20': 'ADR-071',
    'BR-28': 'ADR-070',
}.items():
    row = next((line for line in traceability.splitlines() if line.startswith(f'| {br_id} |')), '')
    if required_record not in row:
        print(f'{br_id} traceability must include {required_record}.', file=sys.stderr)
        sys.exit(1)

readiness = (root / 'docs/quality/customer-order-cancellation-readiness.md').read_text(encoding='utf-8')
if 'CLEAN_CUTOVER_GATE = PASSED' not in readiness:
    print('Customer cancellation readiness must record the evidenced clean-cutover gate result.', file=sys.stderr)
    sys.exit(1)
normalized_readiness = re.sub(r'\s+', ' ', readiness)
required_current_readiness = (
    'customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md',
    'customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md',
    'customer-order-cancellation-12-partial-refund-allocation-and-restoration.md',
    'customer-order-cancellation-13-refund-earned-point-recovery-foundation.md',
    'customer-order-cancellation-14-point-account-read-vertical-slice.md',
    'customer-order-cancellation-15-settlement-input-snapshot-foundation.md',
    'customer-order-cancellation-16-immutable-refund-and-loyalty-event-producer.md',
    'customer-order-cancellation-20-settlement-foundation.md',
    'customer-order-cancellation-30-order-compensation-foundation.md',
    '11 policy/grants + 13 recovery + signed cursor',
)
if not all(fragment in normalized_readiness for fragment in required_current_readiness):
    print('Customer cancellation readiness is missing the current foundation graph.', file=sys.stderr)
    sys.exit(1)
for stale_fragment in (
    '10-partial-refund-allocation-foundation',
    '10/20/30은 00의 계약·migration 전략을 입력으로 독립 진행',
    'partial refund allocation plan이 통과함',
):
    if stale_fragment in normalized_readiness:
        print(f'Customer cancellation readiness still contains stale plan structure: {stale_fragment}', file=sys.stderr)
        sys.exit(1)

release_evidence = (
    root / 'docs/quality/customer-order-cancellation-release-evidence.md'
).read_text(encoding='utf-8')
normalized_release_evidence = re.sub(r'\s+', ' ', release_evidence)
required_release_evidence = [
    '**Evidence source:** product-owner operational-state attestation',
    '**Attestor role:** product owner',
    '| shared/production deployment environment | 0 |',
    '| production/shared compensation schema, table와 row | 0 |',
    '| completed `OrderRejectedV1`/`OrderCancelledV1` publication | 0 |',
    '| incomplete `OrderRejectedV1`/`OrderCancelledV1` publication | 0 |',
    '| external 또는 independently deployed consumer | 0 |',
    '| rollback 대상 production binary/data | 0 |',
    '| production/shared 환경에 적용된 migration | 0 |',
    'CLEAN_CUTOVER_GATE = PASSED',
    'compensation schema 변경 또는 최초 production/shared 배포 직전에 같은 inventory를 다시 확인',
]
for statement in required_release_evidence:
    if statement not in normalized_release_evidence:
        print(f'Clean-cutover release evidence is incomplete: {statement}', file=sys.stderr)
        sys.exit(1)
if '이 PASS는 migration/event 전략만 허용한다.' not in normalized_release_evidence:
    print('Clean-cutover PASS must not imply that customer cancellation is implemented.', file=sys.stderr)
    sys.exit(1)

closure = (root / 'docs/decisions/customer-order-cancellation-decision-closure.md').read_text(encoding='utf-8')
if (
    '| Clean cutover gate result |' not in closure
    or '`PASSED`; 모든 운영 항목이 명시적 0' not in closure
):
    print('Decision closure must record the evidenced clean-cutover PASS.', file=sys.stderr)
    sys.exit(1)
if 'Canonical documents aligned' not in closure or 'Product owner confirmed' not in closure:
    print('Decision closure must distinguish document alignment from product-owner confirmation.', file=sys.stderr)
    sys.exit(1)
closure_rows = [
    line for line in closure.splitlines()
    if line.startswith('| ') and not line.startswith('| Topic |') and not line.startswith('| -----')
]
if not closure_rows:
    print('Decision closure decision rows are missing.', file=sys.stderr)
    sys.exit(1)
for row in closure_rows:
    cells = [cell.strip() for cell in row.split('|')]
    if len(cells) < 8 or cells[4] != 'Yes' or cells[5] != 'Yes':
        print(f'Decision closure must keep document alignment and product-owner confirmation explicit: {row}', file=sys.stderr)
        sys.exit(1)

br14_start = policy.index('## BR-14 ')
br15_start = policy.index('## BR-15 ')
br14 = policy[br14_start:br15_start]
normalized_br14 = re.sub(r'\s+', ' ', br14)

reason_contract = [
    '`Order` row에는 reason code와 허용된 detail을 저장한다.',
    '`AuditRecord`에는 정규화된 reason code만 저장하고 detail은 복제하지 않는다.',
    'Refund 내부 기록과 외부 결제 취소 요청에는 처리에 필요한 정규화된 reason만 사용하며 detail은 전달하지 않는다.',
    '`OrderCancelledV1` persistent payload에는 reason code와 detail을 모두 포함하지 않는다.',
    '애플리케이션 로그에도 reason code와 detail을 복제하지 않는다.',
]
for statement in reason_contract:
    if statement not in normalized_br14:
        print(f'BR-14 cancellation reason data boundary is incomplete: {statement}', file=sys.stderr)
        sys.exit(1)
if '이벤트, 감사 기록과 외부 결제 Provider에는 reason code만 전달' in normalized_br14:
    print('BR-14 still says persistent events receive cancellation reasonCode.', file=sys.stderr)
    sys.exit(1)

required_tests = br14.split('- **Required Tests:**', 1)[1].split('- **ADR Required:**', 1)[0]
normalized_tests = re.sub(r'\s+', ' ', required_tests)
clean_marker = '**Clean-cutover path (release gate 전체 0):**'
forward_marker = '**Forward-migration path (release gate nonzero 또는 unknown):**'
if clean_marker not in normalized_tests or forward_marker not in normalized_tests:
    print('BR-14 Required Tests must separate clean-cutover and forward-migration paths.', file=sys.stderr)
    sys.exit(1)
if normalized_tests.index(clean_marker) > normalized_tests.index(forward_marker):
    print('BR-14 release-gate test paths are out of order.', file=sys.stderr)
    sys.exit(1)
clean_tests, forward_tests = normalized_tests.split(forward_marker, 1)
if 'legacy compatibility layer와 version 이중 발행 없음' not in clean_tests:
    print('Clean-cutover tests must assert no legacy compatibility or dual publication.', file=sys.stderr)
    sys.exit(1)
forward_requirements = [
    '기존 migration과 V1 계약 유지',
    '구 publication 역직렬화',
    'legacy listener routing',
    'publication drain',
    'rollback compatibility',
    '별도 version 또는 compatibility bridge',
]
for requirement in forward_requirements:
    if requirement not in forward_tests:
        print(f'Forward-migration tests are incomplete: {requirement}', file=sys.stderr)
        sys.exit(1)
if 'legacy listener target mapping 유지와 미완료 V1 publication 소진 검증' in normalized_tests:
    print('Legacy compatibility test is still unconditional in BR-14.', file=sys.stderr)
    sys.exit(1)

adr030 = re.sub(
    r'\s+',
    ' ',
    (root / 'docs/adr/ADR-030-customer-cancellation-authorization.md').read_text(encoding='utf-8'),
)
if (
    '**Amended by:** ADR-038' not in adr030
    or '| `FAILED`, `MANUAL_REVIEW` | `PROCESSING` | `REFUND_DELAYED` |' not in adr030
):
    print('ADR-030 does not record the accepted customer-refund projection amendment.', file=sys.stderr)
    sys.exit(1)

context_map = (root / 'docs/architecture/context-map.md').read_text(encoding='utf-8')
ownership_rows = {
    line.split('|')[1].strip(): line
    for line in context_map.splitlines()
    if line.startswith('| ') and len(line.split('|')) > 3
}
owned_data_expectations = {
    'Ordering': ['AcceptanceTimeoutWork'],
    'Payment': ['PaymentCancellationRecoverySnapshot'],
    'Promotion': ['CompensationCouponTermsSnapshot'],
    'Operations': [
        'OrderCompensationCase',
        'OrderCompensationBenefitPolicySnapshot',
        'BenefitRestorationPolicyVersion',
        'RepairProposal',
    ],
}
for context, aggregates in owned_data_expectations.items():
    row = ownership_rows.get(context, '')
    for aggregate in aggregates:
        if aggregate not in row:
            print(
                f'Context Map data ownership omits {aggregate} from {context}.',
                file=sys.stderr,
            )
            sys.exit(1)

flow = (root / 'docs/product/end-to-end-flow.md').read_text(encoding='utf-8')
flow_headings = re.findall(r'^## (\d+)\. (.+)$', flow, flags=re.MULTILINE)
if [int(number) for number, _ in flow_headings] != list(range(1, len(flow_headings) + 1)):
    print('End-to-end flow sections are not numbered consecutively.', file=sys.stderr)
    sys.exit(1)
if not any('Customer cancellation' in title for _, title in flow_headings):
    print('End-to-end flow must cover the customer cancellation path.', file=sys.stderr)
    sys.exit(1)

clean_cutover_migrations = {
    'docs/adr/ADR-029-customer-cancellation-scope.md',
    'docs/adr/ADR-033-order-compensation-case-generalization.md',
    'docs/adr/ADR-040-order-termination-resource-release.md',
    'docs/adr/ADR-042-benefit-restoration-ledger-metadata.md',
}
adr059 = (root / 'docs/adr/ADR-059-pre-release-compensation-clean-cutover.md').read_text(
    encoding='utf-8'
)
for source in sorted(clean_cutover_migrations):
    adr_id = re.match(r'docs/adr/(ADR-\d{3})', source).group(1)
    if adr_id not in adr059:
        print(
            f'ADR-059 must name {adr_id} in the migration mechanics it replaces.',
            file=sys.stderr,
        )
        sys.exit(1)
    body = (root / source).read_text(encoding='utf-8')
    if 'backfill' in body and 'ADR-059' not in body:
        print(
            f'{source} describes a legacy backfill without pointing at the ADR-059 gate.',
            file=sys.stderr,
        )
        sys.exit(1)

openapi_text = (root / 'openapi/beanflow-v1.yaml').read_text(encoding='utf-8')
for schema_name in ('PaymentApprovalRecoverySummary', 'CancellationRefundRecoverySummary'):
    if f'    {schema_name}:' not in openapi_text:
        print(f'OpenAPI recovery schema is missing: {schema_name}', file=sys.stderr)
        sys.exit(1)
if '    PaymentRecoverySummary:' in openapi_text:
    print('OpenAPI reintroduced the shared PaymentRecoverySummary schema.', file=sys.stderr)
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
    from openapi_spec_validator import validate
    from openapi_spec_validator.validation.exceptions import OpenAPIValidationError
    from referencing.exceptions import Unresolvable
except ImportError as exc:
    print(
        'OpenAPI validation dependencies are missing. '
        'Install scripts/ci/requirements-docs.txt.',
        file=sys.stderr,
    )
    print(f'Missing dependency: {exc}', file=sys.stderr)
    sys.exit(1)
else:
    try:
        target_openapi_path = root / 'openapi/beanflow-v1.yaml'
        deployed_openapi_path = root / 'openapi/beanflow-v1-deployed.yaml'
        with target_openapi_path.open(encoding='utf-8') as f:
            spec = yaml.safe_load(f)
        validate(spec, base_uri=target_openapi_path.resolve().as_uri())
        with deployed_openapi_path.open(encoding='utf-8') as f:
            deployed_spec = yaml.safe_load(f)
        validate(deployed_spec, base_uri=deployed_openapi_path.resolve().as_uri())
    except Unresolvable as exc:
        print(f'OpenAPI 3.1 validation failed: unresolved reference {exc.ref}', file=sys.stderr)
        sys.exit(1)
    except (yaml.YAMLError, OpenAPIValidationError, TypeError, ValueError) as exc:
        print(f'OpenAPI 3.1 validation failed: {exc}', file=sys.stderr)
        sys.exit(1)
    if spec.get('openapi') != '3.1.0':
        print('OpenAPI version must be 3.1.0.', file=sys.stderr)
        sys.exit(1)
    if deployed_spec.get('openapi') != '3.1.0':
        print('Deployed OpenAPI version must be 3.1.0.', file=sys.stderr)
        sys.exit(1)
    if spec.get('info', {}).get('x-beanflow-contract-status') != 'target':
        print('Target OpenAPI must declare x-beanflow-contract-status: target.', file=sys.stderr)
        sys.exit(1)
    if deployed_spec.get('info', {}).get('x-beanflow-contract-status') != 'deployed':
        print('Deployed OpenAPI must declare x-beanflow-contract-status: deployed.', file=sys.stderr)
        sys.exit(1)
    if not spec.get('info', {}).get('x-beanflow-contract-date') or not deployed_spec.get('info', {}).get('x-beanflow-contract-date'):
        print('Both OpenAPI contracts must declare x-beanflow-contract-date.', file=sys.stderr)
        sys.exit(1)

    expected_deployed_operations = {
        ('/orders', 'post'),
        ('/orders/{orderId}', 'get'),
        ('/orders/{orderId}/payment-confirmations', 'post'),
        ('/payments/{paymentId}/refunds', 'post'),
        ('/store-orders/{orderId}', 'get'),
        ('/store-orders/{orderId}/status', 'patch'),
        ('/operations/policies/expired-benefit-restoration', 'get'),
        ('/operations/policies/expired-benefit-restoration/{trigger}/{benefitType}', 'patch'),
    }
    actual_deployed_operations = {
        (path, method)
        for path, path_item in deployed_spec.get('paths', {}).items()
        for method in path_item
        if method in {'get', 'post', 'put', 'patch', 'delete'}
    }
    if actual_deployed_operations != expected_deployed_operations:
        print('Deployed OpenAPI operations do not match the current controller allowlist.', file=sys.stderr)
        print('Missing:', sorted(expected_deployed_operations - actual_deployed_operations), file=sys.stderr)
        print('Unexpected:', sorted(actual_deployed_operations - expected_deployed_operations), file=sys.stderr)
        sys.exit(1)
    deployed_schemas = deployed_spec.get('components', {}).get('schemas', {})
    deployed_transition = deployed_schemas.get('DeployedStoreOrderTransitionResult', {})
    if set(deployed_transition.get('required', [])) != {'order', 'rejectionRecovery', 'replayed'}:
        print('Deployed store transition must preserve the current legacy response wrapper.', file=sys.stderr)
        sys.exit(1)
    deployed_policy_get = deployed_spec['paths']['/operations/policies/expired-benefit-restoration']['get']
    deployed_policy_get_refs = {
        parameter.get('$ref')
        for parameter in deployed_policy_get.get('parameters', [])
        if isinstance(parameter, dict)
    }
    if deployed_policy_get_refs != {'./beanflow-v1.yaml#/components/parameters/AccessReason'}:
        print('Deployed policy GET must require the audited access-reason contract.', file=sys.stderr)
        sys.exit(1)
    deployed_policy_list = deployed_policy_get['responses']['200']['content']['application/json']['schema']
    if deployed_policy_list.get('minItems') != 5 or deployed_policy_list.get('maxItems') != 5:
        print('Deployed policy GET must return exactly five heads.', file=sys.stderr)
        sys.exit(1)
    deployed_policy_patch = deployed_spec['paths'][
        '/operations/policies/expired-benefit-restoration/{trigger}/{benefitType}'
    ]['patch']
    deployed_policy_patch_names = {
        parameter.get('name')
        for parameter in deployed_policy_patch.get('parameters', [])
        if isinstance(parameter, dict) and parameter.get('name')
    }
    if deployed_policy_patch_names != {'trigger', 'benefitType'}:
        print('Deployed policy PATCH must expose only the keyed policy path parameters.', file=sys.stderr)
        sys.exit(1)
    if {
        'DeployedExpiredBenefitRestorationPolicy',
        'DeployedUpdateExpiredBenefitRestorationPolicyRequest',
    } & set(deployed_schemas):
        print('Deployed singleton policy schemas must be removed after keyed policy deployment.', file=sys.stderr)
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
        '/operations/point-accounts/{accountId}/adjustments',
        '/stores/{storeId}/settlements',
        '/stores/{storeId}/settlements/{settlementBatchId}/items',
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
        ('/operations/point-accounts/{accountId}/adjustments', 'post'),
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

    cancellation = spec['components']['schemas']['Cancellation']
    if cancellation['properties']['orderState'].get('const') != 'CANCELLED':
        print('Cancellation success must expose orderState=CANCELLED.', file=sys.stderr)
        sys.exit(1)

    cancellation_detail = spec['components']['schemas']['CancellationRequest']['properties']['detail']
    cancellation_detail_description = cancellation_detail.get('description', '')
    normalized_cancellation_detail_description = re.sub(r'\s+', ' ', cancellation_detail_description)
    required_cancellation_detail_fragments = (
        'The server trims it before validation',
        'empty normalized value is treated as absent',
        'present normalized value must contain 1 to 200 characters',
        'Control characters are rejected',
    )
    if not all(fragment in normalized_cancellation_detail_description for fragment in required_cancellation_detail_fragments):
        print('Cancellation detail normalization/control-character contract is missing.', file=sys.stderr)
        sys.exit(1)
    if {'minLength', 'maxLength'} & set(cancellation_detail):
        print('Cancellation detail length must be validated after normalization, not by a raw schema length.', file=sys.stderr)
        sys.exit(1)

    cancellation_description = spec['paths']['/orders/{orderId}/cancellations']['post'].get('description', '')
    if 'RETRY_SCHEDULED' not in cancellation_description:
        print('Cancellation unresolved-refund states are incomplete.', file=sys.stderr)
        sys.exit(1)

    schemas = spec['components']['schemas']
    create_order_refs = {
        branch.get('$ref')
        for branch in schemas['CreateOrderResult'].get('oneOf', [])
        if isinstance(branch, dict)
    }
    expected_create_order_refs = {
        '#/components/schemas/PendingPaymentOrderCreation',
        '#/components/schemas/BenefitOnlyOrderCreation',
    }
    if create_order_refs != expected_create_order_refs:
        print('CreateOrderResult must keep the pending-payment and benefit-only variants.', file=sys.stderr)
        sys.exit(1)
    pending_payment_requirements = {
        required
        for branch in schemas['PendingPaymentOrder'].get('allOf', [])
        if isinstance(branch, dict)
        for required in branch.get('required', [])
    }
    if 'reservationExpiresAt' not in pending_payment_requirements:
        print('PendingPaymentOrder must require reservationExpiresAt.', file=sys.stderr)
        sys.exit(1)
    order_conflict_description = spec['components']['responses']['OrderCreationConflict'].get('description', '')
    if 'IDEMPOTENCY_REQUEST_IN_PROGRESS' not in order_conflict_description:
        print('Order creation conflict must document IDEMPOTENCY_REQUEST_IN_PROGRESS.', file=sys.stderr)
        sys.exit(1)

    approval_recovery_ref = schemas['PaymentConfirmation']['properties']['recovery'].get('$ref')
    cancellation_recovery_ref = schemas['Cancellation']['properties']['paymentRecovery'].get('$ref')
    order_recovery_ref = schemas['Order']['properties']['paymentRecovery'].get('$ref')
    expected_approval_ref = '#/components/schemas/PaymentApprovalRecoverySummary'
    expected_cancellation_ref = '#/components/schemas/CancellationRefundRecoverySummary'
    if approval_recovery_ref != expected_approval_ref:
        print('PaymentConfirmation.recovery must use PaymentApprovalRecoverySummary.', file=sys.stderr)
        sys.exit(1)
    if cancellation_recovery_ref != expected_cancellation_ref or order_recovery_ref != expected_cancellation_ref:
        print('Cancellation and Order paymentRecovery must use CancellationRefundRecoverySummary.', file=sys.stderr)
        sys.exit(1)
    if approval_recovery_ref == cancellation_recovery_ref:
        print('Payment approval and cancellation refund recovery schemas must not be shared.', file=sys.stderr)
        sys.exit(1)

    approval_recovery = schemas['PaymentApprovalRecoverySummary']
    approval_properties = set(approval_recovery.get('properties', {}))
    if approval_properties != {'state', 'lastUpdatedAt'}:
        print('PaymentApprovalRecoverySummary must contain only state and lastUpdatedAt.', file=sys.stderr)
        sys.exit(1)
    if set(approval_recovery.get('required', [])) != {'state', 'lastUpdatedAt'}:
        print('PaymentApprovalRecoverySummary required fields are incorrect.', file=sys.stderr)
        sys.exit(1)
    expected_approval_states = {'REQUESTED', 'PROCESSING', 'RECONCILING', 'SUCCEEDED', 'MANUAL_REVIEW'}
    actual_approval_states = set(approval_recovery['properties']['state'].get('enum', []))
    if actual_approval_states != expected_approval_states:
        print('PaymentApprovalRecoverySummary states are incomplete or mixed with Refund states.', file=sys.stderr)
        sys.exit(1)

    cancellation_recovery = schemas['CancellationRefundRecoverySummary']
    if not cancellation_recovery.get('allOf'):
        print('CancellationRefundRecoverySummary conditional customer contract is missing.', file=sys.stderr)
        sys.exit(1)
    expected_customer_states = {'NOT_REQUIRED', 'REQUESTED', 'PROCESSING', 'SUCCEEDED'}
    actual_customer_states = set(cancellation_recovery['properties']['state'].get('enum', []))
    if actual_customer_states != expected_customer_states:
        print('Cancellation refund customer projection enum is incorrect.', file=sys.stderr)
        sys.exit(1)
    forbidden_customer_states = {
        'RETRY_SCHEDULED', 'FAILED', 'UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW', 'SETUP_INCOMPLETE'
    }
    if actual_customer_states & forbidden_customer_states:
        print('Internal Refund states reappeared in the customer projection enum.', file=sys.stderr)
        sys.exit(1)
    cancellation_properties = set(cancellation_recovery.get('properties', {}))
    required_cancellation_properties = {
        'state',
        'noticeCode',
        'approvedAmountKrw',
        'succeededRefundAmountBeforeCancellationKrw',
        'cancellationRequestedRefundAmountKrw',
        'remainingRefundableAmountKrw',
        'lastUpdatedAt',
    }
    if cancellation_properties != required_cancellation_properties:
        print('CancellationRefundRecoverySummary notice/allocation contract is incomplete.', file=sys.stderr)
        sys.exit(1)

    amount_fields = {
        'approvedAmountKrw',
        'succeededRefundAmountBeforeCancellationKrw',
        'remainingRefundableAmountKrw',
    }
    not_required_branches = [
        branch for branch in cancellation_recovery['allOf']
        if branch.get('if', {}).get('properties', {}).get('state', {}).get('const') == 'NOT_REQUIRED'
    ]
    if len(not_required_branches) != 1:
        print('CancellationRefundRecoverySummary must state exactly one NOT_REQUIRED rule.', file=sys.stderr)
        sys.exit(1)
    not_required_then = not_required_branches[0].get('then', {})
    if not_required_then.get('properties', {}).get('cancellationRequestedRefundAmountKrw', {}).get('const') != 0:
        print('NOT_REQUIRED must pin the cancellation requested refund amount to zero.', file=sys.stderr)
        sys.exit(1)
    if not_required_then.get('not', {}).get('required') != ['noticeCode']:
        print('NOT_REQUIRED must not carry a delay notice.', file=sys.stderr)
        sys.exit(1)
    if amount_fields & set(not_required_then.get('properties', {})):
        print(
            'NOT_REQUIRED must not force the approved, prior-succeeded or remaining '
            'amounts to zero; a prior full refund leaves them positive.',
            file=sys.stderr,
        )
        sys.exit(1)
    if amount_fields & set(not_required_then.get('required', [])):
        print('NOT_REQUIRED must not require amounts a missing snapshot cannot verify.', file=sys.stderr)
        sys.exit(1)

    order_schema = schemas['Order']
    order_cancellation_fields = {'cancelledAt', 'cancellationCause', 'cancellationReasonCode'}
    if not order_cancellation_fields <= set(order_schema.get('properties', {})):
        print('Customer Order projection must expose the cancellation fact fields.', file=sys.stderr)
        sys.exit(1)
    if order_cancellation_fields & set(order_schema.get('required', [])):
        print('Order cancellation fields are absent unless the order is CANCELLED.', file=sys.stderr)
        sys.exit(1)
    store_order = schemas['StoreOrder']
    store_order_refs = {
        branch.get('$ref') for branch in store_order.get('allOf', []) if isinstance(branch, dict)
    }
    if '#/components/schemas/Order' not in store_order_refs:
        print('StoreOrder must project the Order schema.', file=sys.stderr)
        sys.exit(1)
    store_exclusions = {
        tuple(item.get('required', []))
        for branch in store_order.get('allOf', [])
        if isinstance(branch, dict)
        for item in branch.get('not', {}).get('anyOf', [])
    }
    for excluded in ('cancellationReasonCode', 'paymentRecovery'):
        if (excluded,) not in store_exclusions:
            print(f'StoreOrder must exclude {excluded} from the store projection.', file=sys.stderr)
            sys.exit(1)
    if schemas['StoreOrderResult']['properties']['order'].get('$ref') != '#/components/schemas/StoreOrder':
        print('StoreOrderResult must return the StoreOrder projection.', file=sys.stderr)
        sys.exit(1)

    refund = schemas['Refund']
    refund_properties = set(refund.get('properties', {}))
    requested_refund_amounts = {
        'cashRefundRequestedKrw',
        'pointsRestorationRequestedKrw',
    }
    confirmed_refund_amounts = {'cashRefundedKrw', 'pointsRestoredKrw'}
    if not requested_refund_amounts | confirmed_refund_amounts | {'pointsRestorationState'} <= refund_properties:
        print('Refund requested/confirmed amount fields are incomplete.', file=sys.stderr)
        sys.exit(1)
    refund_required = set(refund.get('required', []))
    if not requested_refund_amounts | {'pointsRestorationState'} <= refund_required:
        print('Refund requested amount snapshots and points state must exist in every state.', file=sys.stderr)
        sys.exit(1)
    if confirmed_refund_amounts & refund_required:
        print('Refund confirmed amounts must not be unconditionally required.', file=sys.stderr)
        sys.exit(1)
    refund_success_branches = [
        branch for branch in refund.get('allOf', [])
        if branch.get('if', {}).get('properties', {}).get('state', {}).get('const') == 'SUCCEEDED'
    ]
    if len(refund_success_branches) != 1:
        print('Refund must have exactly one SUCCEEDED amount rule.', file=sys.stderr)
        sys.exit(1)
    refund_success = refund_success_branches[0]
    if refund_success.get('then', {}).get('required') != ['cashRefundedKrw']:
        print('SUCCEEDED cash Refund must require cashRefundedKrw.', file=sys.stderr)
        sys.exit(1)
    if refund_success.get('else', {}).get('not', {}).get('required') != ['cashRefundedKrw']:
        print('Non-SUCCEEDED cash Refund must forbid cashRefundedKrw.', file=sys.stderr)
        sys.exit(1)
    expected_points_states = {
        'NOT_REQUIRED', 'REQUESTED', 'PROCESSING', 'SUCCEEDED', 'MANUAL_REVIEW'
    }
    actual_points_states = set(
        refund['properties']['pointsRestorationState'].get('enum', [])
    )
    if actual_points_states != expected_points_states:
        print('Refund points restoration states are incomplete.', file=sys.stderr)
        sys.exit(1)
    points_success_branches = [
        branch for branch in refund.get('allOf', [])
        if branch.get('if', {}).get('properties', {}).get('pointsRestorationState', {}).get('const')
        == 'SUCCEEDED'
    ]
    if len(points_success_branches) != 1:
        print('Refund must have exactly one points SUCCEEDED amount rule.', file=sys.stderr)
        sys.exit(1)
    points_success = points_success_branches[0]
    if points_success.get('then', {}).get('required') != ['pointsRestoredKrw']:
        print('SUCCEEDED points restoration must require pointsRestoredKrw.', file=sys.stderr)
        sys.exit(1)
    if points_success.get('else', {}).get('not', {}).get('required') != ['pointsRestoredKrw']:
        print('Non-SUCCEEDED points restoration must forbid pointsRestoredKrw.', file=sys.stderr)
        sys.exit(1)
    points_not_required_branches = [
        branch for branch in refund.get('allOf', [])
        if branch.get('if', {}).get('properties', {}).get('pointsRestorationState', {}).get('const')
        == 'NOT_REQUIRED'
    ]
    if len(points_not_required_branches) != 1:
        print('Refund must have exactly one points NOT_REQUIRED amount rule.', file=sys.stderr)
        sys.exit(1)
    points_not_required = points_not_required_branches[0]
    if (
        points_not_required.get('then', {}).get('properties', {})
        .get('pointsRestorationRequestedKrw', {}).get('const')
        != 0
    ):
        print('NOT_REQUIRED points restoration must pin requested points to zero.', file=sys.stderr)
        sys.exit(1)
    if (
        points_not_required.get('else', {}).get('properties', {})
        .get('pointsRestorationRequestedKrw', {}).get('minimum')
        != 1
    ):
        print('Required points restoration must have a positive requested amount.', file=sys.stderr)
        sys.exit(1)
    points_after_cash_branches = [
        branch for branch in refund.get('allOf', [])
        if set(
            branch.get('if', {}).get('properties', {})
            .get('pointsRestorationState', {}).get('enum', [])
        ) == {'PROCESSING', 'SUCCEEDED', 'MANUAL_REVIEW'}
    ]
    if len(points_after_cash_branches) != 1:
        print('Refund must constrain active points restoration to cash success.', file=sys.stderr)
        sys.exit(1)
    if (
        points_after_cash_branches[0].get('then', {}).get('properties', {})
        .get('state', {}).get('const')
        != 'SUCCEEDED'
    ):
        print('Active points restoration requires a SUCCEEDED cash Refund.', file=sys.stderr)
        sys.exit(1)
    points_before_cash_branches = [
        branch for branch in refund.get('allOf', [])
        if branch.get('if', {}).get('properties', {})
        .get('pointsRestorationState', {}).get('const') == 'REQUESTED'
    ]
    if len(points_before_cash_branches) != 1:
        print('Refund must have exactly one REQUESTED-before-cash-success rule.', file=sys.stderr)
        sys.exit(1)
    requested_cash_state_rule = (
        points_before_cash_branches[0].get('then', {}).get('properties', {})
        .get('state', {}).get('not', {})
    )
    if requested_cash_state_rule.get('const') != 'SUCCEEDED':
        print('REQUESTED points restoration must forbid a SUCCEEDED cash Refund.', file=sys.stderr)
        sys.exit(1)

    settlement_items_path = spec['paths'][
        '/stores/{storeId}/settlements/{settlementBatchId}/items'
    ]['get']
    settlement_item_parameter_refs = {
        item.get('$ref')
        for item in settlement_items_path.get('parameters', [])
        if isinstance(item, dict)
    }
    expected_settlement_item_parameter_refs = {
        '#/components/parameters/StoreId',
        '#/components/parameters/SettlementBatchId',
        '#/components/parameters/Cursor',
        '#/components/parameters/Limit',
    }
    if settlement_item_parameter_refs != expected_settlement_item_parameter_refs:
        print('SettlementItem list scope/pagination parameters are incomplete.', file=sys.stderr)
        sys.exit(1)
    settlement_item_page_ref = (
        settlement_items_path['responses']['200']['content']['application/json']['schema'].get('$ref')
    )
    if settlement_item_page_ref != '#/components/schemas/SettlementItemPage':
        print('SettlementItem list must return SettlementItemPage.', file=sys.stderr)
        sys.exit(1)
    if (
        schemas['SettlementItemPage']['properties']['items']['items'].get('$ref')
        != '#/components/schemas/SettlementItem'
    ):
        print('SettlementItemPage items must use the SettlementItem projection.', file=sys.stderr)
        sys.exit(1)
    expected_settlement_item_fields = {
        'settlementItemId',
        'settlementBatchId',
        'orderId',
        'completedAt',
        'grossPaidKrw',
        'feeKrw',
        'benefitCostKrw',
        'netSettlementKrw',
        'currency',
    }
    settlement_item = schemas['SettlementItem']
    if set(settlement_item.get('properties', {})) != expected_settlement_item_fields:
        print('SettlementItem projection fields are incomplete or excessive.', file=sys.stderr)
        sys.exit(1)
    if set(settlement_item.get('required', [])) != expected_settlement_item_fields:
        print('SettlementItem projection fields must all be required snapshots.', file=sys.stderr)
        sys.exit(1)

    policy_list_schema = (
        spec['paths']['/operations/policies/expired-benefit-restoration']['get']
        ['responses']['200']['content']['application/json']['schema']
    )
    if policy_list_schema.get('minItems') != 5 or policy_list_schema.get('maxItems') != 5:
        print('Expired benefit policy list must return exactly five heads.', file=sys.stderr)
        sys.exit(1)
    parameters = spec['components']['parameters']
    limit_schema = parameters['Limit']['schema']
    if (
        limit_schema.get('minimum') != 1
        or limit_schema.get('maximum') != 100
        or limit_schema.get('default') != 20
    ):
        print('Common pagination limit must declare default 20 and range 1..100.', file=sys.stderr)
        sys.exit(1)
    cursor_parameter = parameters['Cursor']
    if (
        cursor_parameter.get('in') != 'query'
        or cursor_parameter.get('required') is not False
        or cursor_parameter.get('schema', {}).get('minLength') != 1
        or cursor_parameter.get('schema', {}).get('maxLength') != 2048
        or 'HMAC-signed' not in cursor_parameter.get('description', '')
    ):
        print('Common cursor must be an optional 1..2048-char HMAC-signed query parameter.', file=sys.stderr)
        sys.exit(1)
    if schemas['PageInfo']['properties']['nextCursor'].get('maxLength') != 2048:
        print('PageInfo nextCursor must match the public 2048-character cursor maximum.', file=sys.stderr)
        sys.exit(1)
    radius_parameter = parameters['RadiusMeters']
    if (
        radius_parameter.get('in') != 'query'
        or radius_parameter.get('required') is not True
        or radius_parameter.get('schema', {}).get('minimum') != 1
        or radius_parameter.get('schema', {}).get('maximum') != 10000
    ):
        print('Nearby radiusMeters must be a required integer in the 1..10000 range.', file=sys.stderr)
        sys.exit(1)
    nearby_description = spec['paths']['/stores/nearby']['get'].get('description', '')
    nearby_distance_description = schemas['NearbyStore']['properties']['distanceMeters'].get('description', '')
    if 'canonical micrometer distance tuple' not in nearby_description or 'display value, not the cursor tuple' not in nearby_distance_description:
        print('Nearby distance display/cursor tuple contract is incomplete.', file=sys.stderr)
        sys.exit(1)
    expected_cursor_operations = {
        'GET /stores/nearby',
        'GET /point-accounts/{accountId}/transactions',
        'GET /stores/{storeId}/settlements',
        'GET /stores/{storeId}/settlements/{settlementBatchId}/items',
    }
    cursor_operations = set()
    for path, path_item in spec['paths'].items():
        for method in ('get', 'post', 'put', 'patch', 'delete'):
            operation = path_item.get(method)
            if not isinstance(operation, dict):
                continue
            parameter_refs = {
                item.get('$ref')
                for item in operation.get('parameters', [])
                if isinstance(item, dict)
            }
            if '#/components/parameters/Cursor' not in parameter_refs:
                continue
            operation_name = f'{method.upper()} {path}'
            cursor_operations.add(operation_name)
            if '#/components/parameters/Limit' not in parameter_refs:
                print(f'{operation_name} cursor pagination must also use Limit.', file=sys.stderr)
                sys.exit(1)
            if '400' not in operation.get('responses', {}):
                print(f'{operation_name} must expose 400 for invalid cursor scope or syntax.', file=sys.stderr)
                sys.exit(1)
    if cursor_operations != expected_cursor_operations:
        print('Cursor operation inventory is stale; update the shared pagination contract.', file=sys.stderr)
        sys.exit(1)
    point_account_get = spec['paths']['/point-accounts/{accountId}']['get']
    point_account_parameter_refs = {
        parameter.get('$ref')
        for parameter in point_account_get.get('parameters', [])
        if isinstance(parameter, dict)
    }
    if point_account_parameter_refs != {
        '#/components/parameters/PointAccountId',
        '#/components/parameters/OptionalAccessReason',
    } or set(point_account_get.get('responses', {})) != {'200', '400', '401', '403', '404', '503'}:
        print('Point-account summary owner/operator read contract is incomplete.', file=sys.stderr)
        sys.exit(1)
    point_transaction_get = spec['paths']['/point-accounts/{accountId}/transactions']['get']
    point_transaction_parameter_refs = {
        parameter.get('$ref')
        for parameter in point_transaction_get.get('parameters', [])
        if isinstance(parameter, dict)
    }
    if point_transaction_parameter_refs != {
        '#/components/parameters/PointAccountId',
        '#/components/parameters/OptionalAccessReason',
        '#/components/parameters/Cursor',
        '#/components/parameters/Limit',
    } or set(point_transaction_get.get('responses', {})) != {'200', '400', '401', '403', '404', '503'}:
        print('Point-transaction owner/operator cursor contract is incomplete.', file=sys.stderr)
        sys.exit(1)
    optional_access_reason = parameters['OptionalAccessReason']
    if (
        optional_access_reason.get('in') != 'header'
        or optional_access_reason.get('name') != 'X-Access-Reason'
        or optional_access_reason.get('required') is not False
        or 'PLATFORM_OPERATOR' not in optional_access_reason.get('description', '')
    ):
        print('Point-account support-read optional access-reason contract is incomplete.', file=sys.stderr)
        sys.exit(1)
    policy_get = spec['paths']['/operations/policies/expired-benefit-restoration']['get']
    policy_get_parameter_refs = {
        parameter.get('$ref')
        for parameter in policy_get.get('parameters', [])
        if isinstance(parameter, dict)
    }
    if policy_get_parameter_refs != {'#/components/parameters/AccessReason'}:
        print('Expired benefit policy GET must require the AccessReason header.', file=sys.stderr)
        sys.exit(1)
    access_reason = parameters['AccessReason']
    if (
        access_reason.get('in') != 'header'
        or access_reason.get('name') != 'X-Access-Reason'
        or access_reason.get('required') is not True
        or access_reason.get('schema', {}).get('type') != 'string'
        or set(policy_get.get('responses', {})) != {'200', '400', '401', '403', '503'}
    ):
        print('Expired benefit policy GET access-reason or failure contract is incomplete.', file=sys.stderr)
        sys.exit(1)
    dispute_operation = spec['paths']['/settlement-items/{itemId}/disputes']['post']
    if dispute_operation.get('tags') != ['Dispute']:
        print('Settlement dispute endpoint must be owned by the Dispute API tag.', file=sys.stderr)
        sys.exit(1)
    if 'base GET은 다섯 현재 head를' not in normalized_api_conventions:
        print('API conventions must describe the same five expired-benefit policy heads as OpenAPI.', file=sys.stderr)
        sys.exit(1)
    if (
        '`openapi/beanflow-v1-deployed.yaml`' not in normalized_api_conventions
        or '`openapi/beanflow-v1.yaml`' not in normalized_api_conventions
        or 'pre-release target' not in normalized_api_conventions
    ):
        print('API conventions must distinguish deployed and target OpenAPI sources.', file=sys.stderr)
        sys.exit(1)

    point_transaction = schemas['PointTransaction']
    point_transaction_types = set(point_transaction['properties']['type'].get('enum', []))
    expected_point_transaction_types = {
        'ACCRUAL',
        'USE',
        'EXPIRATION',
        'RESTORE',
        'COMPENSATION',
        'RESTORE_SKIPPED_EXPIRED',
        'RECOVERY',
        'ADJUSTMENT',
    }
    if point_transaction_types != expected_point_transaction_types:
        print('PointTransaction contract types are incomplete or excessive.', file=sys.stderr)
        print('Found:', sorted(point_transaction_types), file=sys.stderr)
        sys.exit(1)
    point_transaction_type_description = point_transaction['properties']['type'].get('description', '')
    point_transaction_amount_description = point_transaction['properties']['amountKrw'].get('description', '')
    if 'RECOVERY is an actual debit' not in point_transaction_type_description:
        print('PointTransaction must distinguish RECOVERY debit from PointRecoveryPending.', file=sys.stderr)
        sys.exit(1)
    if 'RECOVERY are negative' not in point_transaction_amount_description:
        print('PointTransaction amount must define the signed RECOVERY effect.', file=sys.stderr)
        sys.exit(1)
    if 'RESTORE_SKIPPED_EXPIRED is zero' not in point_transaction_amount_description:
        print('PointTransaction amount must define the zero skipped-restoration effect.', file=sys.stderr)
        sys.exit(1)
    if 'ADJUSTMENT follows its stored CREDIT or DEBIT balance effect' not in point_transaction_amount_description:
        print('PointTransaction amount must define the signed ADJUSTMENT effect.', file=sys.stderr)
        sys.exit(1)
    point_account_pending_description = schemas['PointAccount']['properties']['recoveryPendingKrw'].get('description', '')
    if 'PointRecoveryPending remaining amounts' not in point_account_pending_description:
        print('PointAccount recoveryPendingKrw must be defined as a pending summary.', file=sys.stderr)
        sys.exit(1)
    if 'updatedAt' in schemas['PointAccount'].get('required', []) or 'updatedAt' in schemas['PointAccount']['properties']:
        print('PointAccount must not expose an ungrounded updatedAt value.', file=sys.stderr)
        sys.exit(1)

    recovery_adr = (root / 'docs/adr/ADR-065-refund-earned-point-recovery-ledger.md').read_text(encoding='utf-8')
    required_recovery_adr_fragments = (
        'PointTransaction(type=RECOVERY)',
        'PointRecoveryPending',
        'loyalty_point_recovery_pending',
        'recovery_pending_krw',
        'PENDING -> SETTLED',
    )
    if not all(fragment in recovery_adr for fragment in required_recovery_adr_fragments):
        print('ADR-065 recovery ownership, state or DB contract is incomplete.', file=sys.stderr)
        sys.exit(1)
    event_catalog = (root / 'docs/architecture/event-catalog.md').read_text(encoding='utf-8')
    recovery_event_row = next(
        (line for line in event_catalog.splitlines() if line.startswith('| PointRecoveryPendingRecorded |')),
        '',
    )
    if not recovery_event_row.endswith('| PointRecoveryPending |'):
        print('PointRecoveryPendingRecorded must use PointRecoveryPending as its source of truth.', file=sys.stderr)
        sys.exit(1)
    plan13 = (
        root / 'docs/exec-plans/active/customer-order-cancellation-13-refund-earned-point-recovery-foundation.md'
    ).read_text(encoding='utf-8')
    if 'later-accrual offset' not in plan13:
        print('Plan 13 must own the accepted refund earned-point recovery foundation.', file=sys.stderr)
        sys.exit(1)
    required_plan10_issuer_fragments = (
        'issuer_type',
        '`issuer_reference`',
        'Verified precheck',
        '추정하거나 PLATFORM default로 backfill하지 않는다.',
    )
    plan10 = (
        root / 'docs/exec-plans/completed/customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md'
    ).read_text(encoding='utf-8')
    if not all(fragment in plan10 for fragment in required_plan10_issuer_fragments):
        print('Plan 10 must own the issuer snapshot migration and non-guessing precheck gate.', file=sys.stderr)
        sys.exit(1)

    adjustment_adr = (root / 'docs/adr/ADR-066-audited-loyalty-point-adjustment.md').read_text(encoding='utf-8')
    required_adjustment_adr_fragments = (
        '`POINT_ADJUSTMENT` permission',
        'issuer { issuerType, issuerReference }',
        'balance_effect',
        'POINT_ADJUSTMENT_INSUFFICIENT_AVAILABLE',
        'PointsAdjusted',
        'loyalty_point_adjustment_command_idempotency',
        'LoyaltyPointAdjustmentIdempotencyRetentionWorker',
    )
    if not all(fragment in adjustment_adr for fragment in required_adjustment_adr_fragments):
        print('ADR-066 audited point-adjustment contract is incomplete.', file=sys.stderr)
        sys.exit(1)

    adjustment_path = spec['paths']['/operations/point-accounts/{accountId}/adjustments']['post']
    adjustment_parameter_refs = {
        item.get('$ref')
        for item in adjustment_path.get('parameters', [])
        if isinstance(item, dict)
    }
    expected_adjustment_parameter_refs = {
        '#/components/parameters/PointAccountId',
        '#/components/parameters/IdempotencyKey',
    }
    if adjustment_parameter_refs != expected_adjustment_parameter_refs:
        print('Point adjustment must require account scope and Idempotency-Key.', file=sys.stderr)
        sys.exit(1)
    adjustment_request_ref = (
        adjustment_path['requestBody']['content']['application/json']['schema'].get('$ref')
    )
    if adjustment_request_ref != '#/components/schemas/PointAdjustmentRequest':
        print('Point adjustment request schema is incorrect.', file=sys.stderr)
        sys.exit(1)
    if set(adjustment_path.get('responses', {})) != {'201', '400', '401', '403', '404', '409', '503'}:
        print('Point adjustment response contract is incomplete.', file=sys.stderr)
        sys.exit(1)
    if (
        adjustment_path['responses']['201']['content']['application/json']['schema'].get('$ref')
        != '#/components/schemas/PointAdjustmentResult'
    ):
        print('Point adjustment success response schema is incorrect.', file=sys.stderr)
        sys.exit(1)
    adjustment_request = schemas['PointAdjustmentRequest']
    if set(adjustment_request.get('required', [])) != {'amountKrw', 'reason', 'evidenceReferences'}:
        print('Point adjustment required fields are incomplete.', file=sys.stderr)
        sys.exit(1)
    expected_nonblank_pattern = r'.*\S.*'
    adjustment_nonblank_fields = {
        'issuerReference': schemas['PointIssuer']['properties']['issuerReference'],
        'reason': adjustment_request['properties']['reason'],
        'evidence reference': adjustment_request['properties']['evidenceReferences']['items'],
    }
    if any(field.get('pattern') != expected_nonblank_pattern for field in adjustment_nonblank_fields.values()):
        print('Point adjustment issuer, reason and evidence references must reject blank values.', file=sys.stderr)
        sys.exit(1)
    if adjustment_request['properties']['amountKrw'].get('not', {}).get('const') != 0:
        print('Point adjustment amount must reject zero.', file=sys.stderr)
        sys.exit(1)
    adjustment_branches = adjustment_request.get('allOf', [])
    if len(adjustment_branches) != 1:
        print('Point adjustment must have exactly one positive/negative conditional branch.', file=sys.stderr)
        sys.exit(1)
    adjustment_branch = adjustment_branches[0]
    if set(adjustment_branch.get('then', {}).get('required', [])) != {'issuer', 'expiresAt'}:
        print('Positive point adjustment must require issuer and expiresAt.', file=sys.stderr)
        sys.exit(1)
    adjustment_negative_forbidden = {
        tuple(branch.get('required', []))
        for branch in adjustment_branch.get('else', {}).get('not', {}).get('anyOf', [])
    }
    if adjustment_negative_forbidden != {('issuer',), ('expiresAt',)}:
        print('Negative point adjustment must forbid issuer and expiresAt.', file=sys.stderr)
        sys.exit(1)
    adjustment_issuer_types = set(schemas['PointIssuer']['properties']['issuerType'].get('enum', []))
    if adjustment_issuer_types != {'PLATFORM', 'BRAND', 'STORE'}:
        print('Point adjustment issuer types are incomplete.', file=sys.stderr)
        sys.exit(1)
    adjustment_result = schemas['PointAdjustmentResult']
    if set(adjustment_result.get('required', [])) != {'account', 'transactions'}:
        print('Point adjustment result fields are incomplete.', file=sys.stderr)
        sys.exit(1)
    if adjustment_result['properties']['account'].get('$ref') != '#/components/schemas/PointAccount':
        print('Point adjustment result must return the changed PointAccount.', file=sys.stderr)
        sys.exit(1)
    if adjustment_result['properties']['transactions']['items'].get('$ref') != '#/components/schemas/PointTransaction':
        print('Point adjustment result must return PointTransaction entries.', file=sys.stderr)
        sys.exit(1)
    adjustment_plan = (
        root / 'docs/exec-plans/active/loyalty-point-adjustment-foundation.md'
    ).read_text(encoding='utf-8')
    adjustment_plan_requirements = (
        'Plan 10 issuer precheck evidence',
        'PointsAdjusted',
        'loyalty_point_adjustment_command_idempotency',
        'LoyaltyPointAdjustmentIdempotencyRetentionWorker',
    )
    if not all(fragment in adjustment_plan for fragment in adjustment_plan_requirements):
        print('Point adjustment active ExecPlan is missing migration, retention or event completion criteria.', file=sys.stderr)
        sys.exit(1)
    adjustment_event_row = next(
        (line for line in event_catalog.splitlines() if line.startswith('| PointsAdjustedV1 |')),
        '',
    )
    if not adjustment_event_row.endswith('| PointTransaction |'):
        print('PointsAdjustedV1 must use PointTransaction as its source of truth.', file=sys.stderr)
        sys.exit(1)
    if 'Analytics PointsAdjustedV1 consumer' in adjustment_plan:
        print('Point-adjustment plan must not own the Analytics PointsAdjustedV1 consumer.', file=sys.stderr)
        sys.exit(1)
    analytics_plan = (
        root / 'docs/exec-plans/active/analytics-refund-and-late-event-projection.md'
    ).read_text(encoding='utf-8')
    if 'consumer는 Analytics plan만 구현' not in analytics_plan:
        print('Analytics plan must own the PointsAdjustedV1 consumer checkpoint.', file=sys.stderr)
        sys.exit(1)

    settlement_input_adr = (
        root / 'docs/adr/ADR-071-settlement-input-snapshot-foundation.md'
    ).read_text(encoding='utf-8')
    required_settlement_input_fragments = (
        'StoreSettlementTerms',
        'OrderSettlementInputSnapshot',
        'feeBaseKrw',
        'SETTLEMENT_INPUT_UNAVAILABLE',
        'netSettlementKrw',
    )
    if not all(fragment in settlement_input_adr for fragment in required_settlement_input_fragments):
        print('Settlement-input source/materialization ADR is incomplete.', file=sys.stderr)
        sys.exit(1)
    plan15 = (
        root / 'docs/exec-plans/active/customer-order-cancellation-15-settlement-input-snapshot-foundation.md'
    ).read_text(encoding='utf-8')
    plan20 = (
        root / 'docs/exec-plans/active/customer-order-cancellation-20-settlement-foundation.md'
    ).read_text(encoding='utf-8')
    plan16 = (
        root / 'docs/exec-plans/active/customer-order-cancellation-16-immutable-refund-and-loyalty-event-producer.md'
    ).read_text(encoding='utf-8')
    normalized_plan15 = re.sub(r'\s+', ' ', plan15)
    normalized_plan20 = re.sub(r'\s+', ' ', plan20)
    normalized_plan16 = re.sub(r'\s+', ' ', plan16)
    required_plan15_ownership = (
        'payload factory 또는 typed mapper',
        'validator와 contract fixture',
        'actual outbox producer 교체와 activation은 Plan 20이 소유한다.',
        'V2 producer activation, Settlement consumer와 V1 publication drain/deployment gate',
    )
    if not all(fragment in normalized_plan15 for fragment in required_plan15_ownership):
        print('Plan 15 must own only immutable V2 input/factory/validator/fixture handoff.', file=sys.stderr)
        sys.exit(1)
    if '`OrderCompletedV2` outbox를 함께 저장한다' in normalized_plan15:
        print('Plan 15 must not reclaim the V2 completion outbox transaction.', file=sys.stderr)
        sys.exit(1)
    required_plan20_ownership = (
        'incomplete `OrderCompletedV1` publication/deployed V1 consumer gate',
        '`OrderCompletedV1 -> OrderCompletedV2` cutover',
        'Ordering guarded completion transaction의 V2 outbox 저장/activation',
        'Ordering producer transaction과 Settlement consumer transaction은 같은 event를 다루더라도 별도의 local transaction이다.',
        'Plan 15의 snapshot materialization, payload input 재계산 또는 Merchant/Campaign/PointLot 최신 state 조회',
    )
    if not all(fragment in normalized_plan20 for fragment in required_plan20_ownership):
        print('Plan 20 must own V2 cutover/outbox/consumer and reject snapshot re-materialization.', file=sys.stderr)
        sys.exit(1)
    required_plan16_boundary = (
        '`OrderCompletedV2`의 Ordering completion producer/cutover와 Settlement consumer는 Plan 20 소유다.',
        '이 plan은 refund/Loyalty result event만 생산하며 Order completion event를 생산하거나 그 outbox를 저장하지 않는다.',
    )
    if not all(fragment in normalized_plan16 for fragment in required_plan16_boundary):
        print('Plan 16 must be limited to Refund/Loyalty producers, not OrderCompletedV2.', file=sys.stderr)
        sys.exit(1)
    event_catalog_contract = re.sub(
        r'\s+', ' ', (root / 'docs/architecture/event-catalog.md').read_text(encoding='utf-8')
    )
    transaction_boundaries_contract = re.sub(
        r'\s+', ' ', (root / 'docs/architecture/transaction-boundaries.md').read_text(encoding='utf-8')
    )
    normalized_adr068 = re.sub(
        r'\s+', ' ', (root / 'docs/adr/ADR-068-immutable-integration-event-snapshots.md').read_text(encoding='utf-8')
    )
    normalized_adr071 = re.sub(r'\s+', ' ', settlement_input_adr)
    ownership_contract_fragments = (
        'Plan 15 owns the Merchant terms, Campaign burden, PointLot issuer source/materialization plus the V2 payload factory/validator/contract fixture.',
        '`OrderCompletedV1 -> OrderCompletedV2` cutover와 Ordering guarded completion transaction의 V2 outbox 저장/activation',
        'The Ordering producer transaction and Settlement consumer transaction are separate local transactions;',
        'Plan 15는 completion transaction이 사용할 immutable input, `OrderCompletedV2` payload factory 또는 typed mapper, validator와 contract fixture를 제공한다.',
        'Settlement consumer는 Ordering producer와 별도의 local transaction에서 immutable event payload와 source unique를 검증하고',
    )
    ownership_contract_sources = (
        event_catalog_contract,
        normalized_plan20,
        normalized_adr068,
        normalized_adr071,
        transaction_boundaries_contract,
    )
    if not all(
        fragment in source
        for fragment, source in zip(ownership_contract_fragments, ownership_contract_sources)
    ):
        print('ADR, architecture and ExecPlan OrderCompletedV2 ownership contracts are inconsistent.', file=sys.stderr)
        sys.exit(1)
    cursor_adr = (root / 'docs/adr/ADR-070-signed-cursor-and-pagination-contract.md').read_text(encoding='utf-8')
    required_cursor_fragments = (
        '(distanceMicrometers ASC, storeId ASC)',
        '`1..10000`',
        'stripTrailingZeros()',
        'signed-cursor foundation',
        '`endpoint`, `filterHash`, `sort`, `issuedAt`, `expiresAt`',
        '위 다섯 property 외의 property와 `null`은 허용하지 않는다.',
        'JSON string array',
        'lowercase canonical UUID string',
        '64자리 lowercase hexadecimal string',
        'JSON integer',
        'padding 없는 Base64URL',
        '`v1.<key-id>.<encoded-payload>` 문자열의 UTF-8 bytes',
        '`now >= expiresAt`',
        '최대 `2048`자',
        'active-key-id: current',
        '`keys`는 duplicate key ID를 검출할 수 있는 list다.',
        'secret-base64-url: ${BEANFLOW_CURSOR_HMAC_CURRENT_KEY}',
        '최소 32 bytes',
        'source, 기본 설정, production 또는 local runtime configuration에 fallback secret을 넣지 않는다.',
        '공개된 test-vector 전용 key material',
        '이름과 주석으로 test-vector 전용임을 표시한다.',
        'production 또는 local runtime configuration에서 선택할 수 없고',
        '실제 deployment secret과 같은 environment variable 이름을 사용하지 않는다',
        'test result와 log에 key material을 출력하지 않으며',
        '운영 fallback으로 사용할 수 없다',
    )
    normalized_cursor_adr = re.sub(r'\s+', ' ', cursor_adr)
    if not all(fragment in normalized_cursor_adr for fragment in required_cursor_fragments):
        print('Signed-cursor canonical payload, key-ring or test-vector contract is incomplete.', file=sys.stderr)
        sys.exit(1)
    cursor_plan = (root / 'docs/exec-plans/completed/signed-cursor-foundation.md').read_text(encoding='utf-8')
    required_cursor_plan_fragments = (
        '### Fixed v1 wire and key contract',
        '`endpoint`, `filterHash`, `sort`, `issuedAt`, `expiresAt`',
        'JSON string array',
        'padding 없는 Base64URL',
        '`now >= expiresAt`',
        '`2048`자를 넘을 수 없다',
        '`secret-base64-url`',
        '최소 32 bytes',
        'public test-vector key',
        'never a runtime key',
    )
    normalized_cursor_plan = re.sub(r'\s+', ' ', cursor_plan)
    if not all(fragment in normalized_cursor_plan for fragment in required_cursor_plan_fragments):
        print('Signed-cursor ExecPlan is missing the locked v1 wire/key/test-vector handoff.', file=sys.stderr)
        sys.exit(1)
    operator_permission_adr = (
        root / 'docs/adr/ADR-069-operator-permission-grants-and-audited-policy-read.md'
    ).read_text(encoding='utf-8')
    required_operator_permission_fragments = (
        'POINT_ACCOUNT_READ',
        'operator-permission-bootstrap',
        'verified release principal',
        '단기 OIDC workload identity',
        'Plan 11만 네 값의 closed permission vocabulary와 grant',
        '`grant`, `revoke`, `regrant`',
    )
    if not all(fragment in operator_permission_adr for fragment in required_operator_permission_fragments):
        print('Operator permission bootstrap/read contract is incomplete.', file=sys.stderr)
        sys.exit(1)
    plan11 = (
        root / 'docs/exec-plans/completed/customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md'
    ).read_text(encoding='utf-8')
    normalized_plan11 = re.sub(r'\s+', ' ', plan11)
    required_plan11_decisions = (
        '`POINT_ACCOUNT_READ`',
        '`POINT_ADJUSTMENT`',
        'PATCH는 existing body의 non-blank `reason`만 적용한다.',
        'OIDC workload identity',
        '> **Implementation-Ready:** `true`',
    )
    if not all(fragment in normalized_plan11 for fragment in required_plan11_decisions):
        print('Plan 11 permission, PATCH reason or workload-identity contract is incomplete.', file=sys.stderr)
        sys.exit(1)
    if 'PATCH는 existing reason/evidence contract' in normalized_plan11:
        print('Plan 11 must not require evidence in the expired-benefit policy PATCH body.', file=sys.stderr)
        sys.exit(1)
    plan14 = (
        root / 'docs/exec-plans/active/customer-order-cancellation-14-point-account-read-vertical-slice.md'
    ).read_text(encoding='utf-8')
    if 'vocabulary constraint만 필요한 forward migration' in plan14:
        print('Plan 14 must consume Plan 11 vocabulary without another grant migration.', file=sys.stderr)
        sys.exit(1)
    required_plan14_decisions = (
        'customer-order-cancellation-13-refund-earned-point-recovery-foundation.md',
        'idx_point_transaction_account_occurred_id',
        'point_account_id, occurred_at DESC, id DESC',
        'Account `updated_at` column 또는 임의 timestamp backfill',
        '`recoveryPendingKrw`는 Plan 13 Account summary의 실제 non-negative 값',
        '> **Implementation-Ready:** `false`',
        '> **Writes-Migration:** `true`',
    )
    if not all(fragment in plan14 for fragment in required_plan14_decisions):
        print('Plan 14 dependency, index, projection or readiness contract is incomplete.', file=sys.stderr)
        sys.exit(1)
    if 'permission vocabulary migration/enforcement' in plan14:
        print('Plan 14 must not claim the Plan 11 permission vocabulary migration.', file=sys.stderr)
        sys.exit(1)
    nearby_plan = (
        root / 'docs/exec-plans/active/nearby-store-discovery.md'
    ).read_text(encoding='utf-8')
    required_nearby_decisions = (
        'merchant_store_discovery_profile',
        'PostgreSQL 17/PostGIS 3.5',
        'application startup을 실패',
        'Discovery/Controller는 Merchant Entity나 Repository를 직접 호출하지 않는다',
        '> **Depends-On:** `docs/exec-plans/completed/signed-cursor-foundation.md`',
    )
    if not all(fragment in nearby_plan for fragment in required_nearby_decisions):
        print('Nearby StoreDiscoveryProfile, PostGIS gate or cursor dependency is incomplete.', file=sys.stderr)
        sys.exit(1)
    normalized_nearby_plan = re.sub(r'\s+', ' ', nearby_plan)
    if any(fragment in normalized_nearby_plan for fragment in (
        '`merchant_store`에 non-sensitive',
        '`merchant_store`에 `name`',
        '`merchant_store` table에 name',
    )):
        print('Nearby must not add discovery name/location directly to merchant_store.', file=sys.stderr)
        sys.exit(1)
    payment_refunded_row = next(
        (line for line in (root / 'docs/architecture/event-catalog.md').read_text(encoding='utf-8').splitlines()
         if line.startswith('| PaymentRefundedV1 |')),
        '',
    )
    if '| Loyalty, Settlement, Analytics |' not in payment_refunded_row or 'Ordering' in payment_refunded_row:
        print('PaymentRefundedV1 must not declare Ordering as a consumer.', file=sys.stderr)
        sys.exit(1)
    idempotency_adr = (
        root / 'docs/adr/ADR-032-customer-cancellation-idempotency.md'
    ).read_text(encoding='utf-8')
    if '실제 Flyway 번호는 ADR-072의 migration-writer lease' not in re.sub(r'\s+', ' ', idempotency_adr):
        print('ADR-032 must use ADR-072 branch-time migration numbering.', file=sys.stderr)
        sys.exit(1)
    plan30 = (
        root / 'docs/exec-plans/active/customer-order-cancellation-30-order-compensation-foundation.md'
    ).read_text(encoding='utf-8')
    stable_listener_ids = (
        'beanflow.order-compensation.order-rejected.payment.v1',
        'beanflow.order-compensation.order-rejected.pickup.v1',
        'beanflow.order-compensation.order-rejected.stock.v1',
        'beanflow.order-compensation.order-rejected.coupon.v1',
        'beanflow.order-compensation.order-rejected.points.v1',
        'beanflow.order-compensation.order-rejected.customer-notification.v1',
        'beanflow.order-compensation.order-cancelled.pickup.v1',
        'beanflow.order-compensation.order-cancelled.stock.v1',
        'beanflow.order-compensation.order-cancelled.coupon.v1',
        'beanflow.order-compensation.order-cancelled.points.v1',
    )
    if not all(listener_id in plan30 for listener_id in stable_listener_ids):
        print('Plan 30 stable listener-target-to-step registry is incomplete.', file=sys.stderr)
        sys.exit(1)
    if 'PUBLICATION_TARGET_UNMAPPED' not in plan30:
        print('Plan 30 must fail closed for an unknown publication target.', file=sys.stderr)
        sys.exit(1)
    ownership_amendment_sources = (
        root / 'docs/adr/ADR-041-trigger-and-benefit-scoped-restoration-policy.md',
        root / 'docs/adr/ADR-061-refund-requested-and-confirmed-amounts.md',
    )
    ownership_amendment_fragments = (
        'Plan 11이 composite policy 저장소/API와 다섯 head seed를 단독 구현한다.',
        'Plan 12가 부분 환불 allocation과 공개 Refund 계약을',
    )
    for source, fragment in zip(ownership_amendment_sources, ownership_amendment_fragments):
        if fragment not in re.sub(r'\s+', ' ', source.read_text(encoding='utf-8')):
            print(f'ExecPlan ownership amendment is missing from {source}.', file=sys.stderr)
            sys.exit(1)
    execution_adr = (
        root / 'docs/adr/ADR-072-execplan-unattended-execution-and-migration-lane.md'
    ).read_text(encoding='utf-8')
    required_execution_fragments = (
        'migration-writer lease',
        'Plan 40은 latest main base의 **Draft PR**',
        'Plan 40→50은 하나의 Draft stack과 하나의 migration-writer lease',
        '모든 direct successor의 `Depends-On` path를 새 completed path로 갱신',
    )
    if not all(fragment in execution_adr for fragment in required_execution_fragments):
        print('Unattended execution/migration lane ADR is incomplete.', file=sys.stderr)
        sys.exit(1)

    policy_patch = spec['paths'][
        '/operations/policies/expired-benefit-restoration/{trigger}/{benefitType}'
    ]['patch']
    policy_patch_parameters = {
        parameter.get('name'): parameter
        for parameter in policy_patch.get('parameters', [])
        if isinstance(parameter, dict) and parameter.get('name')
    }
    expected_policy_triggers = {
        'STORE_REJECTION', 'CUSTOMER_CANCELLATION', 'PARTIAL_REFUND'
    }
    if set(policy_patch_parameters['trigger']['schema'].get('enum', [])) != expected_policy_triggers:
        print('Expired benefit policy PATCH trigger enum is incomplete.', file=sys.stderr)
        sys.exit(1)
    if '404' not in policy_patch.get('responses', {}):
        print('Expired benefit policy PATCH must reject the absent partial-refund coupon key.', file=sys.stderr)
        sys.exit(1)

    policy_schema = schemas['ExpiredBenefitRestorationPolicy']
    if set(policy_schema['properties']['trigger'].get('enum', [])) != expected_policy_triggers:
        print('ExpiredBenefitRestorationPolicy trigger enum is incomplete.', file=sys.stderr)
        sys.exit(1)
    partial_refund_policy_rules = [
        branch for branch in policy_schema.get('allOf', [])
        if branch.get('if', {}).get('properties', {}).get('trigger', {}).get('const')
        == 'PARTIAL_REFUND'
    ]
    if len(partial_refund_policy_rules) != 1:
        print('ExpiredBenefitRestorationPolicy must have one PARTIAL_REFUND key rule.', file=sys.stderr)
        sys.exit(1)
    if (
        partial_refund_policy_rules[0].get('then', {}).get('properties', {})
        .get('benefitType', {}).get('const')
        != 'POINTS'
    ):
        print('PARTIAL_REFUND policy must be restricted to POINTS.', file=sys.stderr)
        sys.exit(1)

    expected_termination_triggers = {'STORE_REJECTION', 'CUSTOMER_CANCELLATION'}
    for schema_name in ('StoreCompensationSummary', 'CompensationSummary'):
        actual_triggers = set(
            schemas[schema_name]['properties']['trigger'].get('enum', [])
        )
        if actual_triggers != expected_termination_triggers:
            print(
                f'{schema_name} must remain restricted to order-termination triggers.',
                file=sys.stderr,
            )
            sys.exit(1)

    unresolved_states = (
        '`REQUESTED`, `PROCESSING`, `RETRY_SCHEDULED`, `UNKNOWN`, '
        '`RECONCILING`, `MANUAL_REVIEW`'
    )
    unresolved_sources = [
        'docs/product/business-policy-decisions.md',
        'docs/adr/ADR-031-customer-cancellation-api-contract.md',
        'docs/adr/ADR-036-cancellation-after-partial-refund.md',
        'docs/api/error-catalog.md',
        'docs/architecture/transaction-boundaries.md',
    ]
    for source in unresolved_sources:
        text = re.sub(r'\s+', ' ', (root / source).read_text(encoding='utf-8'))
        if unresolved_states not in text:
            print(
                f'{source} must list the same six unresolved prior-Refund states '
                f'as the cancellation OpenAPI contract.',
                file=sys.stderr,
            )
            sys.exit(1)

    print(
        f'OpenAPI YAML and local contract checks passed '
        f'(target {len(actual_paths)} paths, deployed {len(deployed_spec["paths"])} paths, '
        f'{len(spec["components"]["schemas"])} schemas).'
    )

print(
    f'Validated {len(ids)} business policies, {len(adr_files)} ADRs, '
    f'{len(markdown_files)} Markdown files, and {len(plan_metadata)} ExecPlans.'
)
PY

echo "Document verification completed."
