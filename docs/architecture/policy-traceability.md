# Business Policy Traceability

이 문서는 `docs/product/business-policy-decisions.md`의 BR-01~BR-32가 어느
Aggregate 또는 Read Model에서 보호되고, 어떤 상태·E2E 단계·트랜잭션·ADR로
구체화되는지 추적한다. Business Policy가 제품 동작의 원본이며 이 표는 복제된 정책
문구가 아니라 구현 준비 상태를 보여준다.

상태 표기:

- **Ready:** Accepted Policy와 ADR/아키텍처 문서가 구현 방향을 결정함
- **Blocked by prerequisite:** 정책은 확정됐지만 구현 선행 모델 또는 검증 gate가 닫히지 않음
- **Scope accepted, contracts in progress:** 기능 범위는 Accepted이지만 API·이벤트·
  트랜잭션 계약이 아직 결정 진행 중이며 구현에 착수하지 않음

| BR | Owner / protected data | State and E2E coverage | Transaction / DB reinforcement | Decision record | Readiness |
|---|---|---|---|---|---|
| BR-01 | Store, PickupSlot, Campaign, PointLot, SettlementBatch | E2E 검색·예약·정산 시간 경계 | offset/Instant 저장, 고정 Clock | Policy | Ready |
| BR-02 | Order, Payment, SettlementItem/Adjustment | 주문 계산·부분 환불·정산 | integer KRW, 항목 합계와 결정적 배분 | ADR-014 | Ready |
| BR-03 | Order와 네 자원 Reservation | 주문 생성, PointLot 예약 시점 보장, `UNKNOWN`이어도 5분 만료; late approval은 void/refund | 한 로컬 tx, active source unique, guarded expiry/approval | ADR-005, ADR-011, ADR-013 | Ready |
| BR-04 | MenuConfiguration, SellableStock, StockReservation | menu/options → sellable requirements, reserve → confirm/release | normalized option set, conditional update/lock, source unique | ADR-005, ADR-026 | Ready |
| BR-05 | PickupSlot, PickupReservation | reserve → confirm/release | capacity guard, active order unique | ADR-005 | Ready |
| BR-06 | Order, Refund, 네 자원 Reservation, NotificationDelivery, BenefitRestorationPolicy, OrderCompensationCase, AcceptanceTimeoutWork | 수락 경고·timeout·거절 보상·만료 혜택 disposition | guarded Order transition, trigger×benefit policy snapshot, idempotent compensation, case trigger, durable timeout wakeup | ADR-015, ADR-028, ADR-033, ADR-041, ADR-058, ADR-059 | Clean-cutover gate passed; trigger-aware compensation foundation pending |
| BR-07 | Order | `PAID → REJECTED`, `ACCEPTED` 이후 단순 거절 금지 | optimistic version/guarded transition | Policy, ADR-015 | Ready |
| BR-08 | Campaign, OrderLine benefit allocation | 대상 품목 coupon 계산 후 point 적용 | integer KRW/bps, immutable snapshot | ADR-004, ADR-014, ADR-024 | Ready |
| BR-09 | Campaign, CouponIssuance | order당 coupon 하나, available/reserved/used/restored, 만료 보상 발급 | active issuance와 restoration source unique, trigger×coupon policy version, restoration disposition metadata, compensation terms·cost snapshot | ADR-024, ADR-028, ADR-041, ADR-042, ADR-043, ADR-049 | Ready |
| BR-10 | PointAccount, PointLot/Reservation/Transaction, OrdinaryAccrualPolicy, OrderPointAccrualSnapshot, AuditRecord | 예약·사용·종료 복원과 OrderCompleted 적립, 전역 기본+매장 override/상속 정책 선택, rollout 이전 Order 명시적 제외, 감사형 수동 adjustment | source order/allocation/restoration/adjustment unique, signed effect, 0..10000 bps, FLOOR/HALF_UP, exact/서울 달력일 expiry, GLOBAL fallback/STORE exact-match, append-only INHERIT_GLOBAL, verified initial GLOBAL bootstrap, LEGACY_NOT_APPLICABLE/SNAPSHOTTED source, immutable snapshot, completion/refund timing source, target Audit | ADR-011, ADR-028, ADR-041, ADR-042, ADR-066, ADR-073, ADR-074 | Runtime policy·adjustment implemented; PointAccount read is active Plan 14 |
| BR-11 | Payment(type=BENEFIT_ONLY), Order와 네 자원 Reservation, OrderCompensationCase | 주문 생성 tx 안의 PG 없는 승인·예약 확정, 고객 취소 시 Refund 없는 PAYMENT NOT_REQUIRED | order/payment source unique, create/cancel tx 원자성, 공통 여섯 step | ADR-016, ADR-039 | Ready |
| BR-12 | OrderLine, Refund, PointTransaction | 품목별 부분 환불 | snapshot allocation, refund source unique | ADR-014 | Ready |
| BR-13 | PointAccount, PointLot/Transaction, PointRecoveryPending | 환불 실제 `RECOVERY` debit과 이후 적립 우선 상계 | refund/Lot·pending source unique, non-negative account와 pending summary tie-out | ADR-011, ADR-014, ADR-065 | Ready |
| BR-14 | Order, Refund, PaymentCancellationRecoverySnapshot, 네 자원 Reservation, OrderCompensationCase, NotificationDelivery, ReprocessingCase, AcceptanceTimeoutWork | `PENDING_PAYMENT` 해제 전용 취소와 미수락 `PAID` 보상 취소, 소유자 전용 실행과 요약 조회, 선행 부분 환불 잔액 합성, 접수·환불 후속 알림, 정산 제외와 운영 복구 | state guard, 명령 tx 멱등, timeout work, case/source unique, refund amount/snapshot tie-out, request 3·lookup 5, customer projection, benefit-only PAYMENT NOT_REQUIRED, 자원 trigger, 두 benefit policy snapshot, accepted delivery commit gate, terminal result event, primary notification step 단조성, setup detector·2인 복구, target Audit, minimal V1 payload, clean cutover release gate | Policy, ADR-029~ADR-060 | Runtime implemented; command, recovery, Settlement lifecycle and clean-cutover evidence complete |
| BR-15 | OrderLine, Refund | 주문 불변, 품목별 환불과 고객 전체 취소 잔액 합성 | cumulative refund guard, line allocation source unique | ADR-004, ADR-014, ADR-036 | Ready |
| BR-16 | SettlementItem/Batch, Refund, AuditRecord | OrderCompleted 이후 Item 생성, 미완료 고객 취소 환불의 NOT_APPLICABLE 제외 증적 | source order/type unique, exclusion action/target/source unique, Order·Refund·Audit 파생 정합성 | ADR-008, ADR-017, ADR-048 | Ready |
| BR-17 | SettlementBatch | 일별 OPEN→CALCULATED→CONFIRMED | store/date unique, chunk restart | ADR-017 | Ready |
| BR-18 | StoreSettlementTerms, OrderSettlementInputSnapshot, SettlementItem | 주문 생성 시 수수료 계약을 고정하고 완료·환불 정산 계산 | applicable terms 하나, integer KRW, immutable fee-base/rate snapshot | ADR-017, ADR-071 | Ready |
| BR-19 | Campaign/CouponReservation cost legs, OrderSettlementInputSnapshot, SettlementItem | 일반·보상 쿠폰의 원 부담을 주문 시점에 고정해 정산 | burden share sum check, final platform/store leg tie-out, 미래 완료 주문에서만 비용 인식 | ADR-017, ADR-043, ADR-049, ADR-071 | Ready |
| BR-20 | PointLot issuer/allocation, OrderSettlementInputSnapshot, SettlementItem, AuditRecord | 포인트 비용 정산, Plan 10 만료 부분 환불 compensation과 수동 credit adjustment issuer snapshot | issuer type/reference immutable snapshot, Plan 10 legacy issuer precheck/migration gate, allocation/source tie-out, no default issuer | ADR-011, ADR-017, ADR-063, ADR-066, ADR-071 | Ready |
| BR-21 | SettlementAdjustment | 확정 후 append-only 조정과 이월, 미완료 고객 취소에는 Adjustment 생성 금지 | source reason unique, completed SettlementItem 존재 guard | ADR-008, ADR-017, ADR-048 | Ready |
| BR-22 | SettlementDispute | `[D+1 00:00, D+15 00:00)` Asia/Seoul | application boundary check with fixed Clock | ADR-018 | Ready |
| BR-23 | SettlementDispute held amount | FILED→decision, Batch 불변 | active dispute/held source unique | ADR-018 | Ready |
| BR-24 | SettlementDispute | 종결 후 새 증빙 reference로 1회 재이의 | previous ID required, active partial unique | ADR-018 | Ready |
| BR-25 | IdempotencyRecord, StoreCommandIdempotency, CancellationCommandIdempotency, PointAdjustment command, RepairProposal | 주문 생성 최초 response 재생, Payment UNKNOWN 현재 상태, 고객 취소·매장 전이·point adjustment terminal response 재생, replay 표시 부재, 2인 복구 명령 | actor/operation/key unique + canonical payload hash, target ID 포함, operation 승격, 기존 직렬화 root·외부 결과 불명 기반 모델 선택, proposal guarded transition | ADR-007, ADR-025, ADR-032, ADR-053, ADR-057, ADR-064, ADR-066 | Ready |
| BR-26 | IdempotencyRecord, CancellationCommandIdempotency, StoreCommandIdempotency, PointAdjustmentCommandIdempotency | non-terminal 보존, terminal+90일 정리 | Context/table별 독립 keyset chunk, `(retention_expires_at, id)` index, store backfill | ADR-007, ADR-032, ADR-056, ADR-066 | Ready |
| BR-27 | NotificationDelivery, ReprocessingCase | 접수·환불 성공·지연 알림, retry 1m/5m/30m→MANUAL_REVIEW | logical source unique, 접수 tx commit gate, 후속 terminal result publication | ADR-019, ADR-044, ADR-045, ADR-046, ADR-047 | Ready |
| BR-28 | Discovery Query Model, Merchant StoreDiscoveryProfile, SignedCursorCodec | nearby search only, raw coordinate 비보존 | request-only coordinate; response/error/log/metric/Audit redaction, raw range/micrometer keyset tuple, PostGIS 실패 503 | ADR-020, ADR-070 | Runtime implemented (nearby only; menu/pickup-slot 미구현) |
| BR-29 | PaymentMethod | token metadata lifecycle | member/provider/token unique, sensitive fields absent | ADR-021 | Ready |
| BR-30 | AuditRecord | 주문 생성·만료·고객 취소·정산 제외·setup 탐지/복구·감사형 point adjustment target별 append-only, manual reason, 서울 달력 5년 보존 | owner/consumer tx 원자성, action/target/source unique, 2인 actor 분리, adjustment command Audit atomicity, chunk retention | ADR-022, ADR-048, ADR-051, ADR-052, ADR-053, ADR-054, ADR-058, ADR-066 | Ready |
| BR-31 | Analytics Read Model | refund-day and original-completion-day metrics | event/refund reference unique | ADR-023 | Ready |
| BR-32 | Analytics Read Model, ReprocessingCase | ≤7일 idempotent rebuild, 초과 BACKFILL_REQUIRED | source event/day unique, approved chunk backfill | ADR-023 | Ready |

## Cross-document gates

1. `ACCEPTED` 이후 매장 취소와 고객 취소는 Accepted Business Policy가 없으므로 현재
   Order 상태 머신과 API target status에 포함하지 않는다. ADR-029가 제조 비용 부담
   주체와 취소 수수료 정책이 확정되기 전까지 운영자 우회 경로도 금지한다.
2. OpenAPI는 아직 구현하지 않은 내부 schema를 예측하지 않고 위 표에서 확정된 최소
   금액, 시간, 상태, idempotency와 오류 의미만 계약한다.
