# Transaction Boundaries

## General rule

한 트랜잭션에서 강한 일관성이 반드시 필요한 Aggregate만 함께 변경한다. 외부 네트워크 호출은 트랜잭션 경계 밖에서 실행한다.

## Order creation

Initial decision:

- Tx I1에서 intended Order ID, scope, canonical payload hash와
  `IdempotencyRecord(PROCESSING)`을 짧게 먼저 커밋한다.
- Order, 슬롯·재고·쿠폰·포인트 예약은 같은 PostgreSQL 배포 단위의 로컬 트랜잭션에서 공개 Application API를 통해 조정한다.
- Ordering이 다른 모듈의 Repository를 직접 호출하지 않는다.
- Merchant applicable settlement terms, Promotion CouponReservation final burden legs와 Loyalty
  PointReservation issuer allocations이 모두 검증된 뒤 같은 transaction에
  `OrderSettlementInputSnapshot`을 저장한다. source/snapshot tie-out failure는 Order·예약의
  부분 성공이나 default fee/cost로 대체하지 않고 전체 rollback한다.
- 2026-08-02 Plan 15 구현은 V18–V20 owner source FK/trigger와 Order unique snapshot을 이
  경계에 연결했다. snapshot insert failure, missing terms, coupon burden 또는 cross-store
  point issuer는 `SETTLEMENT_INPUT_UNAVAILABLE`로 Order·Pickup·Stock·Coupon·Point 변경을
  함께 rollback한다. concurrent future terms publication은 주문 시각에 적용되는 기존 version을
  바꾸지 않는다.
- Order와 OrderLine을 저장한 뒤 같은 transaction에서 Operations의 typed selector로
  STORE override 또는 현재 GLOBAL version을 선택한다. Ordering은 선택된 immutable policy values,
  canonical hash, selection source, 주문 실결제액과 conceptual-unit별 현금·적립 배분을
  `OrderPointAccrualSnapshot`으로 저장한다.
- policy 선택은 STORE scope shared advisory lock 뒤 명시적 STORE head를 shared lock으로 읽고,
  fallback이 필요할 때만 GLOBAL head를 shared lock으로 읽는다. STORE policy write는 같은 advisory
  key의 exclusive lock과 head write lock을 사용한다. 따라서 동시 Order 선택은 허용하면서 정책 변경과
  선형화된 Order는 변경 전 또는 변경 후 version 중 하나만 사용하며 두 version의 값을 섞지 않는다.
- Order/OrderLine flush, policy selection 또는 point accrual source/header/unit flush가 실패하면
  Order, 모든 owner 예약·benefit-only 승인, Audit와 성공 idempotency response를 함께 rollback한다.
  missing policy를 0 bps, current cache 또는 legacy marker로 대체하지 않는다.
- 일부 실패 시 주문과 모든 예약을 롤백한다.
- Merchant는 정규화한 메뉴·옵션 구성을 sellable unit 요구량으로 번역하고,
  Ordering은 같은 unit 요구량을 주문 전체에서 합산하여 Inventory 공개 API에
  전달한다. Inventory는 메뉴·옵션을 역으로 조회하지 않는다.
- payable이 0이면 같은 주문 생성 트랜잭션에서
  `BENEFIT_ONLY Payment(APPROVED)`를 만들고 네 예약을 확정한 뒤 Order를 `PAID`로
  커밋한다. 중간 `PENDING_PAYMENT`와 `RESERVED` 상태는 외부에 커밋하지 않으며
  외부 Provider를 호출하지 않는다.
- 성공 Tx O는 Order, 예약, AuditRecord와 IdempotencyRecord의
  `COMPLETED + 최초 201 response`, complete `OrderPointAccrualSnapshot`을 함께 커밋한다.
- 확정 실패로 Tx O가 rollback되면 별도 Tx I2가 `FAILED + 최초 error response`를
  저장한다. Tx I1 뒤 멈춘 PROCESSING은 intended Order와 owner source를
  reconciliation하며 새 주문을 자동 실행하지 않는다.

Revisit when:

- Lock Wait 또는 transaction duration이 측정된 병목이 됨
- 독립 서비스 분리가 요구됨
- 보상 Saga의 운영 비용을 감당할 필요가 생김

## Fast reorder

빠른 재주문은 별도 reservation transaction을 만들지 않고 주문 생성의 Tx I1/Tx O/Tx I2를
그대로 사용한다.

```text
Tx I1: actor + REORDER_ORDER_V1 + key unique arbitration
       + canonical payload hash + intended new Order ID + PROCESSING
commit
Tx O:  source Order lock + owner/state/option snapshot 검증
       + source menu/normalized option/quantity를 shared order-creation input으로 변환
       + current quote + Pickup/Stock/Coupon/Point reserve
       + new Order/immutable snapshots/Audit/priceComparison
       + idempotency COMPLETED + first 201 status/body
commit
Tx I2: Tx O의 확정 실패 status/body를 idempotency FAILED로 저장
```

- source Order lock은 snapshot 읽기와 향후 허용 상태 변화 경쟁을 일관되게 처리한다. source가
  terminal이 아니면 `REORDER_SOURCE_STATE_INVALID`이며 새 Order와 예약을 만들지 않는다.
- Tx I1과 Tx O 사이 또는 source read와 current owner validation 사이의 메뉴·옵션·가격·slot·재고·
  coupon·point 변화는 Tx O가 다시 읽고 잠근 현재 owner 상태로 판정한다. client가 보았던 과거 상태나
  사전 read를 보장하지 않는다.
- Tx O는 기존 주문 생성 owner orchestration을 transaction-mandatory internal shared boundary로
  추출해 direct create와 reorder가 함께 호출한다. Controller나 reorder service가 owner Repository를
  직접 호출하거나 shared boundary에 새 transaction propagation을 추가하지 않는다.
- source line 하나가 legacy이거나 current Merchant validation을 통과하지 못하면 모든 line failure를
  결정적 순서로 수집한 뒤 전체 rollback한다. 부분 Order나 부분 reservation은 없다.
- current quote가 성공하면 source/current 혜택 전 가격 비교와 changed-line 목록을 같은 Tx O의 최초
  201 body에 고정한다. replay가 현재 가격으로 다시 계산하지 않는다.
