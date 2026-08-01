# ADR-011: PointLot과 포인트 원장

- **Status:** Accepted
- **Date:** 2026-07-28
- **Amended by:** ADR-063의 부분 환불 만료 포인트 복원, ADR-065의 환불 적립 포인트 회수, ADR-066의 감사형 포인트 조정, ADR-073의 주문 시점 일반 적립 snapshot

## Context

단일 balance만으로 만료 순서, 발급 주체, 부분 환불 복원과 비용 부담을 재현할 수 없다.

## Decision

발급분별 PointLot과 적립·사용·소멸·복원·조정 PointTransaction 원장을 사용한다. 사용은 만료가 빠른 Lot부터 한다.

주문 생성의 결제 전 포인트 예약은 다음을 따른다.

- Loyalty가 `PointReservation` Aggregate를 소유한다. Reservation은 `orderId`,
  `pointAccountId`, 총액, 상태, 주문 `reservationExpiresAt`과 source reference를
  가지고 하위 `PointReservationAllocation`이 PointLot별 금액을 고정한다.
- 주문 생성 시 `expiresAt > now`인 Lot만 `(expiresAt, pointLotId)` 순서로 잠그고
  allocation한다. 같은 Order의 active reservation은 하나뿐이다.
- PointAccount와 PointLot은 available과 reserved 요약 금액을 구분한다. 예약 시
  available을 줄이고 reserved를 늘리며 총합과 allocation을 같은 트랜잭션에서
  검증한다.
- 예약 시점에 유효했던 allocation은 주문 lease 끝까지 확정할 수 있다. 원 Lot의
  만료 시각이 lease 중간에 지나도 예약분을 자동 해제하거나 결제 승인을 거부하지
  않는다.
- Lot 만료 작업은 available 금액만 즉시 만료하고 active reserved 금액은 해당
  Order가 `USED` 또는 `RELEASED`로 종결될 때까지 보존한다.
- 결제 승인 시 reserved allocation을 USE PointTransaction으로 확정한다.
- 예약 해제 시 원 Lot이 아직 유효한 allocation은 available로 복원한다. 이미
  만료된 allocation은 available로 복원하지 않고 EXPIRATION PointTransaction으로
  확정한다. 한 Reservation 안에서 두 disposition이 섞일 수 있다.
- reservation, use, release와 expiration은 Order/source reference와 allocation
  reference의 Unique Constraint로 한 번만 반영한다.

이 clarification은 2026-07-28 주문 생성과 예약 lease Feature의 결정 게이트에서
확정했다.

2026-07-30 store rejection amendment:

- 사용 확정된 PointReservation은 매장 거절 시 `USED -> RESTORED`로 전환한다.
- 원 PointLot이 유효하면 원 Lot의 available balance와 `RESTORE` 원장을 복원한다.
- 이미 만료됐고 활성 정책이 `COMPENSATE_WITH_NEW_ISSUANCE`면 allocation별 원 Lot
  reference를 보존한 새 PointLot과 `COMPENSATION` 원장을 생성한다.
- `PRESERVE_ORIGINAL_EXPIRY`면 가용 balance를 늘리지 않고
  `RESTORE_SKIPPED_EXPIRED` 원장을 기록한다.
- event source와 allocation reference의 Unique Constraint가 이중 복원을 막는다.

2026-08-01 partial refund amendment (ADR-063):

- 부분 환불은 `PARTIAL_REFUND × POINTS` policy version을 Refund 요청 시 snapshot한다.
- 원 PointLot이 `refundSucceededAt`에 유효하면 원 lot과 `RESTORE` 원장을 복원한다.
- 이미 만료됐고 snapshot mode가 `COMPENSATE_WITH_NEW_ISSUANCE`면 original lot과
  issuer/cost lineage를 보존한 새 PointLot을 `refundSucceededAt`부터 snapshot validity
  days 동안 발급하고 `COMPENSATION` 원장을 기록한다. 초기 유효일수는 30일이다.
- `PRESERVE_ORIGINAL_EXPIRY`면 가용 balance를 늘리지 않고
  `RESTORE_SKIPPED_EXPIRED`를 기록한다.
- 부분 환불은 PointReservation의 `USED` 상태와 reservation-level 종료 metadata를
  바꾸지 않는다. Refund line/point allocation과 PointTransaction이 부분 복원 원천이다.
- 후속 주문 종료는 이미 부분 환불로 복원된 allocation을 제외하고 잔여 allocation만
  처리한다.

2026-08-01 refund-earned-point recovery amendment (ADR-065):

- `RECOVERY`는 환불에 대응해 실제 가용 PointLot과 PointAccount에서 차감한
  append-only debit PointTransaction이다.
- 회수하지 못한 잔액은 PointTransaction이 아닌
  `PointRecoveryPending(PENDING)` Aggregate로 보존하며, 이후 적립은 이를 먼저
  상계한다.
