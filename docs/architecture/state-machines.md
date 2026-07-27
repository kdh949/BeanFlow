# State Machines

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
ACCEPTED/PREPARING -> CANCELLED_BY_STORE
COMPLETED -> remains COMPLETED with refund records
```

Order의 과거 완료 사실은 부분 환불 때문에 되돌리지 않는다.

## Payment

```text
READY -> APPROVING -> APPROVED
READY/APPROVING -> FAILED
APPROVING -> UNKNOWN -> RECONCILING
APPROVED -> PARTIALLY_REFUNDED -> REFUNDED
```

`UNKNOWN`은 terminal failure가 아니다.

## Reservation

```text
RESERVED -> CONFIRMED
RESERVED -> EXPIRED
RESERVED -> RELEASED
CONFIRMED -> RELEASED_BY_REJECTION
```

같은 예약에 terminal transition은 한 번만 성공한다.

## Notification Delivery

```text
PENDING -> PROCESSING -> SUCCEEDED
PROCESSING -> RETRY_SCHEDULED
RETRY_SCHEDULED -> PROCESSING
PROCESSING/RETRY_SCHEDULED -> MANUAL_REVIEW
```

## Settlement Batch

```text
OPEN -> CALCULATED -> CONFIRMED
```

`CONFIRMED` 이후 Item 또는 Batch를 수정하지 않고 Adjustment를 추가한다.

## Dispute

```text
FILED -> UNDER_REVIEW -> ACCEPTED | REJECTED | WITHDRAWN
```

재이의제기는 이전 Dispute를 참조하는 별도 Aggregate instance다.
