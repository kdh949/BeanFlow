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
- `ACCEPTED` 이후 매장 취소와 고객 취소는 Business Policy가 아직 없으므로 상태
  전이로 허용하지 않는다. 정책이 결정되기 전에는 승인된 운영자 환불 명령도 Order
  상태를 임의로 변경하지 않는다.
- 고객 취소는 `PENDING_PAYMENT`와 `ACCEPTED` 이전 `PAID`에서만 `CANCELLED`로
  전이한다(BR-14, ADR-029). `PENDING_PAYMENT` 취소는 예약 해제만 수반하고
  `PAID` 취소는 매장 거절과 같은 owner 보상 대상을 갖는다. `CANCELLED` 자체는
  환불·복원 완료를 뜻하지 않는다.
- 고객 취소, 매장 수락, 3분 timeout 자동 거절은 같은 Order row에서 경쟁하며 하나만
  성공한다.
- acceptance deadline 이후 고객 취소는 Order를 취소하지 않고 deduplicated
  `AcceptanceTimeoutWork`를 저장한 뒤 409를 반환한다. timeout worker가 기존
  store-timeout 거절 전이를 실행한다.
- `CANCELLED`는 여러 원인이 공유하는 단일 상태다. 원인은 Order의
  `cancellationCause`(`CUSTOMER_REQUEST`, `PAYMENT_DECLINED`)로 구분하며 별도
  Cancellation Aggregate를 두지 않는다. `CANCELLED`에서 `cancelledAt`과
  `cancellationCause`는 필수다.
- `BENEFIT_ONLY` 결제도 외부 PG 호출만 생략할 뿐 Order는 같은 `PAID` 전이를 사용한다.
- `BENEFIT_ONLY` 고객 취소는 Refund 없이 PAYMENT 보상 step을 `NOT_REQUIRED`로
  확정하고 나머지 owner 보상은 일반 `PAID` 취소와 동일하게 진행한다.
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

일회성 checkout에서는 `OneTimePaymentAttempt`가 같은 승인 생명주기를 다음처럼 명시한다.

```text
READY -> CONFIRMING -> APPROVED | FAILED
CONFIRMING -> UNKNOWN -> RECONCILING
RECONCILING -> APPROVED | FAILED | MANUAL_REVIEW
```

- `READY`는 callback snapshot이 아직 claim되지 않은 상태다.
- `CONFIRMING`부터 paymentKey와 callback hash가 고정된다. exact replay는 같은 결과를 읽고
  다른 payload는 상태를 바꾸지 않고 거부한다.
- `UNKNOWN`과 `RECONCILING`은 같은 paymentKey의 Provider query만 허용한다. 새 confirm과
  PaymentMethod fallback은 없다.
- one-time attempt의 provider order/customer/order name/amount/currency/callback URL은 prepare 뒤
  immutable이며 DB trigger와 CHECK/UNIQUE 제약으로 보호한다.

Order가 이미 `EXPIRED` 또는 `CANCELLED`인 뒤 승인 사실이 확인되면 Payment는
`RECONCILING`에 남고 별도 `LATE_VOID -> LATE_REFUND` recovery 상태가
`SUCCEEDED` 또는 `MANUAL_REVIEW`에 도달한다. 이 경로는 Order 상태를 바꾸지 않는다.

Payment의 표시 상태 `PARTIALLY_REFUNDED`와 `REFUNDED`는 성공한 Refund 합계에서
파생한다. 승인 결과와 환불 시도 결과를 하나의 enum으로 덮어쓰지 않는다.

`PaymentConfirmation.recovery`는 승인 결과 불명과 조회 복구의 상태·시각만 가진
`PaymentApprovalRecoverySummary`를 사용한다. 고객 취소 환불 projection은 별도
`CancellationRefundRecoverySummary`이며 두 schema와 enum을 공유하지 않는다.

## PaymentMethod

```text
registration work: READY -> PROCESSING -> COMPLETED(201)
                               |-> REJECTED(422)
                               |-> MISCONFIGURED_RETRYABLE -> PROCESSING
                               |-> REGISTRATION_UNKNOWN -(lookup 미지원, 같은 Tx)-> MANUAL_REVIEW

PaymentMethod: ACTIVE -> DEACTIVATION_REQUESTED
                    -> DEACTIVATION_UNKNOWN -> MANUAL_REVIEW
                    -> RECONCILING
                    -> DEACTIVATED

ACTIVE | DEACTIVATION_REQUESTED | DEACTIVATION_UNKNOWN
       | RECONCILING | MANUAL_REVIEW
       -- verified BILLING_DELETED --> DEACTIVATED
```