- PointAccount 가용 잔액은 음수가 될 수 없고, `recoveryPendingKrw` summary는 PENDING
  잔액 합과 같은 transaction에서 유지한다.
- 2026-08-02 Plan 13 V17과 owner service가 이 contract를 구현했다. completion 적립은 gross
  `ACCRUAL` PointLot/transaction을 먼저 만들고 같은 transaction의 pending별 `RECOVERY`로
  새 Lot을 debit하므로 append-only effect 합과 net available이 함께 보존된다.

2026-08-01 ordinary accrual snapshot amendment (ADR-073):

- 일반 적립의 적용 정책과 unit별 적립 결과는 Order 생성 시 immutable
  `OrderPointAccrualSnapshot`으로 고정한다. 새 PointLot은 완료 시 이 snapshot이 정한
  issuer와 만료 결과를 저장하며 현재 적립 정책을 다시 읽지 않는다.
- `OrderCompletedV1`은 frozen trigger로 유지한다. Loyalty는 version/source를 검증하는
  typed Ordering boundary로 immutable snapshot을 읽고, snapshot 누락·불일치에는 0원 Lot,
  기본 issuer 또는 기본 만료를 만들지 않는다.
- 완료 전에 성공한 부분 Refund의 unit은 이후 일반 적립에서 제외하며, 아직 발생하지 않은
  credit에 `RECOVERY` 또는 PointRecoveryPending을 만들지 않는다. BR-10은
  `refundSucceededAt <= completedAt`에 Refund 우선으로 `EXCLUDED_BEFORE_ACCRUAL`을
  적용하고, 그보다 늦은 성공분만 recovery 대상으로 정한다.

2026-08-01 audited manual adjustment amendment (ADR-066):

- `ADJUSTMENT`는 활성 Platform Operator의 명시적 권한, reason, evidence와 target
  AuditRecord가 필요한 signed manual correction이다.
- 양수 adjustment는 입력 issuer snapshot과 future expiry의 새 PointLot을 만들고,
  음수 adjustment는 available Lot을 선소멸 순서로 줄인다. amount 방향은 storage
  `balance_effect`로 보존한다.
- 수동 adjustment는 PointRecoveryPending을 상계하지 않으며, SettlementAdjustment나
  refund recovery type을 재사용하지 않는다.

## Alternatives Considered

- balance만 저장
- 원장만 저장하고 balance 매번 합산
- Lot+원장+검증 가능한 요약 balance

## Rationale

만료와 비용 추적을 지원하면서 조회 성능을 위한 요약을 유지한다.

## Consequences

- 사용 시 여러 Lot을 잠글 수 있다.
- 요약과 원장 reconciliation이 필요하다.
- PointLot 만료 worker는 active reservation을 삭제하지 않고 available과 reserved를
  구분해야 한다.
- 주문 해제 결과가 Lot 만료 전후에 따라 restore 또는 expiration으로 나뉜다.
- 부분 환불 보상 lot은 원 issuer/cost owner를 승계하고 Refund policy snapshot으로
  재현된다.
- 환불 적립 포인트 회수와 이후 적립 상계는 실제 `RECOVERY` debit과 별도 pending
  obligation을 함께 tie-out해야 한다.
- 일반 적립은 주문 생성 snapshot의 gross amount와 unit별 allocation으로만 재현한다.
- 수동 adjustment도 PointLot, PointAccount, signed transaction, IdempotencyRecord와
  AuditRecord를 같은 local transaction에서 tie-out해야 한다.

## Verification

- 선소멸 사용
- 만료 경계
- lease 중 Lot 만료 후 결제 승인
- lease 중 Lot 만료 후 예약 해제 시 비복원
- 유효·만료 allocation이 섞인 예약 해제
- 같은 Order의 동시 포인트 예약
- 환불 복원·회수
- 부분 환불 시 만료 lot의 30일 보상과 정책 version 재현
- 부분 환불 뒤 reservation USED 유지와 후속 종료의 잔여 allocation 복원
- 환불 적립 포인트 전액/부분 회수, 부족액 상계와 pending summary tie-out
- 주문 생성 뒤 적립 정책 변경, 성공 부분 환불 unit과 일반 적립 snapshot의 allocation tie-out
- 수동 양수/음수 adjustment의 issuer·expiry, balance effect와 Audit atomicity
- 원장과 balance tie-out

## Metrics

측정 전에는 목표·가정과 실제 결과를 분리한다. 실제 측정 결과가 생기면 조건과 함께 추가한다.

## Revisit Conditions

포인트 프로그램 통합·선물·양도 요구가 생길 때

## Related Decisions

- BR-10, BR-11, BR-12, BR-13, BR-20
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
- [ADR-017](ADR-017-settlement-calculation-and-cost-allocation.md)
- [ADR-065](ADR-065-refund-earned-point-recovery-ledger.md)
- [ADR-066](ADR-066-audited-loyalty-point-adjustment.md)
- [ADR-073](ADR-073-order-point-accrual-snapshot.md)