- Tx O가 rollback되면 owner reservation과 new Order가 남지 않는다. Tx I2 실패로 `PROCESSING`이
  남으면 reconciliation이 intended Order와 owner source를 검증하며 새 재주문을 자동 실행하지 않는다.
- canonical payload는 `sourceOrderId`, `pickupSlotId`, `couponIssuanceId`(null 포함),
  `pointsToUseKrw`다. source line은 immutable source Order로 식별되므로 payload에 중복하지 않는다.
- `COMPLETED`/`FAILED`는 최초 status/body와 함께 90일 retention 만료 시각을 갖는다.
  `PROCESSING`/`MANUAL_REVIEW`는 자동 정리하지 않는다.

## PaymentMethod lifecycle

```text
Tx R1: actor + REGISTER_PAYMENT_METHOD_V1 + key unique arbitration
       + canonical payload hash/authKey SHA-256
       + intended PaymentMethod ID + fixed TOSS provider
       + one CSPRNG provider customer reference + READY work
commit
Tx RC: READY work -> PROCESSING + claim token/time
commit
External registration Port once
Tx R2 issued: token fingerprint advisory lock
              + exact binding check + PaymentMethod ACTIVE
              + registration terminal 201 response
Tx R2 rejected: terminal 422 response
Tx R2 unknown: lookup 미지원이면 MANUAL_REVIEW + delayed 202 response
```

- raw authKey는 R1 payload hash 입력과 external request 메모리에서만 존재하고 DB·관측 데이터에
  저장하지 않는다. claim 이전 process loss만 같은 logical operation이 다시 claim한다. claim 뒤
  timeout·응답 유실은 authKey를 다시 보내지 않는다. lookup이 없는 현재 Port의 Unknown과
  result 저장 실패는 같은 result/recovery transaction에서 즉시 `MANUAL_REVIEW`로 종결한다.
  재기동 recovery는 `claim_started_at <= startupNow - claimStaleAfter`인 실제 stale `PROCESSING`만
  Provider 재호출 없이 `MANUAL_REVIEW`로 전이하고 fresh claim은 다른 인스턴스의 live call로 보존한다.
  기본 `claimStaleAfter`는 5분이며 실제 adapter의 전체 timeout과 grace 합보다 길어야 한다.
- registration result는 provider+token fingerprint transaction advisory lock 뒤 cross-owner row를
  확인한다. exact ACTIVE owner/reference/alias/brand/last4만 기존 resource로 수렴하고 다른 binding은
  overwrite·reactivation 없이 `MANUAL_REVIEW`다.
- `Misconfigured`가 외부 side effect 부재를 확인한 경우만 503으로 같은 key 재시도를 허용한다.
  다른 key의 같은 customer/provider/authKey hash는 Provider 호출 전에 거부한다.
- 위 재시도는 registration 전용 `MISCONFIGURED_RETRYABLE -> PROCESSING` 전이다. deactivation
  Misconfigured는 manual review로 가고 DELETE를 다시 호출하지 않는다.

```text
Tx M: customer-scope advisory lock
      + target ACTIVE PaymentMethod lock
      + previous default/target deterministic ID-order locks
      + default swap + terminal command 200 response
commit
```

- 새 PaymentMethod는 default가 아니다. default replay는 현재 선호를 다시 실행하지 않고 저장된 최초
  200을 반환한다. default는 Payment 승인 대상 추론에 쓰지 않는다.

```text
Tx D1: owner PaymentMethod lock + command arbitration
       + DEACTIVATION_REQUESTED + is_default=false + READY work
commit
Tx DC: READY work -> PROCESSING + claim token/time
commit
External deactivation Port once
Tx D2 success: DEACTIVATED + terminal 204
Tx D2 unknown: DEACTIVATION_UNKNOWN + unknown_at + deadline(unknown_at+96h)
Deadline worker: due unknown -> MANUAL_REVIEW, no Provider call
Startup recovery: interrupted PROCESSING -> DEACTIVATION_UNKNOWN, no Provider call
```

- D1 commit부터 새 Payment 준비와 목록의 active 선택에서 제외한다. Provider latency 동안 DB
  transaction/connection을 유지하지 않는다. claim 뒤 DELETE는 timeout·응답 유실·result 저장 실패를
  포함해 자동 재호출하지 않고 not-found를 성공으로 추정하지 않는다.
- terminal command 원장은 90일 뒤 bounded keyset worker가 정리할 수 있다. unknown/reconciling/manual
  work와 inbox는 운영 해소 전 정리하지 않는다.

```text
Tx W1: verified provider notification
       + (provider, notificationId) unique inbox accept
commit
Tx W2: token fingerprint mapping 0|1|many
       + token advisory, PaymentMethod row lock 뒤 active deactivation work lock
       + monotonic DEACTIVATED/stored 204 or MANUAL_REVIEW inbox result
commit before Provider 2xx
```

- 인증·서명 실패와 malformed transport는 W1에 들어오지 않는다. W1 또는 W2 commit 실패를 2xx로
  응답하지 않는다. raw token은 같은 delivery의 W2까지 메모리에서만 유지하고, 실패 replay가 다시
  제공한 token으로 non-terminal inbox를 처리한다. 0건/다건 mapping은 owner를 추정하지 않고 inbox를
  manual review에 두며, W2 terminal commit 뒤에만 2xx를 반환한다.
- D1, DC, D2, deactivation persistence-failure recovery, deadline/startup worker와 W2의 공통 mutation
  lock order는 `PaymentMethod → deactivation work`다. ID만 아는 경로는 non-locking method ID
  projection 뒤 method를 잠그고 work를 잠근다. W2도 method lock 뒤 active work를 다시 읽으므로
  D1의 미커밋 insert를 놓치지 않는다. W2가 Provider result보다 먼저 work를 stored 204로 완료하면
  뒤 D2는 새 Audit·상태 전이 없이 그 204를 반환한다.

## Payment approval

```text
Tx A: Order lock + amount/currency 검증 + Payment READY + prepare idempotency
      + immutable OneTimePaymentAttempt 저장
commit
Browser: Toss V2 Standard CARD 인증
Tx B: Payment lock + owner/providerOrder/amount/paymentKey binding + callback claim
commit
External Toss confirm
Tx C approved: Order lock + 네 예약 확정 + Order PAID + Payment/Attempt APPROVED + Audit
Tx C declined: Order lock + 네 예약 해제 + Order CANCELLED + Payment/Attempt FAILED + Audit
Tx C unknown: Payment/Attempt UNKNOWN + query reconciliation schedule
```

