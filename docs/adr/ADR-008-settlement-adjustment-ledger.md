# ADR-008: 확정 정산 조정 원장

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

확정 정산 뒤 환불·이의제기가 발생할 수 있다. 과거 결과를 수정하면 감사와 재현이 어렵다.

## Decision

확정 Batch와 Item은 불변으로 유지하고 이후 변경은 SettlementAdjustment로 기록하여 다음 정산에 상계한다.

ADR-048에 따라 매장 수락 전 고객 취소는 `COMPLETED`되지 않아 SettlementItem이
없으므로 “확정 이후 변경”에 해당하지 않는다. 그 성공 Refund에는 0원 또는 음수
SettlementAdjustment를 만들지 않고 append-only AuditRecord로
`NOT_APPLICABLE` 판정을 증명한다.

## Alternatives Considered

- 과거 정산 재계산·덮어쓰기
- 전체 배치 취소 후 재생성
- 불변 원장과 Adjustment

## Rationale

감사 가능성, 재처리와 과거 시점 재현성을 확보한다.

## Consequences

- 현재 유효 금액 계산에 원장 합산이 필요하다.
- 음수 이월 정책이 필요하다.

## Verification

- 확정 후 부분·전액 환불
- 중복 Adjustment 방지
- 다음 배치 상계 tie-out
- **Plan 20 evidence (2026-08-03):** V21과 Settlement consumer는 `COMPLETED` source만 immutable
  Item으로 만들고, 고객 취소 Refund에는 Item/Adjustment 없이 실제 Order/Refund evidence와
  source-unique `SETTLEMENT_REFUND_EXCLUDED` Audit만 남긴다. existing Item, source mismatch와
  Audit rollback은 성공 처리되지 않는 통합 테스트로 검증했다.
- **Settlement lifecycle evidence (2026-08-03):** V28~V29와 Settlement Application Service는
  confirmed Item/Batch에만 append-only Adjustment를 허용하고 source/reason unique와 mutation
  trigger로 중복·변경을 막는다. completed Refund와 accepted Dispute는 각각 stable source로
  하나의 Adjustment, Audit와 persistent event에 수렴한다. 계산 시각 기반 ingestion
  high-watermark와 이전 confirmed Batch의 negative carry source를 summary에 고정해 늦게 생성된
  Adjustment를 다음 Batch에서 한 번만 소비한다. 500건 keyset tie-out, 연속 음수 이월,
  exact replay와 conflict failure를 PostgreSQL 통합 테스트로 검증했다.

## Metrics

- **Measured (2026-08-03, single local run):** PostgreSQL 17.6, 1,000 Item, chunk 500에서
  calculation 36.054ms, confirmation 17.260ms였다. 기준선·SLA가 없는 진단 값이며 개선율 주장이 아니다.

## Revisit Conditions

실제 지급·채권 관리와 외부 회계 요구가 도입될 때

## Related Decisions

- BR-21, BR-23
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
- [ADR-018](ADR-018-settlement-dispute-hold-and-refile.md)
