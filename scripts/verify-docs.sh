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
  "docs/quality/customer-order-cancellation-readiness.md"
  "docs/quality/customer-order-cancellation-release-evidence.md"
  "docs/decisions/customer-order-cancellation-decision-closure.md"
  "docs/exec-plans/active/customer-order-cancellation-and-recovery.md"
  "docs/exec-plans/active/customer-order-cancellation-00-contract-baseline.md"
  "docs/exec-plans/active/customer-order-cancellation-10-partial-refund-allocation-foundation.md"
  "docs/exec-plans/active/customer-order-cancellation-20-settlement-foundation.md"
  "docs/exec-plans/active/customer-order-cancellation-30-order-compensation-foundation.md"
  "docs/exec-plans/active/customer-order-cancellation-40-command.md"
  "docs/exec-plans/active/customer-order-cancellation-50-recovery.md"
  "docs/exec-plans/active/ci-pr-validation.md"
  "docs/review/code-review.md"
  "docs/exec-plans/completed/foundation-domain-model.md"
  "openapi/beanflow-v1.yaml"
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

traceability = (root / 'docs/architecture/policy-traceability.md').read_text(encoding='utf-8')
br14_row = next((line for line in traceability.splitlines() if line.startswith('| BR-14 |')), '')
if 'Blocked by' not in br14_row:
    print('BR-14 traceability must expose its implementation prerequisites.', file=sys.stderr)
    sys.exit(1)

readiness = (root / 'docs/quality/customer-order-cancellation-readiness.md').read_text(encoding='utf-8')
if 'CLEAN_CUTOVER_GATE = PASSED' not in readiness:
    print('Customer cancellation readiness must record the evidenced clean-cutover gate result.', file=sys.stderr)
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
        with (root / 'openapi/beanflow-v1.yaml').open(encoding='utf-8') as f:
            spec = yaml.safe_load(f)
        validate(spec)
    except Unresolvable as exc:
        print(f'OpenAPI 3.1 validation failed: unresolved reference {exc.ref}', file=sys.stderr)
        sys.exit(1)
    except (yaml.YAMLError, OpenAPIValidationError, TypeError, ValueError) as exc:
        print(f'OpenAPI 3.1 validation failed: {exc}', file=sys.stderr)
        sys.exit(1)
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

    cancellation = spec['components']['schemas']['Cancellation']
    if cancellation['properties']['orderState'].get('const') != 'CANCELLED':
        print('Cancellation success must expose orderState=CANCELLED.', file=sys.stderr)
        sys.exit(1)

    cancellation_detail = spec['components']['schemas']['CancellationRequest']['properties']['detail']
    if 'Control characters are rejected' not in cancellation_detail.get('description', ''):
        print('Cancellation detail normalization/control-character contract is missing.', file=sys.stderr)
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
        f'({len(actual_paths)} paths, '
        f'{len(spec["components"]["schemas"])} schemas).'
    )

print(
    f'Validated {len(ids)} business policies, {len(adr_files)} ADRs, '
    f'and {len(markdown_files)} Markdown files.'
)
PY

echo "Document verification completed."