- DB connection을 Provider latency 동안 점유하지 않는다.
- one-time 경로는 PaymentMethod row와 Port를 호출하지 않는다. 저장형 token lifecycle과 checkout
  승인 소유권을 섞지 않는다.
- Tx A의 provider order/customer key/order name/amount/currency/success·fail URL과 Provider
  idempotency key는 이후 모든 confirm/query/refund가 재사용하는 immutable snapshot이다.
- Tx B는 callback hash와 paymentKey를 한 번만 claim한다. exact replay는 저장 결과를 반환하고
  다른 callback payload는 Provider 호출 전에 거부한다.
- timeout은 `UNKNOWN`일 수 있다.
- PG 성공 후 Tx C 실패는 reconciliation으로 복구한다.
- Tx A에서 최초 reconciliation due 시각을 함께 저장해 PG 성공 후 Tx C 전체 실패도
  stuck `APPROVING` 조회로 복구한다.
- Tx C 잠금 순서는 Order → Pickup → 정렬된 Stock → Coupon → Point →
  Payment/Idempotency/Audit다.
- 명시 거절은 422와 terminal idempotency result를 저장한다. timeout, 연결 오류,
  응답 유실과 해석 불가 응답은 거절로 바꾸지 않는다.
- `BENEFIT_ONLY`는 외부 호출 없이 같은 로컬 트랜잭션에서 Payment 승인 사실을
  확정하지만, 주문별 source reference와 IdempotencyRecord를 동일하게 보호한다.
- Payment approved amount는 immutable `OrderSettlementInputSnapshot.feeBaseKrw`와 같아야 한다.
  mismatch는 Order completion/event success로 전이하지 않고 reconciliation/manual-review로 남긴다.
- Toss Authorization은 secret key 뒤 `:`를 붙인 Basic credential을 사용하고 secret, paymentKey와
  authorization header를 로그·metric·API 응답에 남기지 않는다.

### Lease expiry while payment is unknown

```text
Tx E1: 5분 만료 시 Order PENDING_PAYMENT -> EXPIRED + 예약 자원 해제
commit
Provider reconciliation reports approved
Tx E2: expired-order late approval 기록 + void/refund recovery 생성
commit
External Provider void/refund
Tx E3: SUCCEEDED | UNKNOWN | RECONCILING | MANUAL_REVIEW 기록
```

- 만료 worker와 승인 reconciliation은 Order의 guarded transition으로 경쟁한다.
- `PAID`가 먼저 확정되면 만료가 실패하고 예약을 확정한다.
- `EXPIRED`가 먼저 확정되면 뒤늦은 승인은 Order와 예약을 되살리지 않는다.
- 외부 void/refund는 E2 트랜잭션 밖에서 실행하고 그 결과를 별도 트랜잭션에 기록한다.
- 실패·timeout은 새 승인 요청이나 성공 환불로 바꾸지 않는다.

### Lease expiry materialization

- scheduled worker, Order 조회와 결제 명령은 같은 idempotent expiry Application
  Service를 호출한다.
- `now >= reservationExpiresAt`이고 Order가 `PENDING_PAYMENT`이면 Order row를
  guarded lock한 뒤 Order `EXPIRED`, 슬롯·재고 예약 `EXPIRED`, 쿠폰 release와
  PointReservation release를 한 로컬 transaction에서 실행한다.
- 모든 owner release와 AuditRecord가 commit된 뒤에만 조회는 `EXPIRED`를 반환하고
  결제 명령은 `RESERVATION_EXPIRED`를 반환한다.
- 한 owner라도 실패하면 전체 rollback한다. 조회·결제는 503을 반환하고 다음 worker
  또는 요청이 같은 source reference로 재시도한다.
- 아직 due가 아니거나 이미 terminal이면 자원 수량을 바꾸지 않는다.

## Cancellation and refund

- Order 취소/거절 전이는 owner Context의 한 트랜잭션에서 한 번만 확정한다.
- 결제 승인 후 필요한 void/refund는 Order 트랜잭션과 분리된 Payment 명령이다.
- Refund REQUESTED와 idempotency record를 먼저 커밋하고 외부 Provider를 호출한다.
- Provider timeout은 Refund `UNKNOWN`과 reconciliation case를 남긴다.
- 원 Order의 취소·거절 상태를 환불 성공으로 간주하지 않는다.
- 성공한 환불 fact를 Loyalty와 Settlement가 각자의 원장에 멱등 반영한다.

### Store/platform partial refund

```text
Tx R1: Order lock + store/platform authorization
     + Payment lock + sorted successful line/point allocation locks
     + USED PointReservation/original allocation snapshot
     + PARTIAL_REFUND×POINTS policy snapshot
     + Refund REQUESTED + immutable line/point request rows + Audit
commit
Provider request/lookup (cash가 0이면 생략)
Tx R2 (Ordering result orchestration): Order lock + immutable settlement snapshot read
     + Payment lock + Refund SUCCEEDED/explicit failure/unknown
     + 성공일 때 Payment 누적액 + immutable line/point success rows
     + `PaymentRefundedV1` persistent target publications
     + points가 양수이면 durable restoration work + Audit
commit
Loyalty Tx R3 (REQUIRES_NEW): PointAccount + USED reservation + sorted allocation/Lot locks
     + original/compensation/skip PointTransaction + restoration ledger
     + 각 owner result의 `PointsRestoredV1` persistent publication
commit
Payment Tx R4: restoration work SUCCEEDED | RETRY_SCHEDULED | MANUAL_REVIEW ack
```

- `R1`이 실패하면 Refund/request snapshot/Audit가 모두 rollback되고 Provider를 호출하지 않는다.
- Provider latency 동안 DB transaction/connection을 유지하지 않는다. `R2` 실패는 claim lease와
  같은 Provider key lookup으로 수렴하며 성공으로 추정하지 않는다. Provider 결과 시각은 외부 호출이
  끝난 뒤 기록하고 claim 시각을 성공 시각으로 재사용하지 않는다.
- `R2`는 Order를 먼저 잠근 뒤 immutable snapshot을 읽고 Payment 공개 API의 owner write를 같은
  transaction에 참여시킨다. Refund success/allocation/snapshot/source/target publication 중 하나라도
  없거나 저장에 실패하면 전체 result transaction을 rollback한다.
