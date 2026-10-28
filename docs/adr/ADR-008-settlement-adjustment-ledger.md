# ADR-008: 확정 정산 조정 원장

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

확정 정산 뒤 환불·이의제기가 발생할 수 있다. 과거 결과를 수정하면 감사와 재현이 어렵다.

## Decision

확정된 Batch·Item은 그대로 두고 환불·이의제기 후 변경된 금액은 SettlementAdjustment에 기록해 다음 정산에서 상계한다.

매장 수락 전에 고객이 주문을 취소하면 SettlementItem이 생성되지 않는다(ADR-048).
해당 환불은 정산 조정 대신 append-only AuditRecord에 `NOT_APPLICABLE`로 기록한다.

## Alternatives Considered

- 과거 정산 재계산·덮어쓰기
- 전체 배치 취소 후 재생성
- 불변 원장과 Adjustment

## Rationale

확정 정산을 그대로 남기고 이후 조정 내역을 추가하므로, 과거 정산 금액과 변경 사유를 함께 조회할 수 있다.

## Consequences

- 현재 유효 금액 계산에 원장 합산이 필요하다.
- 음수 이월 정책이 필요하다.

## Verification

- 확정 후 부분·전액 환불
- 중복 Adjustment 방지
- 다음 배치 상계 tie-out
- **2026-08-03 정산 입력 처리:** V21과 Settlement consumer는 `COMPLETED` source만 immutable
  Item으로 만들고, 고객 취소 Refund에는 Item/Adjustment 없이 실제 Order/Refund evidence와
  source-unique `SETTLEMENT_REFUND_EXCLUDED` Audit만 남긴다. existing Item, source mismatch와
  Audit rollback은 성공 처리되지 않는 통합 테스트로 검증했다.
- **2026-08-03 정산 처리:** V28~V29와 Settlement Application Service는
  confirmed Item/Batch에만 append-only Adjustment를 허용하고 source/reason unique와 mutation
  trigger로 중복·변경을 막는다. completed Refund와 accepted Dispute는 각각 stable source로
  하나의 Adjustment, Audit와 persistent event에 수렴한다. 계산 시각 기반 ingestion
  high-watermark와 이전 confirmed Batch의 negative carry source를 summary에 고정해 늦게 생성된
  Adjustment를 다음 Batch에서 한 번만 소비한다. 500건 keyset tie-out, 연속 음수 이월,
  exact replay와 conflict failure를 PostgreSQL 통합 테스트로 검증했다.

## Metrics

- **Measured (2026-08-03, single local run):** PostgreSQL 17.6에서 정산 항목 1,000개를 500개씩 처리한 단일 로컬 측정 결과는 계산 36.054ms, 확정 17.260ms이다.

## Revisit Conditions

실제 지급·채권 관리와 외부 회계 요구가 도입될 때

## Related Decisions

- BR-21, BR-23
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
- [ADR-018](ADR-018-settlement-dispute-hold-and-refile.md)
