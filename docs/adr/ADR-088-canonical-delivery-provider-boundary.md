# ADR-088: canonical DeliveryFulfillment, Provider ACL과 reconciliation

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

Support delivery inquiry에는 delivery state와 rider/Provider evidence가 필요하지만 자체 rider platform은 비목표이며 Provider webhook은 duplicate/out-of-order/timeout을 만든다.

## Decision

Delivery Context가 provider-independent `DeliveryFulfillment`, DispatchAttempt, minimal AssignmentSnapshot, Incident, Inbox와 ReconciliationCase를 소유한다. Provider별 anti-corruption ports가 상태/error를 번역한다. Raw-body auth 뒤 unique Inbox를 commit하고 2xx한 후 worker가 monotonic state를 적용한다. Timeout은 UNKNOWN/RECONCILING이며 prior provider absence/cancel 확정 전 silent cross-provider failover를 금지한다. 주소/contact/location/raw payload는 최소 범위와 짧은 retention을 적용한다.

## Alternatives Considered

- Provider state를 Order에 저장: ownership/enum leakage로 기각.
- webhook 즉시 적용 후 ACK: durability/duplicate 위험으로 기각.
- timeout 시 다른 Provider dispatch: 이중 배차 위험으로 기각.
- full rider platform: 제품 비목표.

## Rationale

운송 실행은 외부에 두고 BeanFlow 거래·지원에 필요한 canonical facts만 보존한다.

## Consequences

Provider contract tests, reconciliation operations와 privacy retention이 필요하다. 외부 호출은 long DB transaction 밖이다.

## Verification

Mapping, provider별 raw-body signature/authentication과 replay 방지, inbox duplicate/order, missing field, timeout lookup,
success/DB failure, no failover, location/contact expiry. 구체 인증 algorithm/header는 Provider 선택 뒤 typed contract로 확정한다.

## Metrics

Dispatch/webhook/reconciliation/incident outcomes와 sync stale duration; PII ID label 금지.

## Revisit Conditions

실제 Provider 선정, multi-provider routing 요구 또는 자체 rider scope 변경.

## Related Decisions

ADR-003, ADR-006, ADR-009, ADR-019, ADR-020.
