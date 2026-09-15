# ADR-133: 장바구니 즉시 결제와 수락 시 준비시간

- **Status:** Accepted
- **Date:** 2026-09-16

## Context

기존 고객 주문은 픽업 슬롯을 선택하고 5분간 자원을 예약한 뒤 별도 checkout 화면에서 Toss 인증을
진행한다. 주문 초안 생성 `201` 직후 장바구니도 비운다. 신규 제품 동작은 슬롯 선택과 고정 결제 lease를
제거하고 장바구니에서 기존 Toss V2 Standard Payment Window로 바로 진입해야 한다. 고객이 말하는 주문
완료는 서버가 `PAID`를 commit한 결과이며 기존 `COMPLETED`는 실제 픽업 완료로 남아야 한다.

## Decision

- 신규 Order는 `checkoutMode=IMMEDIATE`로 구분하고 기존 행은 `LEGACY_RESERVED` 의미를 보존한다.
- Tx A는 주문·금액·거래조건·공개 reference와 결제 복구용 초안만 저장한다. 슬롯이나 혜택을 예약하지
  않으며 `reservationExpiresAt`는 null이다.
- 기존 주문 생성과 one-time Payment prepare API를 고객의 한 동작에서 연속 호출하고 Toss 창을 연다.
  새 통합 Checkout Aggregate나 저장 결제수단 승인을 만들지 않는다.
- Toss success callback은 서버 confirm 진입점일 뿐 성공 표시가 아니다. 승인과 Order `PAID`가 commit된
  응답 또는 같은 거래 조회로 확인된 뒤에만 고객에게 주문 완료를 표시한다.
- 결제 시작 시 사용자 범위와 cart revision을 저장하고, 성공 확인 시 현재 revision이 같을 때만 장바구니와
  coupon selection을 비운다. 결과불명 거래를 새 Payment로 대체하지 않는다.
- 매장 `ACCEPT`는 `preparationMinutes` 1~120을 필수로 받고 `acceptedAt`과
  `estimatedReadyAt=acceptedAt+preparationMinutes`를 한 번 저장한다. 같은 key와 입력은 최초 값을 재생하고
  다른 분 수는 충돌한다. ETA 도달은 READY/COMPLETED 전이가 아니다. UUID 호환 API도 같은 규칙을 적용한다.

## Alternatives Considered

- 숨은 자동 슬롯 선택: 예약·정원·만료 의존이 남아 제외한다.
- PG 승인 전 DB 기록 없음: 금액 검증, 멱등성, 응답 유실 복구 근거가 없어 제외한다.
- 새 Checkout Aggregate/API: 기존 Order/Payment/reconciliation과 광범위한 이행이 필요해 제외한다.
- 저장 카드 원클릭 결제: 현재 Toss one-time 계약과 다른 제품이므로 제외한다.

## Rationale

고객의 예약 단계를 실제 상태에서 제거하면서 검증된 Order/Payment 식별자, Provider exact-match,
reconciliation과 공개 주문번호를 재사용한다. 준비 예상과 실제 픽업 완료를 분리해 기존 적립·정산 사건을
변경하지 않는다.

## Consequences

결제 전 내부 초안과 사용되지 않은 공개 번호가 남을 수 있다. 일반 주문 목록은 성립 주문과 결제 처리 기록을
구분해야 한다. 장바구니/성공/실패 callback, 재주문, 고객 이력, 매장 보드와 생성 타입을 같은 release stack에서
전환해야 하며 old binary로 단순 rollback할 수 없다.

## Verification

- 초안 `201`·success callback만으로 성공 표시나 cart clear가 없는지 검증한다.
- 5분을 넘긴 인증, 응답 유실·새로고침·다른 탭 변경·계정 변경과 UNKNOWN 재개를 검증한다.
- 준비시간 누락·0·음수·121, 같은/다른 멱등 입력, UUID API와 ETA 경과 후 실제 상태를 검증한다.
- legacy 주문의 슬롯·공개번호·실제 완료 event와 정산 회귀를 검증한다.

## Metrics

checkout 시작 대비 `PAID` 확정·이탈, 중복 Payment, cart 보존, 준비 ETA 대비 actual readyAt를 관측한다.
현재 측정값은 없다.

## Revisit Conditions

다른 채널과 checkout을 공유하거나 초안 보존 비용이 측정된 문제가 되면 별도 Checkout Aggregate를 검토한다.
Billing Key/BrandPay는 독립 제품 결정으로 다룬다.

## Related Decisions

- BR-01, BR-03, BR-05, BR-06, BR-14, BR-25, BR-33, BR-49
- [ADR-080](ADR-080-toss-v2-one-time-payment-window.md), [ADR-096](ADR-096-public-order-reference.md),
  [ADR-097](ADR-097-store-pickup-number.md), [ADR-101](ADR-101-payment-method-checkout-scope.md),
  [ADR-123](ADR-123-order-quote-trade-terms-and-shared-availability.md)
- [ADR-134](ADR-134-post-approval-benefit-use-and-recovery.md),
  [ADR-135](ADR-135-store-hours-payment-gate-and-cutoff.md)

