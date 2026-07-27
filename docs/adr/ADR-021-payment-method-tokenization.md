# ADR-021: 결제수단 tokenization과 저장 금지 데이터

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

BR-29는 BeanFlow가 원본 카드번호, CVC와 전체 유효기간을 저장하지 않고 PG token
reference만 사용하도록 정한다.

## Decision

- PaymentMethod에는 provider, provider token reference, memberId, 표시용 별칭,
  카드 브랜드와 마지막 4자리만 저장한다.
- 원본 PAN, CVC와 전체 유효기간은 API schema, Entity, log, trace, AuditRecord와
  test fixture에 두지 않는다.
- member/provider/token reference를 Unique Constraint로 보호한다.
- 다른 member의 token 사용과 폐기된 token 사용을 Payment Application Service에서
  객체 수준 인가와 상태로 거부한다.
- 필수 Provider credential이 없거나 운영 profile에서 mock Provider가 선택되면
  startup을 실패시킨다.

## Alternatives Considered

- 카드 원문 직접 저장
- token reference만 저장하고 소유권 미검증
- Provider tokenization과 최소 표시 메타데이터

## Rationale

민감 결제정보 저장 책임을 피하면서 사용자에게 필요한 결제수단 식별 정보를 제공한다.

## Consequences

- 실제 Provider의 token 수명과 폐기 callback 계약이 필요하다.
- Provider 장애 시 임의 local token으로 대체할 수 없다.

## Verification

- 다른 사용자의 token 사용 거부
- 민감 필드 이름과 값이 schema/log에 없음
- token 중복 등록과 폐기 상태
- production profile mock startup failure

## Metrics

- **Not measured:** Provider token 수명과 API latency

## Revisit Conditions

실제 PG sandbox 계약, 규제·인증 범위 또는 network token이 도입될 때

## Related Decisions

- BR-29
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
