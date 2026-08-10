# ADR-087: R0-R4 field risk와 목적별 profile change

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

Customer/Store/Rider profile 모델이 불완전하고 범용 PATCH는 필드마다 다른 인증·승인·소유권·통지를 표현하지 못한다.

## Decision

R0 system fact는 adjustment/reconciliation만, R1은 BASIC+permission+Audit 후보, R2는 ENHANCED+Specialist, identity/financial/ownership 영향은 R3로 승격한다. R3는 Support Manager+Operations 순차 승인과 agent execution return, R4 secret은 조회/직접 변경 없이 reset/re-registration만 허용한다. Identity/Merchant/Delivery owner가 목적별 typed commands와 history를 소유하며 old/new channel notification을 시도한다.

## Alternatives Considered

- Support-owned JSON profile: source-of-truth divergence로 기각.
- generic PATCH와 field allowlist: workflow/approval 차이를 숨겨 기각.
- R3를 Operations가 직접 실행: 상담 lineage와 separation을 깨서 기각.

## Rationale

Field sensitivity와 business ownership을 API와 approval granularity에 일치시킨다.

## Consequences

S30이 최소 owner profile model을 포함해야 하며 model gap은 `BLOCKED_BY_MODEL_GAP`이다. notification 실패는 change rollback이 아닌 retry/warning이다.

## Verification

R-class matrix, purpose endpoints, new-contact-only denial, three-actor separation, owner version race와 notification failure.

## Metrics

Class/action별 request/approval/outcome와 post-change notification failure.

## Revisit Conditions

새 profile field, auth method, payout/legal requirement 또는 rider ownership model 확정.

## Related Decisions

ADR-027, ADR-069, ADR-084.
