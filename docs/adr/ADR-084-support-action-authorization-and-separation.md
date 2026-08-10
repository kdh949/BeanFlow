# ADR-084: risk-based Support action, exact approval binding과 Operations handoff

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

역할 하나로 cancellation, compensation, PII와 profile change를 허용할 수 없고 고위험 승인도 payload/version과 분리되면 stale 실행·자기승인이 가능하다.

## Decision

서버 ActionPolicy는 role+persistent grant+Case/relationship+verification+target state/version+amount/history를 평가해 ALLOWED/APPROVAL_REQUIRED/DENIED를 반환하며 unknown은 DENIED다. Approval은 exact revision/payload hash/verification/policy/aggregate version/amount/evidence/expiry에 bind한다. R3는 distinct requester→Support Manager→Operations→distinct eligible agent execution이다. Exceptional compensation은 Support Manager가 아니라 Operations investigation으로 이관되고 decision은 payload 수정 없이 agent에게 반환된다.

## Alternatives Considered

- UI/role boolean: every-request/object authorization을 만족하지 못해 기각.
- 범용 rules engine: 초기 정책 검증/운영 비용이 과도해 기각.
- 승인자가 payload 수정/실행: audit lineage와 separation을 깨서 기각.

## Rationale

정책 설명 가능성, stale 방지와 조직 간 견제를 한 immutable request lineage로 묶는다.

## Consequences

승인 시간이 늘고 revision/reassignment 상태가 필요하다. UI decision은 안내일 뿐 실행 시 재평가한다.

## Verification

Role matrix, same actor/step/execute DB+service checks, stale/revoke/version race, changed payload 409, Operations return flow.

## Metrics

Decision 분포, approval duration/return/stale/separation denial, investigation outcome.

## Revisit Conditions

실제 승인 bottleneck, fraud pattern 또는 policy change frequency가 typed policy 한계를 증명할 때.

## Related Decisions

ADR-053, ADR-064, ADR-069.