- 성공 unit은 가장 작은 미소비 conceptual position부터 고정한다. 실패·불명 Refund는 성공
  allocation을 만들지 않아 position과 승인액을 소비하지 않는다.
- coupon 금액은 Payment allocation 귀속 원장일 뿐 Promotion 상태 전이나 복원 호출이 아니다.
- `R3`은 Payment Entity/Repository를 직접 변경하지 않고 `R4`와 다른 local transaction이다.
  Loyalty commit 뒤 `R4` 실패는 같은 Refund source replay로 재확인한다.
- 부분 복원은 PointReservation `USED`와 reservation-level restoration metadata를 변경하지 않는다.
- R1/R2의 전역 순서는 `Order → immutable settlement snapshot → Payment → 정렬된 Refund allocation →
  정렬된 원 point allocation`이다.

### Customer cancellation

`PAID` acceptance deadline branch:

```text
Tx CT: Order lock + deadline/state revalidation
     + deduplicated AcceptanceTimeoutWork
     + ACCEPTANCE_TIMEOUT_WORK_REQUESTED AuditRecord
commit
HTTP 409 ORDER_STATE_CONFLICT
After commit: wake existing timeout worker
```

- Tx CT는 고객 취소 field, cancellation IdempotencyRecord, Refund, compensation
  Case와 customer-cancellation event를 만들지 않는다.
- work 또는 Audit 저장 실패는 rollback과 `503 DEPENDENCY_UNAVAILABLE`이며, periodic
  timeout scan이 나중에 처리할 수 있다는 이유로 409를 반환하지 않는다.
- C0/C1 replay scope는 owned Order를 먼저 잠근 뒤 `actorId + operation + Idempotency-Key`의
  PostgreSQL transaction advisory lock을 얻는다. 같은 key의 교차 Order 요청도 한 scope로
  직렬화한 뒤 canonical payload hash 불일치로 거부하며, advisory lock은 DB row나 성공
  응답을 대신하지 않는다.

`PENDING_PAYMENT`:

```text
Tx C0: Order lock + ownership/deadline/idempotency 검증
     + Order CANCELLED + 네 예약 해제 + target별 AuditRecords
     + ORDER_CANCELLATION_ACCEPTED NotificationDelivery PENDING
     + 최초 200 response와 cancellation command idempotency 저장
commit
Notification worker: claim transaction -> external Provider -> result transaction
```

- 취소 event, OrderCompensationCase, Refund와 event publication을 만들지 않는다.
- NotificationDelivery 저장 실패는 Tx C0 전체를 rollback한다. `200`은 Provider
  발송 성공이 아니라 delivery work의 내구 저장 완료를 뜻한다.

미수락 `PAID`:

```text
Tx C1: Order lock + ownership/deadline/idempotency 검증
     + Order CANCELLED와 cancellation fields
     + OrderCompensationCase와 여섯 steps
     + Payment cancellation recovery snapshot
     + 남은 refundable cash가 양수이면 그 금액의 Refund REQUESTED
     + ORDER_CANCELLATION_ACCEPTED NotificationDelivery PENDING
     + 변경·생성 target별 AuditRecords
     + OrderCancelledV1과 Pickup, Stock, Coupon, Points persistent publications
     + 최초 202 response와 cancellation command idempotency 저장
commit
After commit: Pickup, Stock, Coupon, Points owner listeners
Refund worker: claim transaction -> external Provider -> result transaction
Notification worker: claim transaction -> external Provider -> result transaction
```

- Tx C1 항목 중 하나라도 저장되지 않으면 전체 rollback하고 `202`를 반환하지 않는다.
- `202`는 외부 환불, 자원 복원 또는 알림 성공을 뜻하지 않는다.
- 외부 Provider 호출과 픽업·재고·쿠폰·포인트 복원은 Tx C1에 포함하지 않는다.
  NotificationDelivery 생성은 포함하되 Provider 호출은 포함하지 않는다.
- Tx C1은 Payment와 성공 refund allocation을 잠가
  `approvedAmountKrw - succeededRefundAmountKrw`를 계산한다. 선행 성공 부분 환불이
  있어도 취소를 거부하지 않으며 새 Refund와 성공 누적액의 합이 승인액을 넘지 않게
  DB 제약과 guarded write로 보호한다.
- 선행 Refund가 `REQUESTED`, `PROCESSING`, `RETRY_SCHEDULED`, `UNKNOWN`,
  `RECONCILING`, `MANUAL_REVIEW`이면 Tx C1의 Order 전이 전에
  `409 PAYMENT_REFUND_UNRESOLVED`로 rollback한다. `FAILED`는 합계에서 제외하고
  `SUCCEEDED`만 snapshot에 포함한다.
- refund 관련 전역 잠금 순서는 `Order → Payment → 정렬된 Refund allocation`이다.
  Order가 필요 없는 Refund 작업은 Payment부터 시작하되 Payment 뒤에 Order를 잠그지
  않는다.
- Payment cancellation recovery snapshot은 승인액, 취소 전 성공 환불액, 이번 취소
  요청액과 고객 취소 Refund ID를 보존한다. `remainingRefundableAmountKrw`는 저장하지
  않고 조회 시점의 성공 Refund 합계에서 계산한다.
- `BENEFIT_ONLY`는 snapshot 0/0/0과 null Refund ID, PAYMENT step
  `NOT_REQUIRED`를 Tx C1에 저장한다. Refund와 Provider 호출은 만들지 않으며 다른
  다섯 보상 step은 일반 `PAID` 취소와 같다. NotificationDelivery는 직접 저장하고
  네 자원 publication만 생성한다.
- 매장 거절과 고객 취소의 원 transaction은 해당 trigger의 COUPON, POINTS policy
  head를 이 순서로 잠그고 두 immutable version을 선택한다. Case의 두 benefit
  policy FK row와 event의 두 전체 snapshot을 같은 transaction에서 저장한다.
  정책 변경과 경쟁하면 두 snapshot이 모두 변경 전 또는 모두 변경 후여야 하며
  혼합 version은 허용하지 않는다.
- Tx C1이 생성하는 고객 취소 Refund는 reason
  `CUSTOMER_ORDER_CANCELLED`, 별도 `customer_reason_code`, source reference
  `order:{orderId}:customer-cancellation:{aggregateVersion}:payment`와 Provider key
  `refund:customer-cancellation:{orderId}:{aggregateVersion}`를 함께 저장한다.
