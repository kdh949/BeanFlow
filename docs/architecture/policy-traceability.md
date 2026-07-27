# Business Policy Traceability

이 문서는 `docs/product/business-policy-decisions.md`의 BR-01~BR-32가 어느
Aggregate 또는 Read Model에서 보호되고, 어떤 상태·E2E 단계·트랜잭션·ADR로
구체화되는지 추적한다. Business Policy가 제품 동작의 원본이며 이 표는 복제된 정책
문구가 아니라 구현 준비 상태를 보여준다.

상태 표기:

- **Ready:** Accepted Policy와 ADR/아키텍처 문서가 구현 방향을 결정함

| BR | Owner / protected data | State and E2E coverage | Transaction / DB reinforcement | Decision record | Readiness |
|---|---|---|---|---|---|
| BR-01 | Store, PickupSlot, Campaign, PointLot, SettlementBatch | E2E 검색·예약·정산 시간 경계 | offset/Instant 저장, 고정 Clock | Policy | Ready |
| BR-02 | Order, Payment, SettlementItem/Adjustment | 주문 계산·부분 환불·정산 | integer KRW, 항목 합계와 결정적 배분 | ADR-014 | Ready |
| BR-03 | Order와 네 자원 Reservation | 주문 생성, PointLot 예약 시점 보장, `UNKNOWN`이어도 5분 만료; late approval은 void/refund | 한 로컬 tx, active source unique, guarded expiry/approval | ADR-005, ADR-011, ADR-013 | Ready |
| BR-04 | MenuConfiguration, SellableStock, StockReservation | menu/options → sellable requirements, reserve → confirm/release | normalized option set, conditional update/lock, source unique | ADR-005, ADR-026 | Ready |
| BR-05 | PickupSlot, PickupReservation | reserve → confirm/release | capacity guard, active order unique | ADR-005 | Ready |
| BR-06 | Order, Refund, 네 자원 Reservation, NotificationDelivery | 수락 경고·timeout·거절 보상 | guarded Order transition, idempotent compensation | ADR-015 | Ready except BR-03 gate |
| BR-07 | Order | `PAID → REJECTED`, `ACCEPTED` 이후 단순 거절 금지 | optimistic version/guarded transition | Policy, ADR-015 | Ready |
| BR-08 | Campaign, OrderLine benefit allocation | 대상 품목 coupon 계산 후 point 적용 | integer KRW/bps, immutable snapshot | ADR-004, ADR-014, ADR-024 | Ready |
| BR-09 | Campaign, CouponIssuance | order당 coupon 하나, available/reserved/used/restored | active issuance unique | ADR-024 | Ready |
| BR-10 | PointAccount, PointLot/Reservation/Transaction | 예약·사용과 OrderCompleted 적립 | source order/allocation unique | ADR-011 | Ready |
| BR-11 | Payment(type=BENEFIT_ONLY), Order와 네 자원 Reservation | 주문 생성 tx 안의 PG 없는 승인·예약 확정 | order/payment source unique, create tx 원자성 | ADR-016 | Ready |
| BR-12 | OrderLine, Refund, PointTransaction | 품목별 부분 환불 | snapshot allocation, refund source unique | ADR-014 | Ready |
| BR-13 | PointAccount, PointLot/Transaction, PointRecoveryPending | 환불 회수와 이후 적립 상계 | refund reference unique, non-negative account | ADR-011, ADR-014 | Ready |
| BR-14 | Order, Refund | 결제 전/후 고객 취소 | state guard, idempotent cancellation | Policy, ADR-015 | Ready |
| BR-15 | OrderLine, Refund | 주문 불변, 품목별 환불 | cumulative refund guard | ADR-004, ADR-014 | Ready |
| BR-16 | SettlementItem/Batch | OrderCompleted 이후 Item 생성 | source order/type unique | ADR-008, ADR-017 | Ready |
| BR-17 | SettlementBatch | 일별 OPEN→CALCULATED→CONFIRMED | store/date unique, chunk restart | ADR-017 | Ready |
| BR-18 | Order fee snapshot, SettlementItem | 완료·환불 정산 계산 | integer KRW, immutable rate snapshot | ADR-017 | Ready |
| BR-19 | Campaign cost snapshot, SettlementItem | 쿠폰 부담 정산 | share sum check, snapshot | ADR-017 | Ready |
| BR-20 | PointLot issuer cost, SettlementItem | 포인트 비용 정산 | lot source tie-out | ADR-011, ADR-017 | Ready |
| BR-21 | SettlementAdjustment | 확정 후 append-only 조정과 이월 | source reason unique | ADR-008, ADR-017 | Ready |
| BR-22 | SettlementDispute | `[D+1 00:00, D+15 00:00)` Asia/Seoul | application boundary check with fixed Clock | ADR-018 | Ready |
| BR-23 | SettlementDispute held amount | FILED→decision, Batch 불변 | active dispute/held source unique | ADR-018 | Ready |
| BR-24 | SettlementDispute | 종결 후 새 증빙 reference로 1회 재이의 | previous ID required, active partial unique | ADR-018 | Ready |
| BR-25 | IdempotencyRecord | 주문 생성 최초 response 재생, PROCESSING 409; Payment UNKNOWN/reconciliation | actor/operation/key unique + canonical payload hash | ADR-007, ADR-025 | Ready |
| BR-26 | IdempotencyRecord | non-terminal 보존, terminal+90일 정리 | chunk cleanup excludes unknown/open | ADR-007 | Ready |
| BR-27 | NotificationDelivery, ReprocessingCase | retry 1m/5m/30m→MANUAL_REVIEW | delivery idempotency unique | ADR-019 | Ready |
| BR-28 | Discovery Query Model | nearby search only, raw coordinate 비보존 | request-only coordinate; log redaction | ADR-020 | Ready |
| BR-29 | PaymentMethod | token metadata lifecycle | member/provider/token unique, sensitive fields absent | ADR-021 | Ready |
| BR-30 | AuditRecord | 주문 생성·만료 target별 append-only, manual reason, 서울 달력 5년 보존 | owner tx 원자성, action/target/source unique, chunk retention | ADR-022 | Ready |
| BR-31 | Analytics Read Model | refund-day and original-completion-day metrics | event/refund reference unique | ADR-023 | Ready |
| BR-32 | Analytics Read Model, ReprocessingCase | ≤7일 idempotent rebuild, 초과 BACKFILL_REQUIRED | source event/day unique, approved chunk backfill | ADR-023 | Ready |

## Cross-document gates

1. `ACCEPTED` 이후 매장 취소는 Accepted Business Policy가 없으므로 현재 Order 상태
   머신과 API target status에 포함하지 않는다.
2. OpenAPI는 아직 구현하지 않은 내부 schema를 예측하지 않고 위 표에서 확정된 최소
   금액, 시간, 상태, idempotency와 오류 의미만 계약한다.
