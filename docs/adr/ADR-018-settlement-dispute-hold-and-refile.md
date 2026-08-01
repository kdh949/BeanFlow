# ADR-018: 정산 이의제기 held 금액과 재이의

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

BR-22~BR-24는 확정 SettlementItem에 대한 14일 이의 기간, 분쟁 금액만의 hold와
새 증빙이 있는 한 번의 재이의를 정한다.

## Decision

- Dispute가 `FILED`될 때 대상 SettlementItem, 예상 조정액과 held amount를 기록한다.
- 제출 window는 Batch 확정의 `Asia/Seoul` 날짜 D를 기준으로
  `[D+1 00:00, D+15 00:00)`다.
- held amount는 확정 Batch 또는 Item을 수정하지 않으며 실제 지급 보류를 의미하지 않는
  MVP 내부 분쟁 표시다.
- 같은 Item에는 진행 중인 Dispute를 하나만 허용한다.
- `ACCEPTED` 판정은 Settlement에 원천 dispute ID를 포함한 Adjustment 명령을 보낸다.
  Adjustment가 확정되기 전에는 처리 완료로 가장하지 않는다.
- `REJECTED` 또는 `WITHDRAWN`은 held amount를 해제한다.
- 종결 후 새 증빙 reference와 이전 dispute ID가 있을 때 별도 Aggregate instance로
  한 번만 재이의를 허용한다.
- **Context boundary amendment (2026-08-01):** Dispute Context가 `SettlementDispute`, held
  amount, filing/decision state와 `SettlementDisputeFiled/Decided` event를 소유한다.
  Settlement Context는 SettlementItem/Batch/Adjustment를 소유하고 Dispute에 confirmed Item
  view를 제공한다. accepted decision은 Dispute가 Settlement의 public Adjustment command를
  호출해 전달하며, 어느 Context도 다른 Context의 Repository를 직접 호출하지 않는다.

## Alternatives Considered

- 분쟁 시 Batch 전체 보류
- 확정 Item 직접 수정
- Dispute별 held 표시와 판정 후 Adjustment

## Rationale

한 항목의 분쟁이 전체 매장 명세를 막지 않으며 확정 정산의 불변성을 유지한다.

## Consequences

- MVP held amount는 실제 자금 지급 보류가 아니다.
- 실제 지급 시스템 도입 시 외부 hold 상태와 실패 의미론을 별도 결정해야 한다.

## Verification

- 확정 다음 날과 14일 경계
- 같은 Item 동시 접수
- 승인 Adjustment 명령 실패·재시도
- 새 증빙 없는 재이의와 두 번째 재이의 거부

## Metrics

- **Not measured:** 이의 접수량과 처리시간

## Revisit Conditions

실제 지급 hold, 외부 중재 또는 계약상 이의 기간이 도입될 때

## Related Decisions

- BR-22, BR-23, BR-24
- [ADR-008](ADR-008-settlement-adjustment-ledger.md)
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-067](ADR-067-settlement-batch-creation-and-schema-ownership.md)