- Refund worker는 adapter allowlist의 명시적 무부수효과 실패에만 동일 key
  `REQUEST`를 10초·30초 뒤 최대 두 번 재실행한다. 어느 REQUEST든 결과가 불명확하면
  REQUEST를 중단하고 동일 key `LOOKUP`을 10초, 30초, 2분, 5분, 15분 뒤 최대 다섯
  번 수행한다. request와 lookup count는 별도 transaction 상태로 보존한다.
- owner listener 실패는 publication과 OrderCompensationStep에 남고 Order
  `CANCELLED`를 되돌리지 않는다.
- 같은 key·payload의 응답 재생은 저장된 최초 `202` body만 반환하며 Tx C1이나
  event를 다시 실행하지 않는다.

## Order completion

- Plan 15는 주문 생성 transaction에서 `OrderSettlementInputSnapshot`과 V2 payload factory/validator의
  immutable input을 materialize한다. Plan 15는 completion outbox를 저장하지 않는다.
- 2026-08-02 Plan 15 checkpoint에는 public `OrderCompletedV2` payload와 factory/validator/fixture만
  존재한다. factory는 completed Order fact, approved Payment fact와 snapshot source/time/currency/
  formula를 검증하지만 Order transition이나 event publication을 수행하지 않는다.
- Plan 20은 V1 publication drain/deployed consumer inventory가 verified zero일 때만 existing Ordering
  completion producer를 V2로 cut over한다. Ordering의 guarded `COMPLETED` transaction은 immutable snapshot과
  matching Payment approval payable tie-out을 검증하고 `OrderCompletedV2` outbox를 Order transition과
  atomically 저장한다.
- snapshot/factory validation failure는 transition을 막고, outbox save failure는 guarded local transaction을
  rollback해 completion publication 성공을 반환하지 않는다. external Provider와 Settlement consumer 호출은
  이 transaction에 넣지 않는다; 외부 결과가 없는데 reconciliation success state를 추정하지 않는다.
- 포인트 적립, 정산 항목, 알림과 분석은 idempotent after-commit 처리다. 그 부수효과 실패로 이미
  commit된 완료 주문을 되돌리지 않는다.

## Point use

- PointAccount 요약과 실제 소비할 PointLot만 같은 트랜잭션에서 잠근다.
- 만료가 빠른 Lot부터 차감한다.
- 원장과 요약 잔액을 함께 갱신한다.
- 동일 주문 사용 reference를 Unique Constraint로 방어한다.
- 주문 생성 예약은 `expiresAt > now`인 PointLot을 `(expiresAt, pointLotId)` 순서로
  잠그고 PointReservation allocation, Account/Lot available·reserved 요약을 같은
  주문 생성 트랜잭션에서 갱신한다.
- 예약 시 유효했던 allocation은 주문 lease 동안 사용 확정할 수 있다. PointLot
  만료 worker는 available 금액만 만료하고 reserved allocation을 임의로 해제하지
  않는다.
- Order 만료·취소 transaction에서 PointReservation을 해제할 때 아직 유효한
  allocation은 available로 복원하고 이미 만료된 allocation은 EXPIRATION 원장으로
  확정한다. 일부만 실패하면 Order와 모든 자원 해제를 함께 롤백한다.

## Point refund recovery and later accrual

- 성공 Refund event를 처리하는 Loyalty transaction은 PointAccount를 먼저 잠근 뒤
  회수 가능한 available PointLot을 `(expiresAt, pointLotId)` 순서로 잠근다. 실제
  차감 Lot, `RECOVERY` transaction, 필요하면 PointRecoveryPending과
  `recoveryPendingKrw` summary를 함께 commit하거나 rollback한다.
- 이후 OrderCompleted 적립 transaction은 PointAccount를 먼저 잠그고, 새 PointLot과
  gross `ACCRUAL` transaction을 만든 뒤 오래된 PENDING 행을 `(createdAt, id)` 순서로
  잠근다. 상계 `RECOVERY` transaction, pending state와 Account/Lot summary는 같은
  local transaction에 속한다. gross `ACCRUAL` owner result가 있으면 `PointsAccruedV1`
  target publication도 같은 transaction에 속하며 pending 상계 뒤의 net 잔액으로 event amount를
  바꾸지 않는다.
- Refund 복원 transaction은 각 original/compensation/skip PointTransaction과 restoration result,
  해당 `PointsRestoredV1` target publication을 함께 commit한다. publication 저장 실패 시 Loyalty
  transaction은 rollback하고 Payment restoration work는 `RETRY_SCHEDULED`로 남긴다.
- Payment Provider 또는 다른 외부 호출은 위 Loyalty transaction에 넣지 않는다. Refund가
  성공했지만 Loyalty 처리에 실패하면 event publication/retry는 남고, Account를 0 또는
  성공 상태로 추정하지 않는다.
- Payment Refund source, Lot별 recovery source와 pending/적립 source의 UNIQUE가 중복
  event 재처리를 막는다. 같은 source의 금액·대상 불일치는 명시적 conflict이며 덮어쓰지
  않는다.

## Audited point adjustment

- `POST /operations/point-accounts/{accountId}/adjustments`는 ADR-064의 명령 transaction
  모델을 쓴다. Application Service가 PointAccount를 먼저 잠그고 CREDIT이면 새 Lot을,
  DEBIT이면 `expiresAt > now`인 `(expiresAt, pointLotId)` 순서의 available Lot을 잠근다.
- Account/Lot summary, Lot별 `ADJUSTMENT` transaction과 `balance_effect`, terminal
  IdempotencyRecord, target AuditRecord, PointsAdjustedV1 persistent event와 최초 201
  response는 하나의 local transaction에 속한다. 외부 Provider·issuer lookup fallback·
  Analytics consumer 호출은 이 transaction에 넣지 않는다.
- Operations public authorization API가 같은 transaction에서 active `POINT_ADJUSTMENT`
  grant를 잠근다. role/JWT claim은 coarse gate일 뿐 grant lookup failure/absence의 fallback이
  아니며, grant/Audit failure는 전체 rollback과 503이다.
- debit 가능한 Lot이 부족하거나 issuer/expiry/reason/evidence contract가 맞지 않으면
  모든 local write를 rollback한다. PointRecoveryPending, 음수 Account 또는 partial
  debit으로 성공을 대신하지 않는다.
