# State Machines

상태 이름은 소유 Context 안에서 해석한다. 한 Aggregate의 terminal 상태는 다른
Aggregate의 후속 작업까지 성공했다는 뜻이 아니다. 예를 들어 Order `REJECTED`와
Payment 환불 완료는 별도 상태다.

## Order

```text
DRAFT
  -> PENDING_PAYMENT
  -> PAID
  -> ACCEPTED
  -> PREPARING
  -> READY
  -> COMPLETED
```

Exceptional transitions:

```text
PENDING_PAYMENT -> EXPIRED | CANCELLED
PAID -> REJECTED | CANCELLED
COMPLETED -> remains COMPLETED with refund records
```

Order의 과거 완료 사실은 부분 환불 때문에 되돌리지 않는다.

- `REJECTED`는 매장 거절 또는 수락 timeout의 Order 결과다. 결제 승인취소·환불은
  Payment/Refund 상태로 별도 추적하며 실패를 `REJECTED` 성공으로 숨기지 않는다.
- `ACCEPTED` 이후 매장 취소는 Business Policy가 아직 없으므로 상태 전이로 허용하지
  않는다. 정책이 결정되기 전에는 승인된 운영자 환불 명령도 Order 상태를 임의로
  변경하지 않는다.
- `BENEFIT_ONLY` 결제도 외부 PG 호출만 생략할 뿐 Order는 같은 `PAID` 전이를 사용한다.
- 주문 생성 중 payable이 0이면 `DRAFT -> PENDING_PAYMENT -> PAID`를 한 로컬
  트랜잭션에서 수행하고 외부에는 `PAID`만 커밋한다. 이 주문은 active lease나
  `EXPIRED` 전이 대상이 아니다.

## Payment approval

```text
READY -> APPROVING -> APPROVED
READY/APPROVING -> FAILED
APPROVING -> UNKNOWN -> RECONCILING
RECONCILING -> APPROVED | FAILED | MANUAL_REVIEW
READY -> APPROVED  (BENEFIT_ONLY only, no external call)
```

`UNKNOWN`은 terminal failure가 아니다.

Order가 이미 `EXPIRED` 또는 `CANCELLED`인 뒤 승인 사실이 확인되면 Payment는
`RECONCILING`에 남고 별도 `LATE_VOID -> LATE_REFUND` recovery 상태가
`SUCCEEDED` 또는 `MANUAL_REVIEW`에 도달한다. 이 경로는 Order 상태를 바꾸지 않는다.

Payment의 표시 상태 `PARTIALLY_REFUNDED`와 `REFUNDED`는 성공한 Refund 합계에서
파생한다. 승인 결과와 환불 시도 결과를 하나의 enum으로 덮어쓰지 않는다.

## Refund

```text
REQUESTED -> PROCESSING -> SUCCEEDED
PROCESSING -> FAILED
PROCESSING -> UNKNOWN -> RECONCILING
RECONCILING -> SUCCEEDED | FAILED | MANUAL_REVIEW
```

- `UNKNOWN` 또는 `RECONCILING` 환불을 성공 환불액에 포함하지 않는다.
- 동일 refund idempotency scope와 source reference는 외부 부작용을 한 번만 만든다.
- 누적 `SUCCEEDED` 환불액은 승인액을 초과할 수 없다.

## Reservation

```text
RESERVED -> CONFIRMED
RESERVED -> EXPIRED
RESERVED -> RELEASED
CONFIRMED -> RELEASED_BY_REJECTION
```

같은 예약에 terminal transition은 한 번만 성공한다.

Payment가 `UNKNOWN`인 채 5분 lease에 도달해도 Reservation은 `EXPIRED`로 전이한다.
뒤늦은 Provider 승인이 확인되면 만료 Order나 Reservation을 되살리지 않고 Payment의
void/refund `RECONCILING` 경로를 시작한다.

deadline이 지났다고 API 응답에서만 상태를 파생하지 않는다. worker, Order 조회 또는
결제 명령이 같은 expiry transaction으로 Order와 Reservation 전이를 모두 커밋한
뒤에만 `EXPIRED`를 반환한다. transaction 실패 시 저장 상태를 유지하고 API는 503으로
실패한다.

## Coupon issuance