- registration `Issued`만 PaymentMethod `ACTIVE`를 만든다. raw authKey는 저장하지 않고 claim 뒤
  결과불명에서는 Provider registration을 재호출하지 않는다. 현재 lookup Port가 없으므로 direct
  Unknown, result 저장 실패와 stale startup claim은 고객 delayed 202를 보존한 채 즉시
  `MANUAL_REVIEW`로 종결한다.
- `MISCONFIGURED_RETRYABLE`은 side effect 부재가 확인되고 설정이 수정된 same-key registration에만
  새 claim을 허용한다. deactivation에는 이 재호출 상태가 없다.
- `DEACTIVATION_REQUESTED`부터 신규 Payment 선택을 거부하며 default를 해제한다. 확인된 Provider
  성공 또는 검증된 notification만 `DEACTIVATED`로 단조 전이한다.
- deactivation unknown은 DELETE를 재호출하지 않고 96시간 webhook 창 뒤
  `MANUAL_REVIEW`로 간다. 어느 상태도 `ACTIVE`로 되돌아가거나 row를 hard delete하지 않는다.
- 고객 projection은 내부 진행·불명·수동 상태를 `DEACTIVATION_PENDING`으로 축약한다. terminal
  `DEACTIVATED`는 일반 목록에서 제외한다.
- Payment Tx1이 먼저 만든 immutable Provider request snapshot은 이후 PaymentMethod 상태와
  독립적으로 기존 Payment 승인·lookup·late recovery에 사용한다.

## Refund

```text
REQUESTED -> PROCESSING -> SUCCEEDED
PROCESSING -> RETRY_SCHEDULED -> PROCESSING
PROCESSING -> FAILED
PROCESSING -> UNKNOWN -> RECONCILING
RECONCILING -> SUCCEEDED | FAILED | MANUAL_REVIEW
```

- `RETRY_SCHEDULED`는 Provider adapter가 부수효과 없음과 같은 key 재실행 안전을
  보장한 allowlist 명시 실패에만 사용한다. 최초 요청 뒤 10초·30초의 최대 두
  재요청만 허용한다.
- 한 번 `UNKNOWN`이 되면 `RETRY_SCHEDULED`나 `PROCESSING` REQUEST 경로로 돌아가지
  않고 같은 key `LOOKUP`만 수행한다.
- `UNKNOWN` 또는 `RECONCILING` 환불을 성공 환불액에 포함하지 않는다.
- 동일 refund idempotency scope와 source reference는 외부 부작용을 한 번만 만든다.
- 누적 `SUCCEEDED` 환불액은 승인액을 초과할 수 없다.
- 고객 `CancellationRefundRecoverySummary`는 내부 복구 상태를 직접 노출하지 않는다.
  `PROCESSING`·`RETRY_SCHEDULED`·`UNKNOWN`·`RECONCILING`은 고객 `PROCESSING`,
  `FAILED`·`MANUAL_REVIEW`는 고객 `PROCESSING + REFUND_DELAYED`로 투영한다.
- 완전한 recovery snapshot에서 누락 Refund를 2인 승인으로 복구한 경우 Refund는
  `RECONCILING`과 next action `LOOKUP`으로 생성된다. 새 REQUEST를 보내지 않는다.
- 내부 `SETUP_INCOMPLETE`는 Refund state가 아니라 recovery setup 무결성 상태다.
  고객에게는 `PROCESSING + REFUND_DELAYED`, 운영자에게는 missing artifact와
  invariant violation으로 노출한다.

## Reservation

```text
RESERVED -> CONFIRMED
RESERVED -> EXPIRED
RESERVED -> RELEASED
CONFIRMED -> RELEASED_AFTER_TERMINATION
```

같은 예약에 terminal transition은 한 번만 성공한다.

