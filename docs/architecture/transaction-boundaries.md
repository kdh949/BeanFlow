# Transaction Boundaries

## General rule

한 트랜잭션에서 강한 일관성이 반드시 필요한 Aggregate만 함께 변경한다. 외부 네트워크 호출은 트랜잭션 경계 밖에서 실행한다.

## Order creation

Initial decision:

- Tx I1에서 intended Order ID, scope, canonical payload hash와
  `IdempotencyRecord(PROCESSING)`을 짧게 먼저 커밋한다.
- Order, 슬롯·재고·쿠폰·포인트 예약은 같은 PostgreSQL 배포 단위의 로컬 트랜잭션에서 공개 Application API를 통해 조정한다.
- Ordering이 다른 모듈의 Repository를 직접 호출하지 않는다.
- 일부 실패 시 주문과 모든 예약을 롤백한다.
- Merchant는 정규화한 메뉴·옵션 구성을 sellable unit 요구량으로 번역하고,
  Ordering은 같은 unit 요구량을 주문 전체에서 합산하여 Inventory 공개 API에
  전달한다. Inventory는 메뉴·옵션을 역으로 조회하지 않는다.
- payable이 0이면 같은 주문 생성 트랜잭션에서
  `BENEFIT_ONLY Payment(APPROVED)`를 만들고 네 예약을 확정한 뒤 Order를 `PAID`로
  커밋한다. 중간 `PENDING_PAYMENT`와 `RESERVED` 상태는 외부에 커밋하지 않으며
  외부 Provider를 호출하지 않는다.
- 성공 Tx O는 Order, 예약, AuditRecord와 IdempotencyRecord의
  `COMPLETED + 최초 201 response`를 함께 커밋한다.
- 확정 실패로 Tx O가 rollback되면 별도 Tx I2가 `FAILED + 최초 error response`를
  저장한다. Tx I1 뒤 멈춘 PROCESSING은 intended Order와 owner source를
  reconciliation하며 새 주문을 자동 실행하지 않는다.

Revisit when:

- Lock Wait 또는 transaction duration이 측정된 병목이 됨
- 독립 서비스 분리가 요구됨
- 보상 Saga의 운영 비용을 감당할 필요가 생김

## Payment approval

```text
Tx 1: Order/PaymentMethod 검증 + Payment APPROVING + IdempotencyRecord 저장
commit
External PG approval
Tx 2 approved: Order lock + 네 예약 확정 + Order PAID + Payment APPROVED + Audit
Tx 2 declined: Order lock + 네 예약 해제 + Order CANCELLED + Payment FAILED + Audit
Tx 2 unknown: Payment UNKNOWN + reconciliation schedule
```

- DB connection을 Provider latency 동안 점유하지 않는다.
- timeout은 `UNKNOWN`일 수 있다.
- PG 성공 후 Tx 2 실패는 reconciliation으로 복구한다.
- Tx1에서 최초 reconciliation due 시각을 함께 저장해 PG 성공 후 Tx2 전체 실패도
  stuck `APPROVING` 조회로 복구한다.
- Tx2 잠금 순서는 Order → Pickup → 정렬된 Stock → Coupon → Point →
  Payment/Idempotency/Audit다.
- 명시 거절은 422와 terminal idempotency result를 저장한다. timeout, 연결 오류,
  응답 유실과 해석 불가 응답은 거절로 바꾸지 않는다.
- `BENEFIT_ONLY`는 외부 호출 없이 같은 로컬 트랜잭션에서 Payment 승인 사실을
  확정하지만, 주문별 source reference와 IdempotencyRecord를 동일하게 보호한다.

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

- Order를 `COMPLETED`로 전환하는 트랜잭션은 원본 사실을 확정한다.
- 포인트 적립, 정산 항목, 알림과 분석은 idempotent after-commit 처리다.
- 부수효과 실패로 완료 주문을 되돌리지 않는다.

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
  local transaction에 속한다.
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

## Settlement

- `OrderCompletedV2` consumer는 immutable completion snapshot을 검증하고 `(storeId,
  settlementDate)` `OPEN` Batch를 insert-or-read한 뒤 SettlementItem, Audit과
  `SettlementItemCreatedV1` publication을 같은 transaction에 저장한다. Batch 또는 Item
  저장 실패는 event completion이 아니다.
- SettlementItem 생성은 원천 거래 reference 단위로 멱등하며 `settlementBatchId` FK가 필수다.
- Batch 집계와 상태 전환은 Item 전체를 Entity 컬렉션으로 로딩하지 않는다.
- 확정 후 환불·판정은 별도 Adjustment 트랜잭션이다.
- `SettlementItem` 생성의 기준 fact는 `OrderCompletedV2`다. `PaymentApproved`만으로
  Item을 생성하지 않는다.
- Payment 환불 fact는 미확정 Item 반영 또는 확정 후 Adjustment 생성의 입력이며,
  원천 refund reference를 Unique Constraint로 보호한다.
- `CALCULATED` 또는 `CONFIRMED` Batch로 닫힌 뒤 늦게 도착한 Item source는 Batch를
  바꾸거나 0원 Adjustment를 만들지 않고 source-unique ReprocessingCase로 남긴다.
- Dispute Context의 판정과 SettlementAdjustment 생성은 Context 간 별도 트랜잭션이다.
  명령 실패 시 Dispute가 Adjustment 완료로 가장하지 않고 재시도 가능한 상태를 유지한다.

## Audited expired-benefit policy read

- policy GET은 Operations transaction에서 Platform Operator role, active
  `EXPIRED_BENEFIT_POLICY_READ` grant, current policy heads와 `X-Access-Reason` access
  AuditRecord를 함께 검증·저장한다.
- grant, head query 또는 Audit 저장 실패는 policy body를 반환하지 않고 503이다. missing/
  revoked grant는 403, missing/malformed reason은 400이다.

## Idempotent commands

멱등 명령은 두 모델 중 하나를 사용한다. 선택 기준은 새 Aggregate 수가 아니라 **기존
직렬화 root, 로컬 원자성, 외부 결과 불명 위험**이다(ADR-064).

- **사전등록 모델:** 주문 생성처럼 기존 root가 없어 새 root 생성 경쟁을 먼저
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
- 주문 생성·예약·확정·만료·해제는 변경된 Aggregate target마다 record를 append하고
  correlationId/source reference로 같은 transaction을 묶는다.
- deadline 만료는 worker·조회·결제 trigger와 무관하게 SYSTEM actor와
  `LEASE_DEADLINE_REACHED` reason을 사용한다.
- 외부 호출 결과는 별도 트랜잭션에서 owner state와 AuditRecord를 함께 확정한다.
- 비동기 owner Context 변경은 원본 event/correlation reference를 감사 기록에 남긴다.
- 감사 실패를 로그만 남기고 원본 수동 변경을 성공 처리하지 않는다.
- AuditRecord retention worker는 서울 달력 5주년이 지난 record만
  `(retentionExpiresAt, id)` 순서의 제한된 chunk로 삭제한다. 일반 비즈니스
  transaction과 분리하고 중단·재실행 시 due 이전 record를 삭제하지 않는다.

## Bulk operations

- 만료, 정산, 재집계는 chunk 처리한다.
- 중단·재실행 시 같은 원천을 중복 처리하지 않는다.
- Bulk SQL 이후 영속성 컨텍스트를 clear하거나 별도 트랜잭션·Repository를 사용한다.
