# ADR-134: 승인 후 혜택 원자 사용과 미성립 승인 복구

- **Status:** Accepted
- **Date:** 2026-09-16

## Context

기존 Order 생성은 Coupon, Point와 Pickup을 같은 5분 lease로 예약하고 실제 reservation allocation을 사용해
최종 정산 입력 snapshot을 즉시 저장한다. 신규 즉시 주문에서 만료시각만 제거하면 인증 대기 중 혜택을
무기한 선점하게 된다. 반대로 선예약을 제거하면 PG 승인 뒤 같은 쿠폰이나 잔액이 먼저 사용되어 주문이
성립하지 못할 수 있다.

## Decision

- `IMMEDIATE` Tx A는 선택한 coupon ID, points amount, 금액·항목, 수수료/정책 source와 schema version을
  typed input snapshot으로 고정하지만 Coupon/Point owner write와 최종 settlement input은 만들지 않는다.
- PG confirm은 DB transaction 밖에서 수행한다. callback 금액, provider order ID와 payment key를 기존
  Payment attempt와 exact match한다.
- 승인 후 Tx C는 replay와 current winner를 먼저 판별하고 Store→Order→Payment claim→Coupon
  issuance→PointAccount→PointLot의 검증된 lock 순서로 현재 상태를 다시 읽는다. 잠금 대기 뒤의 서버 시각을
  사용한다.
- Tx C에서 Coupon을 직접 USED로, Point를 실제 USE ledger와 Lot allocation으로 기록하고, 그 실제 source로
  최종 `OrderSettlementInputSnapshot`, Payment 승인과 Order `PAID`를 한 transaction에 commit한다. 중간
  RESERVED 상태를 commit하지 않는다.
- Tx A에서 고정한 금액·항목·수수료 source는 재시도 때 다시 계산하지 않는다. PointLot은 Tx C 당시 유효한
  기존 선소멸 순서로 배분하고 실제 issuer allocation을 snapshot에 쓴다. 기존
  `OrderPointAccrualSnapshot` 등 다른 불변 snapshot은 보존한다.
- 쿠폰 경합·만료, 포인트 부족, 영업 종료처럼 확정적인 업무 실패는 Tx C 전체를 rollback한다. 별도 Tx D가
  Order/Payment를 다시 잠가 이미 같은 거래가 `PAID`인 승자를 확인한 뒤에만 초안을 종료하고 실제 승인 사실과
  void/refund reconciliation work를 저장한다. 최종 settlement snapshot이 없어도 승인 근거로 복구할 수 있다.
- DB 연결·timeout·deadlock·snapshot 무결성 오류와 Provider 결과불명은 혜택 부족으로 바꾸지 않는다. 기존
  UNKNOWN/RECONCILING/MANUAL_REVIEW로 수렴한다.
- `BENEFIT_ONLY`는 Provider 없이 동일 gate, direct use, 최종 snapshot, Payment 승인과 PAID를 한 transaction에
  commit한다.

## Alternatives Considered

- 기한 없는 예약: 무기한 점유라 제외한다.
- 결제 버튼에서 미리 차감: 인증 대기 중 사용 불가이므로 선점과 같아 제외한다.
- 승인 뒤 무조건 차감: 중복 사용과 음수 잔액을 허용하므로 제외한다.
- 새 Saga/queue: 같은 PostgreSQL·배포 단위에서 기존 recovery보다 운영 비용이 커 제외한다.

## Rationale

사용자가 확정한 “인증 대기 중 선예약 없음”을 실제 owner state로 지키면서 기존 금융 원장, immutable
snapshot, Payment lookup과 환불 recovery를 재사용한다.

## Consequences

혜택 경합에서 승인된 결제가 자동취소/환불될 수 있다. 이 비용을 숨은 hold나 추가 청구로 가리지 않고 원인,
진행 상태와 완료 지연을 관측해야 한다. settlement input materialization 시점이 Tx C로 이동하므로 정상 승인과
복구를 분리하지 않은 채 외부 성공 경로를 열어야 한다.

## Verification

- quote/Tx A/인증 대기 중 reservation row·reserved balance 변화가 0인지 검증한다.
- 동일 Coupon/Point 경합에서 경제적 성공 한 건과 나머지 승인 건의 void/refund 수렴을 검증한다.
- coupon 사용 후 points 부족, snapshot insert 실패와 Payment/PAID 실패가 전체 rollback되는지 검증한다.
- 중복 approval, 이미 PAID인 승자와 Tx D 경합, PG 성공 후 서버 종료/DB 실패, 장기 UNKNOWN을 검증한다.
- 0원 정상/경합/영업시간 밖 경로와 기존 완료·부분환불·정산 tie-out을 검증한다.

## Metrics

승인 후 혜택 경합률, 자동취소/환불 지연, UNKNOWN 최고 경과시간, 중복 use와 원장 불일치, lock wait를 관측한다.
현재 측정값은 없다.

## Revisit Conditions

승인 후 혜택 경합 취소가 실제 고객 경험 문제로 측정되면 짧은 보호 구간, 고객별 checkout 직렬화 또는 혜택
정책 변경을 새 정책/ADR로 비교한다.

## Related Decisions

- BR-02, BR-03, BR-08~12, BR-18~20, BR-25, BR-26, BR-33, BR-49
- [ADR-005](ADR-005-reservation-transaction-strategy.md)과 [ADR-013](ADR-013-payment-unknown-reservation-expiry.md)은
  `LEGACY_RESERVED`에 유지하고 `IMMEDIATE` 선예약에는 적용하지 않는다.
- [ADR-006](ADR-006-external-payment-transaction-boundary.md),
  [ADR-007](ADR-007-payment-idempotency-reconciliation.md),
  [ADR-068](ADR-068-immutable-integration-event-snapshots.md),
  [ADR-071](ADR-071-settlement-input-snapshot-foundation.md)
- [ADR-133](ADR-133-immediate-checkout-and-store-preparation.md),
  [ADR-135](ADR-135-store-hours-payment-gate-and-cutoff.md)