`RELEASED_AFTER_TERMINATION`은
`restorationTrigger = STORE_REJECTION | CUSTOMER_CANCELLATION`과
`restorationSourceReference`가 모두 필수이고 다른 상태에서는 둘 다 부재다. 기존
코드·DB의 `RELEASED_BY_REJECTION`은 forward migration에서 rename하고 거절 row는
`STORE_REJECTION`으로 backfill한다. ADR-059 release gate가 `PASSED`인 동안에는
backfill 대상 row가 0이므로 migration이 최종 상태 enum과 CHECK를 직접 만들며, 이
rename/backfill 규칙은 gate가 nonzero 또는 unknown이 될 때의 계약으로 남는다.

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
CouponReservation `RESTORED`는
`ORIGINAL_RESTORED | COMPENSATION_ISSUED | SKIPPED_EXPIRED` disposition과
restoration source·trigger·policy version을 함께 가진다. `SKIPPED_EXPIRED`는 새
가용 쿠폰이 없지만 정책 적용은 성공한 상태다.

## Point use

PointAccount 자체를 상태 enum으로 표현하지 않는다. 사용·복원은 append-only
PointTransaction과 PointLot 잔액으로 표현하고, 주문별 활성 예약과 사용 reference를
Unique Constraint로 보호한다. 환불 적립 회수의 실제 가용 잔액 차감은
`RECOVERY` PointTransaction이며, 회수하지 못한 부족액은 별도
`PointRecoveryPending` Aggregate다. 둘은 SettlementAdjustment가 아니다.

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
- 고객 취소도 같은 결과 transaction type을 사용하고 source,
  `CUSTOMER_CANCELLATION` trigger와 POINTS policy version ID로 원인을 구분한다.

## Point recovery pending

```text
PENDING -> SETTLED
```

- 성공 Refund 때 실제 회수한 Lot별 금액은 `RECOVERY` debit transaction으로 즉시
  기록한다. 남은 금액이 있을 때만 `PointRecoveryPending(PENDING)`을 만든다.
- `PENDING`은 `remainingAmountKrw > 0`, `SETTLED`는 `remainingAmountKrw = 0`이며
  되돌리거나 삭제하지 않는다.
- 이후 적립은 gross `ACCRUAL`을 기록한 뒤 오래된 PENDING부터 `RECOVERY`로 상계한다.
  상계 후의 net amount만 PointAccount와 새 PointLot의 가용 잔액이 된다.
- 같은 refund/Lot 또는 pending/적립 source의 재처리는 기존 동일 결과만
  `ALREADY_APPLIED`이고, 금액·대상 불일치는 공통 source replay 충돌 코드인
  `IDEMPOTENCY_KEY_REUSED`다.

## Audited point adjustment

`ADJUSTMENT`는 별도 state machine이 아니라 PointAccount lock 아래 완료되는 manual
command다. CREDIT은 입력 issuer snapshot과 future expiry의 새 Lot을 만들고, DEBIT은
`expiresAt > now`인 available Lot을 선소멸 순서로 줄인다. storage의 `balance_effect`가 CREDIT/DEBIT을
보존하며, AuditRecord·IdempotencyRecord·PointTransaction 중 하나라도 실패하면
상태 전이 없이 전체 rollback한다. `ADJUSTMENT`는 PointRecoveryPending을 settle하지
않는다.

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
- 고객 취소 CUSTOMER_NOTIFICATION step은 접수 Delivery만 추적한다. 환불 성공·지연
  후속 Delivery는 이 step을 다시 열지 않는다.

## Acceptance timeout work

```text
PENDING -> CLAIMED -> COMPLETED
CLAIMED -> PENDING | MANUAL_REVIEW
```

같은 Order와 acceptance deadline source에는 work가 하나다. in-memory wakeup은
latency optimization이고 durable row와 periodic worker가 복구 근거다.
`COMPLETED` outcome은 timeout 거절을 확인한 `REJECTED` 또는 deadline 전 수락을 확인한
`NOT_APPLICABLE`만 허용한다. source/deadline 불일치는 성공으로 추정하지 않고
`MANUAL_REVIEW`로 보낸다. claim lease는 1분이고 최초 포함 최대 네 번 실행하며 실패 뒤
1초·5초·30초에 재시도한다. `PENDING`, `CLAIMED`, `MANUAL_REVIEW`는 retention cleanup
대상이 아니고 `COMPLETED`만 완료 시각부터 90일 뒤 chunk cleanup한다.