- terminal adjustment idempotency row는 `created_at + 90일`까지 보존한다. Loyalty-owned
  worker가 `(retention_expires_at, id)` keyset 순서로 bounded chunk를 독립 transaction에서
  정리하며, Ordering worker나 일반 API가 이 table을 삭제하지 않는다.

## Expired-benefit policy and operator grant

policy GET은 method-security의 coarse `PLATFORM_OPERATOR` gate 뒤 Operations local transaction에서
active `EXPIRED_BENEFIT_POLICY_READ` grant row를 `FOR UPDATE`로 잠근다. 정확히 다섯 head/version을
읽고 access reason Audit를 flush한 뒤 transaction이 commit된 경우에만 body를 반환한다. grant가 없거나
revoked면 403, grant/head/version/Audit persistence가 실패하면 503이며 cached/default policy를 반환하지
않는다.

keyed PATCH의 lock/write 순서는 다음과 같다.

```text
active EXPIRED_BENEFIT_POLICY_WRITE grant row
-> actor+Idempotency-Key advisory transaction lock
-> trigger+benefit policy head FOR UPDATE
-> immutable version INSERT
-> head expected-version CAS
-> AuditRecord flush
-> commit
```

같은 payload replay는 기존 immutable version을 반환하고 새 version/Audit를 만들지 않는다. payload가
다르거나 expected version이 stale이면 409다. `PARTIAL_REFUND/COUPON`은 head/version을 만들지 않고
404다.

offline permission bootstrap은 mounted workload token의 signature, issuer, audience, subject, `exp`,
`nbf`와 deployment-run claim 검증을 Spring context와 DB transaction보다 먼저 끝낸다. 검증 뒤 transaction은
actor+permission advisory lock, existing grant row `FOR UPDATE`, state/version write, Audit flush 순서다.
authorization transaction이 active grant row를 먼저 잠그면 revoke가 그 commit까지 대기하고, revoke가
먼저 commit되면 뒤의 privileged transaction은 403이다. 외부 network 호출은 어느 transaction에도 없다.

## Operator permission bootstrap and point-account read

- offline `operator-permission-bootstrap` command는 read-only mounted token file의 단기 OIDC
  workload identity를 required issuer, audience와 allowed subject로 검증한 verified deployment
  release principal만 허용한다. identity 검증은 Operations transaction 전에 완료하며 token/trust
  설정 누락·불일치·만료·검증 key 실패 시 transaction을 시작하지 않는다. 검증 뒤
  actor/permission/reason/evidence를 확인하고 `OperatorPermissionGrant` version/state와 target
  AuditRecord를 하나의 Operations transaction에서 저장한다. direct SQL/default grant/static
  secret/application JWT/role fallback은 이 transaction을 대체하지 않는다.
- customer point-account read는 Loyalty Query Service가 account ownership을 먼저 확인한 뒤
  read-only projection으로 실행한다. Platform Operator branch는 `POINT_ACCOUNT_READ` grant,
  normalized `X-Access-Reason`, target AuditRecord와 projection을 하나의 local transaction에서
  저장한 경우에만 body를 반환한다.
- operator grant/Audit failure는 503이고 missing/revoked grant is 403이다. ledger cursor is
  `(occurredAt DESC, transactionId DESC)`이며 account scope가 다른 cursor를 재사용하지 않는다.

## Nearby store search

- nearby read는 write가 없는 단일 read-only transaction이다. Discovery가 좌표·radius·limit·cursor를
  먼저 검증하고, 검증을 통과한 뒤에만 Merchant public Query API를 호출한다. invalid input에서는
  spatial query를 실행하지 않는다.
- Merchant Query Repository가 `merchant_store_discovery_profile`과 현재 `merchant_store` state를
  하나의 PostGIS native projection으로 읽는다. Discovery는 Merchant Entity/Repository를 직접
  호출하지 않고 영속 복제본이나 동기화 event를 만들지 않는다.
- 이 transaction은 AuditRecord, domain event, cursor row 어느 것도 저장하지 않는다. cursor는
  stateless HMAC token이며 응답 직전에 발급한다.
- 고객 좌표는 transaction 범위의 query value로만 전달하고 응답, error detail, log, trace, metric
  tag에 넣지 않는다. spatial query 또는 transaction commit 실패는 503이며 빈 결과, cached 결과
  또는 애플리케이션 거리 계산으로 대체하지 않는다.
- `merchant_store_discovery_profile` 쓰기는 이번 범위에 없다. Store write use case가 생기면 Store와
  required profile을 같은 Merchant transaction에서 생성해야 startup coverage gate를 만족한다.

## Store catalogue read

- 메뉴와 픽업 슬롯 read는 각각 owner의 read-only transaction 하나다. Discovery는 HTTP 계약과 응답
  투영만 소유하고 Merchant `StoreMenuQueryOperations`, Fulfillment `PickupSlotQueryOperations`와
  Merchant `StorePolicyScopeOperations`만 호출한다. 다른 Context의 Repository나 Entity는 쓰지 않는다.
- Store 존재 확인은 catalogue owner인 Merchant가 수행한다. 없는 Store는 404이고 영속 실패는
  503이며, 실패를 404나 빈 목록으로 바꾸지 않는다. 메뉴가 없는 Store와 열린 슬롯이 없는 Store는
  정상적인 빈 목록 200이다.
- 두 read 모두 write, event, AuditRecord를 만들지 않으므로 멱등 record가 필요 없다. 응답은 조회
  시점 owner state이며 예약이나 가격 보장이 아니다. 슬롯 잔여 capacity는 조회 직후 동시 예약으로
  바뀔 수 있고, 실제 예약은 슬롯 row를 잠그고 capacity를 다시 확인하는
  `PickupReservationOperations.reserve`가 결정한다.
- 메뉴 projection은 메뉴 목록과 옵션 목록 두 statement, 슬롯 projection은 한 statement로 고정한다.
  카탈로그 크기에 따라 statement 수가 늘지 않으며 Aggregate 간 JPA 연관관계를 추가하지 않는다.

## Settlement

- `OrderCompletedV2` Settlement consumer는 Ordering producer와 별도의 local transaction에서 immutable
  event payload와 source unique를 검증하고 `(storeId, settlementDate)` `OPEN` Batch를 insert-or-read한 뒤
  SettlementItem, Audit과 `SettlementItemCreatedV1` publication을 같은 transaction에 저장한다. Batch 또는
  Item 저장 실패는 event completion이 아니다.
