# ADR-023: 환불 지표와 late event 재집계

- **Status:** Accepted
- **Date:** 2026-07-28

## Context

BR-31은 환불 발생일 운영 지표와 원 주문 완료일 수익성 보정 지표를 구분한다.
BR-32는 event 발생일 기준 7일 수정 window와 그보다 오래된 event의 승인된 backfill을
정한다.

## Decision

- Analytics Read Model은 원본 거래 Aggregate가 아니며 source event ID와 payload
  version으로 멱등 갱신한다.
- `refundAmountByRefundDate`는 환불 성공일에 귀속한다.
- `adjustedRevenueByOrderCompletionDate`는 원 Order 완료일 지표를 보정한다.
- 두 지표를 합치거나 같은 이름으로 노출하지 않는다.
- event 발생일로부터 7일 이내 late/replayed event는 해당 일자를 멱등 갱신하고 야간
  재집계에 포함한다.
- 7일 초과 event는 자동 수정하지 않고 `BACKFILL_REQUIRED` ReprocessingCase를 만든다.
  승인된 case만 chunk 단위로 재집계한다.
- projection 실패를 빈/0/stale 지표로 정상처럼 반환하지 않고 freshness와 failure
  상태를 노출한다.

## Alternatives Considered

- 환불을 항상 원 주문일에만 귀속
- 모든 과거 event 자동 재집계
- 두 지표와 bounded automatic window

## Rationale

당일 환불 흐름과 원 거래 수익성을 각각 설명하면서 오래된 대규모 변경을 운영 승인 아래
둔다.

## Consequences

- 지표 이름, 기준일과 freshness를 API/화면에서 함께 표시해야 한다.
- replay와 backfill은 원본 event를 수정하지 않는다.

## Verification

- 과거 완료 주문의 당일 부분 환불
- 같은 event duplicate/replay
- 7일 경계와 8일 이상 event
- backfill 중단·재시작 결과 동일성

## Metrics

- **Target:** projection lag, failed event count, backfill case와 freshness 관측
- **Not measured:** 실제 event 지연 분포와 재집계 비용

## Revisit Conditions

실제 지연 분포, 회계 정의 또는 재집계 비용이 window 변경을 요구할 때

## Related Decisions

- BR-31, BR-32
- [ADR-009](ADR-009-explicit-failure-semantics.md)
- [ADR-010](ADR-010-initial-event-publication.md)