## Repair proposal

```text
PENDING_APPROVAL -> EXECUTED | REJECTED | EXPIRED | STALE
```

제안자는 자기 proposal을 결정할 수 없다. `APPROVE`는 Order→Payment 잠금 아래
safe-repair guard를 다시 검증해 통과할 때만 `EXECUTED`와 누락 Refund를 같은
transaction에서 commit한다. terminal proposal은 다시 열지 않는다.

## Settlement Batch

```text
OPEN -> CALCULATED -> CONFIRMED
```

`CONFIRMED` 이후 Item 또는 Batch를 수정하지 않고 Adjustment를 추가한다.
SettlementItem과 SettlementAdjustment는 append-only 원장 항목이므로 별도 수정 상태
머신을 두지 않는다. `CALCULATED` summary에는 calculation 시각까지 생성된 Adjustment의
high-watermark와 직전 confirmed Batch의 음수 carry source가 고정되며 재실행은 같은 상태를
반환한다. 이전 날짜 Batch가 미확정이면 다음 날짜 계산을 시작하지 않는다.

## Dispute

```text
FILED -> UNDER_REVIEW -> ACCEPTED | REJECTED | WITHDRAWN
```

재이의제기는 이전 Dispute를 참조하는 별도 Aggregate instance다.

- `FILED` 시 대상 예상 조정액을 held amount로 기록한다.
- `FILED` 접수는 confirmed Batch의 서울 날짜 D 기준 `[D+1 00:00, D+15 00:00)`에서만
  허용하며 active Item partial unique와 advisory lock으로 동시 요청을 하나로 수렴한다.
- `ACCEPTED`는 Settlement에 Adjustment 생성 명령을 보내고, 성공한 원천 reference를
  중복 생성하지 않는다. Adjustment가 별도 transaction에서 commit된 뒤에만 Dispute를
  terminal로 저장한다. 후속 transaction 실패 시 `UNDER_REVIEW`와 재처리 Case를 유지한다.
- `REJECTED` 또는 `WITHDRAWN`은 held amount를 해제한다.
- terminal Dispute 뒤 재접수는 immediate previous ID와 이전 배열에 없던 evidence reference를
  요구하며 별도 Aggregate instance로 한 번만 허용한다.

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
`MANUAL_REVIEW`는 처리 중 상태가 아니며 동일 key에는 non-retry
`IDEMPOTENCY_MANUAL_REVIEW_REQUIRED`를 반환한다. reason, review 시작 시각과 intended Order
존재 여부를 보존하고, 별도 감사형 운영자 command 없이 terminal 상태나 response를 추정하지 않는다.

고객 취소와 매장 전이 command idempotency row는 저장 시점에 terminal이고 별도
PROCESSING state가 없다. 같은 key/payload에는 최초 status/body를 재생하며 business
response에 replay indicator를 넣지 않는다. 두 table은 createdAt+90일 뒤 통합
Ordering retention worker가 table별 독립 chunk로 정리한다.

감사형 포인트 조정도 terminal `201`만 저장하는 Loyalty-owned command idempotency row를
사용한다. account/hash가 같은 요청만 재생하고, 다른 account 또는 hash는 409이다.
`createdAt + 90일` 뒤에는 별도 Loyalty keyset retention worker가 정리하며 Ordering worker가
그 table을 정리하지 않는다.

## Reprocessing case

SupportCase, VerificationSession, DataAccessGrant, ActionRequest, Approval and Resolution target states are
defined in [Support State Machines](support-state-machines.md). The S20 SupportCase portion is a current persisted/API
contract; Verification, Grant, ActionRequest, Approval and Resolution remain planning contracts. Canonical Delivery
lifecycle and independent ProviderSyncStatus are defined in [Delivery State Machine](delivery-state-machine.md).

```text
OPEN -> RUNNING -> RESOLVED
RUNNING -> RETRY_SCHEDULED | MANUAL_REVIEW
RETRY_SCHEDULED -> RUNNING
```

7일을 초과한 Analytics late event는 자동으로 과거 지표를 바꾸지 않고
`BACKFILL_REQUIRED` 유형의 `OPEN` case를 만든다.