- consumer는 Merchant, Campaign, PointLot, `OrderSettlementInputSnapshot` 또는 Payment의 current state를
  재조회해 V2 field를 보완하거나 producer tie-out을 다시 계산하지 않는다. missing/inconsistent payload는
  retry 또는 `MANUAL_REVIEW`로 남기며 live default로 대체하지 않는다.
- SettlementItem 생성은 원천 거래 reference 단위로 멱등하며 `settlementBatchId` FK가 필수다.
- Batch 집계와 상태 전환은 Item 전체를 Entity 컬렉션으로 로딩하지 않는다.
- 확정 후 환불·판정은 별도 Adjustment 트랜잭션이다.
- `SettlementItem` 생성의 기준 fact는 `OrderCompletedV2`다. `PaymentApproved`만으로
  Item을 생성하지 않는다.
- Payment 환불 fact는 미확정 Item 반영 또는 확정 후 Adjustment 생성의 입력이며,
  원천 refund reference를 Unique Constraint로 보호한다.
- `CALCULATED` 또는 `CONFIRMED` Batch로 닫힌 뒤 늦게 도착한 Item source는 Batch를
  바꾸거나 0원 Adjustment를 만들지 않고 source-unique ReprocessingCase로 남긴다.
- `PaymentRefundedV1` 고객 취소 제외 consumer는 별도 Settlement local transaction에서 public
  Ordering/Payment evidence API로 Order terminal cause/lifecycle과 Refund 성공 source/version/amount/time을
  읽고 Item 부재를 확인한다. 모두 일치할 때만 source-unique
  `SETTLEMENT_REFUND_EXCLUDED` Audit을 append하고 publication을 완료한다. Audit replay는 같은 key의
  기존 row로 수렴하며, mismatch·missing·기존 Item·Audit insert failure는 transaction을 실패시킨다.
- 이 evidence read는 Ordering/Payment Repository 직접 접근이나 외부 Provider 호출이 아니다.
  Order와 Refund는 terminal fact이며 consumer transaction은 그 값을 변경하지 않는다.
- Batch calculation transaction은 Batch row를 잠그고 이전 confirmed Batch를 확인한 뒤
  Item·Adjustment를 각각 500건 keyset projection으로 읽는다. summary, calculation 시각,
  Adjustment ingestion high-watermark와 carry source를 한 번에 저장하며 다른 Context나 외부
  Provider를 호출하지 않는다. confirmation은 별도 transaction에서 `CALCULATED → CONFIRMED`,
  target Audit와 `SettlementBatchConfirmedV1` publication을 원자 저장한다.
- completed-order `PaymentRefundedV1` consumer는 confirmed Item view를 검증하고
  `REFUND_SUCCEEDED` Adjustment, target Audit와 `SettlementAdjustmentCreatedV1`을 하나의
  Settlement transaction에 저장한다. unconfirmed Item은 0원/no-op으로 완료하지 않고 publication
  retry에 남긴다. 같은 source의 payload conflict는 source-unique ReprocessingCase를 연다.
- Dispute filing transaction은 Item/actor-key advisory lock, confirmed Item과 active membership
  확인, terminal idempotency response, Dispute/held, target Audit와
  `SettlementDisputeFiledV1` publication을 함께 commit한다. Audit/publication 실패에는 row와
  201 응답을 남기지 않는다.
- Dispute Context의 판정과 SettlementAdjustment 생성은 Context 간 별도 트랜잭션이다.
  `ACCEPTED`는 Settlement public command가 `REQUIRES_NEW`로 source-unique Adjustment를 먼저
  commit하고, 그 뒤 Dispute transaction이 `UNDER_REVIEW → ACCEPTED`, held 0, Audit와
  `SettlementDisputeDecidedV1`을 commit한다. 후자가 실패하면 Adjustment는 보존되지만 Dispute는
  `UNDER_REVIEW`이고 `SETTLEMENT_DISPUTE_DECISION` Case가 `MANUAL_REVIEW`로 남는다. 같은 명령
  재시도는 기존 Adjustment의 target/reason/amount를 검증한 뒤 terminal commit과 Case resolve로
  수렴한다. `REJECTED`/`WITHDRAWN`은 Adjustment 없이 held 0과 terminal fact를 한 transaction에
  저장한다.

## Audited expired-benefit policy read

- policy GET은 Operations transaction에서 Platform Operator role, active
  `EXPIRED_BENEFIT_POLICY_READ` grant, current policy heads와 `X-Access-Reason` access
  AuditRecord를 함께 검증·저장한다.
- grant, head query 또는 Audit 저장 실패는 policy body를 반환하지 않고 503이다. missing/
  revoked grant는 403, missing/malformed reason은 400이다.

## Idempotent commands

멱등 명령은 두 모델 중 하나를 사용한다. 선택 기준은 새 Aggregate 수가 아니라 **기존
직렬화 root, 로컬 원자성, 외부 결과 불명 위험**이다(ADR-064).

- **사전등록 모델:** 주문 생성·빠른 재주문처럼 결과 root가 없어 새 root 생성 경쟁을 먼저
  arbitration해야 하거나, 결제 승인처럼 최초 terminal 응답 저장 전 외부 Provider 결과가
  불명확해질 수 있는 명령. `PROCESSING` 레코드를 먼저 커밋하고 reconciliation으로
  수렴한다.
- **명령 트랜잭션 모델:** 매장 주문 상태 전이, 고객 주문 취소와 감사형 point adjustment처럼
  기존 Aggregate root를 잠가 경쟁을 직렬화하고, 모든 local write와 최초 응답을 하나의
  transaction에서 함께 commit하는 명령. 새 local Aggregate를 만들어도 외부 호출이
  transaction 밖이고 rollback 뒤 남는 부수효과가 없으면 이 모델을 사용한다.
  `PROCESSING` 상태가 없고 롤백된 요청은 레코드를 남기지 않는다.

- `actorId + operation + Idempotency-Key` Unique Constraint의 insert가 동시 요청의
  승자를 정한다.
- 특정 Aggregate를 대상으로 하는 명령은 그 식별자를 canonical payload에 포함하고,
  조회한 레코드의 대상 식별자 일치도 함께 검증한다. 고객 취소와 매장 주문 상태 전이가
  모두 `orderId`를 포함한다.