```text
AVAILABLE -> RESERVED -> USED
RESERVED -> RELEASED -> AVAILABLE
USED -> RESTORED
```

한 CouponIssuance는 동시에 두 주문의 `RESERVED` 또는 `USED` 상태가 될 수 없다.

## Point use

PointAccount 자체를 상태 enum으로 표현하지 않는다. 사용·복원은 append-only
PointTransaction과 PointLot 잔액으로 표현하고, 주문별 활성 예약과 사용 reference를
Unique Constraint로 보호한다. 환불 적립 회수 부족액은 Loyalty의
`POINT_RECOVERY_PENDING` 원장 항목이며 SettlementAdjustment가 아니다.

PointReservation은 다음 상태를 사용한다.

```text
RESERVED -> USED
RESERVED -> RELEASED
USED -> RESTORED
```

- `RESERVED` allocation은 예약 시점에 유효했다면 주문 lease 동안 원 Lot 만료와
  무관하게 `USED`로 확정할 수 있다.
- `RELEASED` 시 아직 유효한 allocation은 available로 복원하고 이미 만료된
  allocation은 EXPIRATION 원장으로 확정한다.
- 한 Reservation의 allocation마다 restore/expiration disposition을 한 번만 기록한다.
- 거절 복원 시 유효한 원 allocation은 가용 잔액으로 돌아간다. 만료 allocation은
  거절 event의 정책 snapshot에 따라 새 PointLot으로 보상하거나
  `RESTORE_SKIPPED_EXPIRED` 원장만 기록한다.

## Notification Delivery

```text
PENDING -> PROCESSING -> SUCCEEDED
PROCESSING -> RETRY_SCHEDULED
RETRY_SCHEDULED -> PROCESSING
PROCESSING/RETRY_SCHEDULED -> MANUAL_REVIEW
```

- attempt 1 이후 1분, 5분, 30분의 세 추가 시도만 예약한다.
- 네 번째 실패 후 자동 재시도를 더 만들지 않는다.
- 운영자 재처리는 같은 delivery idempotency key를 재사용하고 별도 감사 기록을 남긴다.

## Settlement Batch

```text
OPEN -> CALCULATED -> CONFIRMED
```

`CONFIRMED` 이후 Item 또는 Batch를 수정하지 않고 Adjustment를 추가한다.
SettlementItem과 SettlementAdjustment는 append-only 원장 항목이므로 별도 수정 상태
머신을 두지 않는다.

## Dispute

```text
FILED -> UNDER_REVIEW -> ACCEPTED | REJECTED | WITHDRAWN
```

재이의제기는 이전 Dispute를 참조하는 별도 Aggregate instance다.

- `FILED` 시 대상 예상 조정액을 held amount로 기록한다.
- `ACCEPTED`는 Settlement에 Adjustment 생성 명령을 보내고, 성공한 원천 reference를
  중복 생성하지 않는다.
- `REJECTED` 또는 `WITHDRAWN`은 held amount를 해제한다.

## Idempotency record

```text
PROCESSING -> COMPLETED
PROCESSING -> UNKNOWN
UNKNOWN -> RECONCILING -> COMPLETED | MANUAL_REVIEW
PROCESSING -> FAILED
```

`FAILED`는 외부 부작용이 없다고 확정된 실패에만 사용한다. `PROCESSING`, `UNKNOWN`,
`RECONCILING`과 `MANUAL_REVIEW` 레코드는 terminal 거래 90일 정리 대상이 아니다.

주문 생성 IdempotencyRecord는 외부 Provider를 호출하지 않으므로
`PROCESSING -> COMPLETED | FAILED | MANUAL_REVIEW`를 사용한다. `COMPLETED`와
`FAILED`는 최초 HTTP status/body를 보존하고, stuck reconciliation에서 일부
부수효과나 결과 불명이 발견될 때만 `MANUAL_REVIEW`로 전환한다.

## Reprocessing case

```text
OPEN -> RUNNING -> RESOLVED
RUNNING -> RETRY_SCHEDULED | MANUAL_REVIEW
RETRY_SCHEDULED -> RUNNING
```

7일을 초과한 Analytics late event는 자동으로 과거 지표를 바꾸지 않고
`BACKFILL_REQUIRED` 유형의 `OPEN` case를 만든다.