- canonical payload 구성을 바꾸면 저장된 구 레코드가 새 hash와 일치할 수 없으므로
  `operation` 값을 함께 승격해 scope를 분리한다.
- payload hash가 다르면 도메인 작업 전에 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다.
- 같은 payload의 후속 요청은 저장된 상태와 응답을 반환하며 작업을 다시 실행하지 않는다.
- 주문 생성 `COMPLETED`/`FAILED`는 최초 HTTP status/body를 그대로 재생한다.
  `PROCESSING`은 `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`와 `Retry-After`를 반환한다.
- stale reconciliation이 `PROCESSING`을 `MANUAL_REVIEW`로 격리하면 자동 처리는 끝난다.
  same-key 요청은 `409 IDEMPOTENCY_MANUAL_REVIEW_REQUIRED`를 `Retry-After` 없이 반환하고
  Tx O를 다시 열지 않는다. reconciliation은 reason, 시작 시각과 intended Order 존재 여부만
  기록하며 terminal response를 추정하지 않는다.
- 빠른 재주문도 `REORDER_ORDER_V1`의 별도 scope로 같은 사전등록 규칙을 적용한다. source
  Order는 immutable 입력이지 결과 root의 직렬화 root가 아니며, 다른 source/request에 같은
  key를 쓰면 `409 IDEMPOTENCY_KEY_REUSED`다.
- 고객 취소는 명령 트랜잭션 모델이므로 `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 쓰지 않고
  Order row lock으로 동시 요청을 직렬화한다. canonical payload에 `orderId`를 포함해
  교차 주문 키 재사용을 `409 IDEMPOTENCY_KEY_REUSED`로 거부하고, 커밋 시점 unique
  위반도 같은 코드로 번역한다. 롤백된 요청은 레코드를 남기지 않는다.
- 고객 취소 멱등 레코드는 저장 시점에 terminal이므로 `retention_expires_at` 기준
  `(retention_expires_at, id)` 순서의 chunk worker가 정리하며 일반 비즈니스
  트랜잭션과 분리한다.
- C1은 Order lock 아래 Case·step·Refund·Delivery·publication을 새로 저장하지만, 외부
  Provider 호출은 transaction 밖이고 모든 durable work와 최초 202 response가 함께
  rollback되므로 명령 트랜잭션 모델을 유지한다.
- Provider 결과가 불명확한 레코드는 정리하거나 신규 요청으로 재승인하지 않는다.

## Audit

- BR-30 대상 변경과 AuditRecord는 가능한 경우 같은 로컬 DB 트랜잭션에 기록한다.
- Audit append는 `Propagation.MANDATORY`다. command의 필수 `AuditCategory`마다 current policy head를 잠그고
  exact immutable version을 읽어 category, class, policy version과 expiry를 같은 caller transaction에
  snapshot한다. head/version 부재, category/class/duration 불일치 또는 Audit flush 실패는 privileged
  business write와 함께 rollback한다. 고정 5년/2년 fallback은 없다.
- 주문 생성·예약·확정·만료·해제는 변경된 Aggregate target마다 record를 append하고
  correlationId/source reference로 같은 transaction을 묶는다.
- deadline 만료는 worker·조회·결제 trigger와 무관하게 SYSTEM actor와
  `LEASE_DEADLINE_REACHED` reason을 사용한다.
- 외부 호출 결과는 별도 트랜잭션에서 owner state와 AuditRecord를 함께 확정한다.
- 비동기 owner Context 변경은 원본 event/correlation reference를 감사 기록에 남긴다.
- 감사 실패를 로그만 남기고 원본 수동 변경을 성공 처리하지 않는다.
- financial/order/settlement/security/policy Audit는 `occurredAt`의 서울 현지 시각 5주년,
  PII access Audit는 2주년을 expiry로 저장한다. 기존 row의 expiry는 V39에서 재계산하지 않는다.
- AuditRecord retention worker는 `retentionExpiresAt <= now`인 record만 `(retentionExpiresAt, id)` 순서로
  `FOR UPDATE SKIP LOCKED` claim/delete한다. worker별 bounded transaction은 서로 disjoint하고, 실패를
  0건 성공으로 바꾸지 않으며 due 이전 row를 삭제하지 않는다. 상세는
  [Audit retention runbook](../operations/audit-retention-runbook.md)을 따른다.

## Bulk operations

- 만료, 정산, 재집계는 chunk 처리한다.
- 중단·재실행 시 같은 원천을 중복 처리하지 않는다.
- Bulk SQL 이후 영속성 컨텍스트를 clear하거나 별도 트랜잭션·Repository를 사용한다.

## Support boundaries

- S20 Support는 Operations public permission/retention/Audit API만 사용하고, owner Context Repository/table을 쓰거나
  호출하지 않는다. subject link는 typed owner ID reference만 저장한다.
- S30 exact search는 짧은 Tx1에서 persistent permission과 actor/5-minute rate row를 잠그고 시도 횟수를 commit한
  뒤, transaction 밖에서 모든 configured Vault HMAC version을 계산한다. Tx2는 permission을 다시 확인하고
  Identity/Merchant/Delivery public query API의 masked DTO만 모아 PII-free Audit와 함께 commit한다. Vault, owner query
  또는 Audit 실패는 503이며 부분/빈 결과 fallback이 없다. Support에는 criterion, digest, ciphertext 또는 masked
  candidate를 저장하지 않는다.
- raw PII reveal과 high-risk change는 필요한 authorization fact와 target Audit이 commit된 뒤에만 응답/성공한다.
- S40 verification Provider 호출은 intent/result transaction 사이, owner Vault decrypt와 break-glass security
  notification Provider 호출은 durable reservation/claim 뒤 DB transaction 밖에서 수행한다. Delivery와 object
  storage Provider도 같은 원칙을 따른다.
- pickup reschedule은 owner Fulfillment transaction에서 new-slot-first swap을 수행한다.
- timeout/ACK loss는 UNKNOWN/RECONCILING이고 retry가 같은 외부 부수효과를 만들지 않아야 한다.
- retention deletion은 component별 상태와 ledger를 원자적으로 전이하되 외부 object/index 삭제는 부분 실패를 명시한다.

S20–S40 command/query/reveal 경계와 future-stage 제약은 [Support transaction boundaries](support-transaction-boundaries.md)를 따른다.
