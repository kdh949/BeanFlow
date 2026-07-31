# Implement customer order cancellation and durable recovery

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

고객이 자신의 주문을 `PENDING_PAYMENT` 또는 매장 수락 기한 전의 `PAID` 상태에서
전체 취소할 수 있게 한다. 결제 전 취소는 주문과 네 예약 자원을 한 transaction에서
완결하고 `200 OK`를 반환한다. 결제 후 취소는 주문 종료와 환불·복원·알림의 내구
착수 지점을 한 transaction에 저장하고 `202 Accepted`를 반환한 뒤, 각 owner와 외부
Provider가 재시작 가능한 방식으로 수렴한다.

완료된 구현은 다음 관찰 가능한 결과를 만든다.

- 같은 고객 취소 명령은 동시·순차 재시도에도 한 번만 상태와 금액을 바꾼다.
- 결과가 불명확한 환불을 성공이나 확정 실패로 위장하지 않는다.
- 취소 접수, 환불 성공, 환불 지연 알림은 process 재시작 후에도 유실되지 않는다.
- 부분 환불 뒤 전체 취소도 승인액을 초과해 환불하거나 혜택을 이중 복원하지 않는다.
- 필수 환불 setup 손상은 고객에게 금액을 추정해 보여 주지 않고 운영 case로 탐지하며,
  안전한 한 종류의 누락만 2인 승인으로 복구한다.

### Goal

ADR-059 release gate를 통과한 pre-release schema에서 ADR-029~060의 고객 취소
capability를 코드, DB 제약, 공개 API, 영속 event, 운영 복구와 테스트가 같은 의미로
보호하는 것이 목표다. 구현 완료 기준은 정상·중복·경쟁·외부 실패·재시작·setup 손상
경로가 모두 명시적으로 수렴하고 `200/202`가 미내구 작업을 성공으로 위장하지 않는
것이다.

## Current State

저장소에는 주문 생성, 5분 예약 lease, 외부 결제 승인과 reconciliation, 매장
수락·거절·timeout, 거절 환불, 네 owner 자원 복원, NotificationDelivery,
ReprocessingCase와 AuditRecord가 구현돼 있다. 현재 Flyway migration은 V1~V12이며
거절 보상은 V8~V10과 `RejectionCompensation*`, `OrderRejected*` 이름을 사용한다.

고객 취소 REST skeleton은 존재하지만 이 계획의 상태·멱등성·환불·보상·알림
동작은 아직 구현하지 않았다. 정책과 계약은 BR-14 amendment, ADR-029~060,
`openapi/beanflow-v1.yaml`에 확정돼 있다. 코드 구현 전에 ADR-059 release gate를
통과해야 하며, gate가 실패하면 clean cutover를 진행하지 않고 이 계획을 중단한다.

관련 기존 진입점과 구현 관례는 다음과 같다.

- Ordering: `ordering/internal/OrderController.kt`,
  `ordering/internal/domain/Order.kt`, `ordering/internal/OrderingPersistence.kt`
- Payment: `payment/internal/domain/Payment.kt`,
  `payment/internal/domain/Refund.kt`, `payment/internal/PaymentPersistence.kt`,
  `payment/internal/RejectionRefundService.kt`,
  `payment/internal/RejectionRefundWorker.kt`
- Owner consumer: `fulfillment/internal/OrderRejectedPickupListener.kt`,
  `inventory/internal/OrderRejectedStockListener.kt`,
  `promotion/internal/OrderRejectedCouponListener.kt`,
  `loyalty/internal/OrderRejectedPointsListener.kt`
- Operations: `operations/internal/RejectionCompensationService.kt`,
  `operations/internal/RejectionCompensationPersistence.kt`,
  `operations/internal/ReprocessingCaseService.kt`,
  `operations/internal/AuditRecordService.kt`
- Notification: `notification/internal/NotificationDeliveryService.kt`,
  `notification/internal/NotificationDeliveryWorker.kt`,
  `notification/internal/NotificationPersistence.kt`
- Event recovery: `ordering/internal/EventPublicationRecoveryWorker.kt`,
  `ordering/internal/EventPublicationRetrySchedule.kt`

## Definitions

- **Tx C0**: `PENDING_PAYMENT` 고객 취소를 완결하는 짧은 로컬 transaction.
- **Tx C1**: 미수락 `PAID` 고객 취소와 모든 비동기 보상 착수 자료를 원자 저장하는
  로컬 transaction.
- **Tx CT**: 수락 기한이 지난 `PAID` 취소 요청이 deduplicated timeout work와
  Audit만 저장하는 transaction.
- **OrderCompensationCase**: `STORE_REJECTION` 또는 `CUSTOMER_CANCELLATION`
  trigger로 시작한 주문 종료 후속 작업을 추적하는 운영 Aggregate.
- **Recovery snapshot**: 승인액, 취소 전 성공 환불액, 이번 취소 요청액과 취소 Refund
  ID를 Tx C1 시점에 고정한 Payment 소유 불변 자료.
- **Allocation ledger**: 현금 환불과 쿠폰·포인트 복원이 어느 원 주문 line에 이미
  적용됐는지 기록해 중복 복원을 막는 원장.
- **SETUP_INCOMPLETE**: 현금 환불이 필요한데 Refund 또는 필수 recovery snapshot이
  없어 정상 자동 처리를 증명할 수 없는 내부 무결성 상태. Refund state가 아니다.
- **Logical source**: event ID가 바뀌어도 같은 업무 부수효과를 하나로 수렴시키는
  안정적인 source reference.
- **Request retry**: Provider가 부수효과 없음과 같은-key 재실행 안전을 모두 보장한
  명시 실패만 같은 key로 다시 요청하는 동작.
- **Lookup reconciliation**: 결과 불명 요청을 다시 보내지 않고 같은 Provider key의
  결과를 조회하는 동작.

## Scope

### In Scope

- `PENDING_PAYMENT`와 acceptance deadline 전 `PAID`의 고객 소유 주문 전체 취소
- Order 취소 원인·사유·시각 모델과 DB CHECK
- Tx C0, Tx C1, Tx CT, target별 Audit와 취소 명령 멱등성
- `OrderCompensationCase` clean cutover와 여섯 step
- 선행 부분 환불 allocation, 남은 현금 환불, 두 독립 retry 예산
- `BENEFIT_ONLY` 취소의 PAYMENT `NOT_REQUIRED`
- 픽업·재고·쿠폰·포인트 source-aware 복원
- trigger×benefit 정책 version과 보상 쿠폰 terms·cost snapshot
- 취소 접수, 환불 성공, 환불 지연 알림
- 미완료 고객 취소 환불의 정산 제외와 Audit
- setup integrity 즉시 탐지, 1분 scanner, 제한 복구와 2인 승인
- Ordering idempotency cleanup과 acceptance timeout durable work
- OpenAPI, 운영 runbook, migration, 구조·계약·동시성·실패 테스트

### Non-goals

- `ACCEPTED`, `PREPARING`, `READY`, `COMPLETED` 이후 고객 직접 취소
- 운영자·매장 구성원이 실행하는 고객 주문 취소와 수수료·책임 승인 workflow
- 고객 품목 단위 부분 취소와 원 Order line 수정
- 새 PG·Notification Provider 계약, credential 온보딩과 실제 계정 연동
- 실제 매장 지급, PG 수수료·세금·회계 전표와 채권 관리
- Kafka, Redis, Kubernetes, MSA, distributed lock
- 손상된 snapshot 자동 재구성 또는 애플리케이션 밖 DB break-glass
- legacy 보상 data/publication migration과 legacy scanner
- 전체 Order lifecycle·멱등성·이벤트의 전면 리팩터링
- notification preference, 법적 채널·기한, 보상 전체 완료 알림
- 별도 Cancellation Aggregate, cancellation history sub-resource, 고객용 step 상세

## Finalized Policies

- 취소 가능 상태는 `PENDING_PAYMENT`와 acceptance deadline 전 `PAID`뿐이다.
- 기한 정확 경계부터 시간 기반 전이가 이긴다. 만료된 `PENDING_PAYMENT`은 만료를
  materialize하고 `409 RESERVATION_EXPIRED`, 기한이 지난 `PAID`는 Tx CT 후
  `409 ORDER_STATE_CONFLICT`다.
- `PENDING_PAYMENT` 성공은 네 예약 해제까지 완료된 `200`, `PAID` 성공은 비동기
  보상 착수 자료가 저장된 `202`다.
- 취소 요청은 닫힌 여섯 `reasonCode` 중 하나가 필수이고 `detail`은 trim 후
  1~200자 선택 값이다. detail은 Order 밖으로 전파하지 않는다.
- 같은 Idempotency-Key와 같은 canonical payload는 최초 저장 response를 재생하고
  `replayed` 필드를 반환하지 않는다. 다른 payload는 409다.
- 선행 성공 부분 환불은 허용하고 남은 현금만 환불한다. 미확정 선행 Refund는
  `409 PAYMENT_REFUND_UNRESOLVED`, lock·DB 장애는 503이다.
- Provider REQUEST는 최초 포함 최대 3회이고 재요청 간격은 10초, 30초다. 결과 불명
  이후에는 REQUEST를 영구 중단하고 LOOKUP을 10초, 30초, 2분, 5분, 15분에 최대
  5회 수행한다.
- 고객은 내부 실패·불명·수동 검토를 확정 실패로 보지 않는다. 정상 setup이면 네
  금액을 모두 반환하고, setup 손상이면 검증할 수 없는 금액을 모두 생략하면서
  `PROCESSING + REFUND_DELAYED`를 반환한다.
- 고객 취소 접수 알림은 Tx C0/C1에 직접 내구 저장한다. 환불 성공·지연 알림은 Payment
  terminal result event에서 파생한다. 보상 전체 완료 알림은 만들지 않는다.
- 고객 취소로 완료되지 않은 거래는 SettlementItem과 Adjustment를 만들지 않고
  NOT_APPLICABLE Audit만 남긴다.

## Business Rules and Invariants

### Domain Invariants

- Order만 `CANCELLED` 상태와 `cancelledAt`, `cancellationCause`,
  `cancellationReasonCode`, `cancellationDetail`을 소유한다. 별도 Cancellation
  Aggregate를 만들지 않는다.
- `CANCELLED`이면 `cancelledAt`과 cause가 필수이고, 그 외 상태에서는 네 취소 필드가
  모두 null이다. `CUSTOMER_REQUEST`만 reason code가 필수이며
  `PAYMENT_DECLINED`에는 reason code가 없다.
- 같은 Order terminal version에는 고객 취소 Refund와 OrderCompensationCase가 각각
  최대 하나다.
- 승인액은 모든 `SUCCEEDED` Refund allocation의 합보다 작을 수 없다. 진행·불명·
  실패 Refund는 성공 누적액에 포함하지 않는다.
- Tx C1 snapshot의 승인액, 취소 전 성공 환불액, 취소 요청액은 불변이다.
  현재 남은 환불 가능액만 조회 시 성공 원장에서 계산한다.
- `BENEFIT_ONLY`는 0/0/0 snapshot, null Refund ID, PAYMENT step
  `NOT_REQUIRED`이며 다른 다섯 step은 일반 PAID 취소와 같다.
- Pickup과 Stock은 `RELEASED_AFTER_TERMINATION`을 공통 결과 상태로 쓰고
  `restorationTrigger`로 원인을 분리한다.
- Coupon과 Point 복원은 결과, source, trigger, policyVersion을 모두 보존한다.
  같은 조합만 멱등 성공이고 다른 조합은 `COMPENSATION_SOURCE_CONFLICT`다.
- 보상 쿠폰은 원 Campaign의 terms와 cost allocation snapshot을 소유한다. 비용은
  발급 시점이 아니라 미래 주문의 실제 완료 redemption에서만 인식한다.
- Compensation CUSTOMER_NOTIFICATION step은 접수 Delivery만 단조롭게 추적한다.
  환불 후속 알림은 이 step을 다시 열지 않는다.
- RepairProposal은 30분 `[createdAt, expiresAt)` 동안만 유효하며 서로 다른 활성
  PLATFORM_OPERATOR 두 명이 제안·승인한다.

### Architecture Constraints

- Controller는 Repository를 직접 호출하지 않는다.
- Ordering Application Service가 고객 취소 use case와 Tx C0/C1/CT를 조정한다.
- Aggregate가 상태 전이와 불변식을 보호하고 Repository는 Aggregate Root 단위다.
- 외부 Payment·Notification Provider 호출은 DB transaction과 row lock 밖에서 한다.
- 다른 Context의 Aggregate는 공개 API와 영속 event로만 변경하며 식별자로 참조한다.
- cross-module 금액·수량 변경 event는 Spring Modulith publication registry에 원
  transaction과 함께 저장한다.
- fake와 scripted provider는 test 또는 명시적 local profile만 허용한다. production
  profile의 fake 선택이나 필수 설정 누락은 시작 실패다.
- 새 production dependency와 새 infrastructure는 추가하지 않는다.

## Architecture and Transaction Boundaries

### Transaction Boundaries

transaction마다 잠금 순서를 다음과 같이 고정한다.

- Tx C0: Order → Pickup → 정렬된 Stock → Coupon → Point
- Tx C1: Order → Payment → 정렬된 Refund allocation → trigger의 COUPON policy
  head → 같은 trigger의 POINTS policy head
- Tx CT: Order → AcceptanceTimeoutWork
- safe repair 승인: Order → Payment
- Order가 필요 없는 Refund worker: Payment → 정렬된 Refund allocation
- owner listener: 해당 owner Aggregate Root와 그 allocation만 잠근다.

Idempotency, Case, Audit, Delivery와 publication은 위 business lock을 역순으로
획득하지 않는 insert/update다. Payment를 먼저 잠근 흐름은 뒤에서 Order를 잠그지
않는다.

Tx C0는 Order ownership·deadline·idempotency를 검증하고 Order `CANCELLED`, 네
예약 해제, target Audit, 접수 NotificationDelivery, 최초 `200` response를 함께
commit한다. 하나라도 실패하면 모두 rollback한다. Refund, compensation Case와
OrderCancelledV1은 만들지 않는다.

Tx C1은 Order·Payment·Refund allocation을 잠가 남은 환불액을 계산하고 Order
`CANCELLED`, six-step Case, 두 benefit policy version, recovery snapshot, 필요한
Refund `REQUESTED`, 접수 NotificationDelivery, target Audit, OrderCancelledV1의 네
listener publication, 최초 `202` response를 함께 commit한다. 하나라도 실패하면
모두 rollback한다.

Tx CT는 deadline이 지난 `PAID`에 대해 deduplicated AcceptanceTimeoutWork와
`ACCEPTANCE_TIMEOUT_WORK_REQUESTED` Audit만 commit한다. 저장 실패는 503이고 409를
반환하지 않는다. after-commit wakeup은 지연 최적화일 뿐이며 durable work와 periodic
scanner가 복구 근거다.

Owner listener, Refund worker, Notification worker, Settlement consumer, setup
scanner와 repair execution은 각각 별도 짧은 transaction이다. 외부 호출은
claim/result transaction 사이에서 수행한다.

## Alternatives Considered

- 결제 전 취소만 구현: BR-14와 게시 API 범위를 축소하므로 제외했다.
- `ACCEPTED` 이후까지 허용: 제조 비용·수수료 정책이 없어 제외했다.
- 별도 Cancellation Aggregate: Order와 원자성 경계를 둘로 나누므로 제외했다.
- 항상 `202`: 동기 완결 C0를 불필요하게 미완료로 표현하므로 제외했다.
- Provider 호출을 Tx C1에 포함: connection과 lock을 장시간 점유하므로 제외했다.
- 환불 결과 불명 뒤 REQUEST 재전송: 중복 환불 위험 때문에 제외했다.
- terminal Refund polling으로 알림 생성: Payment result transaction과 event
  publication 원자성을 잃으므로 제외했다.
- setup snapshot 자동 재구성: 금융 원천을 추정하게 되므로 제외했다.
- 단일 운영자 즉시 복구: 금융 부수효과에 대한 오조작 방지가 부족해 제외했다.

## Failure Semantics

- timeout, 연결 종료, malformed response, 결과 저장 전 crash는 성공·확정 실패가
  아니라 `UNKNOWN` 또는 `RECONCILING`이다.
- adapter allowlist 밖 명시 실패는 fail-closed terminal failure다. allowlist에
  포함돼도 부수효과 없음과 같은-key 안전을 둘 다 증명하지 못하면 재요청하지 않는다.
- 세 번째 retryable REQUEST 실패는 Refund `FAILED`, PAYMENT step과 Case
  `MANUAL_REVIEW`다.
- 다섯 번째 LOOKUP도 불명이거나 마지막 claim 결과가 저장되지 않으면 추가 외부
  작업 없이 `MANUAL_REVIEW`다.
- owner publication retry 소진은 해당 step만 `MANUAL_REVIEW`로 바꾸고 다른 owner
  작업은 계속한다. Order `CANCELLED`는 되돌리지 않는다.
- NotificationDelivery 네 번째 실패는 `MANUAL_REVIEW`와 ReprocessingCase를 남기고
  성공으로 완료하지 않는다.
- 필수 setup 손상을 감지했는데 Case/Audit 저장이 실패하면 고객·운영 조회는 503,
  worker와 consumer는 retry 상태를 유지한다.
- repair safe guard, fingerprint, 승인자, 유효시간 검증 실패는 Refund를 만들지 않고
  명시적 409 또는 `REPROCESSING_NOT_SAFE`로 끝낸다.
- Settlement consumer는 미완료 고객 취소 환불을 금액 0이나 성공으로 기록하지 않고
  NOT_APPLICABLE target Audit를 남긴다.

## Data and Migration

### Persistence Changes

구현 직전 `src/main/resources/db/migration`의 최신 번호를 다시 확인한다. 현재 상태를
기준으로 다음 순서를 사용한다.

1. ADR-059 gate 통과 후 pre-release V8을 직접 수정해
   `rejection_compensation_*`를 trigger-aware `order_compensation_*` clean schema로
   만든다. legacy table·compatibility view·backfill은 만들지 않는다.
2. V13에서 `ordering_order` 취소 필드·CHECK, 기존 `CANCELLED` backfill,
   cancellation idempotency, AcceptanceTimeoutWork와 필요한 unique/index를 추가한다.
3. V14에서 Refund reason/source/customer reason, request·lookup count와 next action,
   recovery snapshot, line-level cash/benefit allocation 및 승인액 상한 제약을
   추가한다.
4. V15에서 owner `RELEASED_AFTER_TERMINATION`, restoration trigger/source,
   trigger×benefit immutable policy version, Case policy child FK, benefit ledger
   metadata를 추가하고 기존 rejection row를 forward migration한다.
5. V16에서 compensation coupon terms·cost snapshot과 future redemption 연결을
   추가한다.
6. V17에서 notification logical source 제약, setup repair proposal·decision
   idempotency, 필요한 ReprocessingCase source unique와 scanner index를 추가한다.
7. retention query를 위한 terminal timestamp·keyset index가 위 migration에
   빠졌다면 별도 forward migration으로 추가한다. 이미 적용된 V1~V12는 V8의
   명시적 pre-release 예외 외에는 수정하지 않는다.

모든 migration은 PostgreSQL Testcontainers에서 빈 DB 적용과 지원하는 backfill
fixture를 검증한다. V8 수정 전에 다음 release gate가 모두 0임을 운영 증거로 남긴다.

- production DB의 legacy compensation table row
- Spring Modulith registry의 미완료 `OrderRejectedV1`/legacy compensation publication
- 외부 consumer와 보존 payload 의존성
- 해당 schema가 적용된 production deployment

하나라도 0이 아니거나 확인할 수 없으면 V8 clean cutover를 중단하고 별도 migration
ADR/ExecPlan을 작성한다.

## API and Event Contracts

### API Contract

- `POST /orders/{orderId}/cancellations`
  - CUSTOMER bearer와 `Idempotency-Key` 필수
  - body는 `reasonCode` 필수, `detail` 선택
  - C0 `200 Cancellation`, C1 `202 Cancellation`
  - 타인 주문은 조회와 같은 non-enumerating 응답
  - 비허용 상태 409, 만료 409, 미확정 Refund 409, lock·저장 장애 503
- `GET /orders/{orderId}`
  - 고객용 `paymentRecovery` projection을 취소 응답과 동일 mapper에서 생성
  - setup 정상 금액은 전부 존재, setup 손상 금액은 전부 생략
  - 내부 attempt, failure code, MANUAL_REVIEW, step과 detail은 비노출
- `GET /operations/orders/{orderId}/compensation`
  - `OperatorCompensationView` wrapper로 내부 Case·step·attempt·실패와 setup 상태 노출
- `POST /operations/reprocessing-cases/{caseId}/repair-proposals`
  - 활성 PLATFORM_OPERATOR, Idempotency-Key와 non-blank reason 필수
- `POST /operations/reprocessing-repair-proposals/{proposalId}/decisions`
  - 다른 활성 PLATFORM_OPERATOR만 승인 가능
  - 승인 시 Order→Payment lock 아래 fingerprint와 safe guard 재검증

OpenAPI를 구현의 입력으로 사용하며 controller DTO와 entity를 분리한다.

### Event Contracts

`OrderCancelledV1`은 Tx C1에서만 발행한다. consumer는 Fulfillment, Inventory,
Promotion, Loyalty 네 개뿐이다. payload는 공통 envelope와 다음 필드만 갖는다.

- `orderId`, `cancelledAt`
- `couponRequired`, `pointsRequired`
- `couponPolicy { policyVersionId, mode, compensationValidityDays }`
- `pointsPolicy { policyVersionId, mode, compensationValidityDays }`

customer/store ID, actor, cause, reason/detail, 상태, 금액, 자원 ID, Provider reference와
`paymentRequired`는 넣지 않는다. causation은 내부 cancellation command ID를 쓰고
client key는 넣지 않는다. source는
`order:{orderId}:customer-cancellation:{aggregateVersion}:{step}`이다.

`OrderRejectedV1`도 최초 production 발행 전 같은 two-policy shape로 제자리
변경한다. dual publish와 V2는 만들지 않는다. 최초 publication 후 V1은 동결한다.

Payment는 고객 취소 Refund terminal result transaction에 다음 publication을 함께
commit한다.

- `CustomerCancellationRefundSucceededV1`
- `CustomerCancellationRefundDelayedV1`

두 payload는 envelope, orderId, customerId, orderAggregateVersion,
refundAmountKrw, outcomeAt만 포함한다. Notification은 종류별 stable logical source로
Delivery를 한 번 만들며 일반 `PaymentRefunded`를 고객 알림 근거로 쓰지 않는다.

## Affected Modules

| Module | 책임 |
|---|---|
| Ordering | API, ownership, Order transition, C0/C1/CT, command idempotency, event |
| Payment | snapshot, allocation, Refund state machine, Provider request/lookup, result events |
| Fulfillment | Pickup source-aware termination release |
| Inventory | Stock source-aware termination release |
| Promotion | Coupon restoration ledger, policy snapshot, compensation coupon |
| Loyalty | Point restoration allocation과 trigger/policy ledger |
| Notification | 접수·환불 결과 Delivery와 외부 발송 복구 |
| Operations | OrderCompensationCase, setup case, Audit, 2인 repair |
| Settlement | 고객 취소 미완료 환불 제외와 NOT_APPLICABLE Audit |
| Identity | CUSTOMER ownership과 PLATFORM_OPERATOR 활성·분리 승인 검증 |
| Eventing/Shared | V1 envelope·failure code·공통 source 계약 |

## Milestones

1. **Release gate와 executable skeleton**
   - ADR-059의 네 가지 0 조건을 검증하고 증거 위치를 이 문서 Revision Notes에 기록한다.
   - 실패하면 이후 milestone을 시작하지 않는다.
   - OpenAPI-generated/manual DTO와 contract test skeleton을 먼저 만든다.
2. **Clean compensation model과 schema**
   - V8 clean cutover, V13~V17 forward migration을 작은 검증 단위로 작성한다.
   - `OrderCompensationCase`, trigger, six steps와 two-policy child를 구현한다.
   - 기존 store rejection tests를 새 이름과 invariant로 모두 통과시킨다.
3. **Order 취소 domain, C0와 Tx CT**
   - Order 필드·guard·reason validation, cancellation idempotency를 구현한다.
   - C0의 네 owner release, target Audit, 접수 Delivery를 원자 검증한다.
   - deadline 경계의 durable timeout work, worker wakeup·recovery·retention을 구현한다.
4. **Payment composition과 Tx C1**
   - line allocation ledger, recovery snapshot, lock 순서와 남은 환불 계산을 구현한다.
   - BENEFIT_ONLY와 미확정 선행 Refund 분기를 구현한다.
   - C1의 all-or-nothing commit gate와 최초 202 response 재생을 검증한다.
5. **Owner restoration과 policy snapshot**
   - OrderCancelledV1과 OrderRejectedV1을 같은 policy shape로 구현한다.
   - 네 listener를 source-aware 공통 termination contract로 전환한다.
   - coupon/point 결과·trigger·policy ledger와 보상 coupon terms/cost를 구현한다.
6. **Refund recovery와 고객 projection**
   - request/lookup count를 분리하고 adapter allowlist fail-closed를 구현한다.
   - 결과 transaction과 refund 성공·지연 publication을 원자화한다.
   - 취소 응답과 Order 조회가 하나의 customer projection mapper를 쓰게 한다.
7. **Notification과 Settlement**
   - C0/C1 접수 Delivery, 환불 결과 Delivery, retry/manual review를 구현한다.
   - CUSTOMER_NOTIFICATION step은 접수 Delivery만 추적하게 한다.
   - Settlement NOT_APPLICABLE 분기와 target Audit를 구현한다.
8. **Setup detection과 safe repair**
   - 조회·worker·consumer inline detector와 1분 batch-100 scanner를 구현한다.
   - source-unique Case/Audit 저장 실패를 503/retry로 전파한다.
   - 완전 snapshot+누락 Refund만 LOOKUP 시작으로 복구하는 2인 승인 API를 구현한다.
9. **Retention, 관측성, 문서와 release 검증**
   - idempotency와 timeout work cleanup을 table별 독립 transaction, keyset batch 100,
     1시간 schedule로 구현한다.
   - metric, structured log, alert와 runbook을 연결한다.
   - 전체 build, architecture, contract, concurrency, failure suite와 문서 검증을
     실행하고 Outcomes에 실제 결과만 기록한다.

각 milestone은 이전 milestone의 test가 green인 상태에서 끝낸다. 구현 중 정책·코드·
OpenAPI 충돌을 발견하면 코드를 우회하지 말고 이 계획의 Surprises & Discoveries와
해당 Business Policy/ADR을 먼저 갱신한다.

## Required Tests

- **Domain/DB:** 모든 허용·비허용 상태, deadline `-1ns/at/+1ns`, 취소 CHECK,
  cause/reason 조합, detail 정규화·길이·제어문자, unique와 승인액 상한
- **C0:** 네 자원·Order·Audit·Delivery 전부 commit 또는 rollback, Provider/Refund/
  Case/event 부재, 만료 경쟁과 단일 자원 해제
- **C1:** 필수 row·snapshot·Refund·Case·publication·response 전부 존재 또는 rollback,
  외부 호출 부재, ACCEPTED/timeout/부분 환불 경쟁
- **Idempotency:** same key/same payload 순차·동시 replay, 다른 payload 409, terminal
  response exact body, unresolved 409와 deadline 409의 non-terminal retry
- **Refund:** 선행 상태별 허용·차단, line allocation tie-out, request 3회,
  unknown 이후 lookup 5회, claim crash, 같은 Provider key, manual review
- **Projection:** 내부 모든 Refund/setup 상태의 고객 state·notice·금액 oneOf,
  취소와 Order 조회 일치, 운영자 상세 보존
- **Events:** producer/consumer exclusivity, payload allowlist, correlation/causation,
  four publications, event-ID 변경 중복, source conflict와 listener별 retry 소진
- **Owner:** pickup/stock 수량 단일 변경, coupon/point allocation 이중 복원 부재,
  policy head 경쟁의 두 snapshot 원자 선택, compensation coupon terms/cost
- **Notification:** C0/C1 Delivery insert failure rollback, 접수/성공/지연 종류별
  logical source, 지연 후 성공, provider retry와 Case, 전체 완료 알림 부재
- **Setup/repair:** inline+scanner 수렴, 감지 저장 실패, snapshot 손상 분류,
  self/stale/expired proposal, 두 operator 동시 승인, fingerprint 재검증,
  복구 후 REQUEST 부재와 LOOKUP 시작
- **Settlement/Audit:** 미완료 고객 취소의 Item/Adjustment 부재, target별 Audit,
  민감 detail/client key/customer ID의 event·Provider·log 부재
- **Architecture/startup:** Modulith와 ArchUnit, Controller→Repository 금지,
  production fake/no-op Provider 시작 실패, 새 infrastructure 부재
- **Migration/retention:** empty DB, supported backfill, release gate nonzero 차단,
  table별 cleanup 실패 격리, terminal 90일과 nonterminal 보존

새 통합 suite는 `src/test/kotlin/io/github/kdh949/beanflow/ordering/internal`의
`CustomerCancellationIntegrationTest`, `CustomerCancellationConcurrencyTest`,
`CustomerCancellationContractTest`를 중심으로 두고 owner·Payment·Operations별
repository/domain test를 각 module 아래에 둔다. 거대한 단일 테스트 파일 대신 위
실패 경계를 기준으로 분리한다.

## Validation Commands

구현 milestone마다 좁은 테스트부터 실행하고 마지막에 전체를 실행한다.

```bash
./gradlew test --tests '*CustomerCancellation*'
./gradlew test --tests '*Refund*' --tests '*Compensation*'
./gradlew test --tests '*Notification*' --tests '*Settlement*'
./gradlew test --tests '*Repair*' --tests '*AcceptanceTimeout*'
./gradlew test --tests '*ModularityTests' --tests '*Architecture*'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

성능을 주장하려면 같은 PostgreSQL/Testcontainers 조건에서 기준선과 변경 후 결과를
같이 기록한다. 실행하지 않은 항목은 `Not run`으로 표시하고 실패를 숨기지 않는다.

## Observability

- cancellation count를 from_state, outcome, reason_code의 닫힌 tag로 측정한다.
- refund attempt를 reason, provider, mode, outcome으로 분리하고 retry scheduled,
  terminal failure, customer delayed를 측정한다.
- compensation step은 trigger, step, state, outcome과 age를 측정한다.
- timeout work와 setup integrity는 state, outcome, age, lag와 manual review를
  측정하고 alert를 연결한다.
- notification은 template, channel, state, outcome과 retry lag를 측정한다.
- settlement NOT_APPLICABLE과 repair proposal outcome을 닫힌 reason으로 측정한다.
- Order, Payment, Refund, Customer, Store ID, client key, Provider reference, raw code,
  cancellation detail은 metric tag에 넣지 않는다.
- structured log에도 client key와 detail을 쓰지 않으며 식별이 필요한 운영 조사는
  target AuditRecord와 권한 있는 조회 API를 사용한다.

## Documentation Updates

구현하면서 다음 문서를 코드와 함께 갱신한다.

- `docs/product/business-policy-decisions.md`
- `docs/architecture/aggregate-invariants.md`
- `docs/architecture/event-catalog.md`
- `docs/architecture/failure-semantics.md`
- `docs/architecture/policy-traceability.md`
- `docs/architecture/state-machines.md`
- `docs/architecture/transaction-boundaries.md`
- `docs/api/api-conventions.md`, `docs/api/error-catalog.md`
- `openapi/beanflow-v1.yaml`
- `docs/security/authorization-matrix.md`
- `docs/testing/test-strategy.md`, 실제 quality evidence 문서
- `docs/operations/payment-reconciliation-runbook.md`
- `docs/operations/store-order-lifecycle-runbook.md`
- ADR-029~060과 `docs/adr/README.md`

Accepted 정책을 변경하는 발견은 코드만 수정하지 않는다. 제품 숫자·행동은 Business
Policy, 장기 구조는 ADR, 국소적이고 되돌리기 쉬운 구현 결정은
`docs/decisions/minor-decisions.md`에 먼저 기록한다.

## Remaining Risks

- 실제 Provider별 “부수효과 없음 + 같은-key 재실행 안전” code는 adapter 계약이
  제공하는 범위에서만 allowlist에 넣을 수 있다. 확인되지 않은 code는 terminal로
  fail-closed한다.
- 부분 환불 기존 구현이 line allocation 원천을 충분히 보존하지 않는다면 V14 전에
  재현 가능한 allocation 모델을 먼저 완성해야 한다. 금액을 주문 현재값에서
  역산하지 않는다.
- V8 clean cutover는 release gate 증거가 없으면 배포할 수 없다.
- Tx C1의 다수 insert와 row lock이 latency를 늘릴 수 있으나 측정 전 성능 개선을
  주장하거나 원자 commit gate를 쪼개지 않는다.
- setup scanner는 violation-only query가 index를 사용하도록 실행 계획을 검증해야
  하며, 전체 table scan이 관측되면 index/query를 먼저 교정한다.

## Revisit Conditions

- `ACCEPTED` 이후 취소 수수료와 비용 책임이 Accepted 될 때
- 고객 셀프 부분 취소 또는 주문 line 수정 요구가 Accepted 될 때
- 실제 Provider가 같은-key 보장을 변경하거나 webhook reconciliation을 도입할 때
- production data/publication/external consumer가 발견돼 clean cutover 전제가 깨질 때
- 환불·알림·timeout SLA 측정 결과가 현재 retry budget 변경을 요구할 때
- setup 손상 유형이 “완전 snapshot + Refund만 누락”을 넘어 안전하게 복구 가능하다는
  별도 근거와 승인이 생길 때
- 독립 배포 consumer와 장기 replay 요구가 확인돼 broker가 필요해질 때

## Progress

- [x] 2026-07-31 고객 취소 정책과 경계 확정
- [x] 2026-07-31 ADR-029~060 Accepted
- [x] 2026-07-31 OpenAPI·아키텍처·운영·테스트 문서 계약 동기화
- [x] 2026-07-31 구현 ExecPlan 작성
- [ ] ADR-059 pre-release release gate 증거 확보
- [ ] schema와 clean compensation model 구현
- [ ] Tx C0, Tx C1, Tx CT 구현
- [ ] Refund·owner·Notification·Settlement 후속 처리 구현
- [ ] setup detection·2인 repair·retention 구현
- [ ] 전체 검증과 운영 evidence 완료

## Surprises & Discoveries

- 기존 OpenAPI는 별도 Aggregate가 없는데 `cancellationId`와 `201 Created`를
  요구했다. 확정 계약은 Order 식별과 상태별 `200/202`로 정정했다.
- 기존 거절 환불은 전체 attempt 하나와 line allocation 없는 모델이라 부분 환불 뒤
  고객 전체 취소를 안전하게 합성할 수 없다. request/lookup count와 allocation
  원장이 선행 구현 조건이다.
- 고객 취소 접수 알림은 compensation event consumer로 만들면 C0에서 event를
  발행하지 않는 규칙과 충돌한다. Tx C0/C1에 Delivery를 직접 저장하는 것으로
  해결했다.
- 기존 compensation naming과 단일 benefit policy는 고객 취소 trigger를 수용하지
  못한다. production 사용 전제 없음이 확인될 때만 clean cutover한다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-07-31 | Accepted | PENDING_PAYMENT와 미수락 PAID만 고객 취소 | 제조 시작 후 비용 정책 부재 | BR-14, ADR-029 |
| 2026-07-31 | Accepted | C0은 200, C1은 202, 별도 Cancellation Aggregate 없음 | 실제 완료 범위를 HTTP에 반영 | ADR-031 |
| 2026-07-31 | Accepted | stored-response command idempotency와 unified cleanup | side effect 중복과 무기한 원장 방지 | ADR-032, ADR-056~058 |
| 2026-07-31 | Accepted | trigger-aware OrderCompensation six-step clean cutover | 거절과 취소 후속 처리의 공통 불변식 | ADR-033, ADR-059 |
| 2026-07-31 | Accepted | 최소 OrderCancelledV1과 네 owner publication | 데이터 최소화와 owner별 복구 | ADR-034, ADR-055 |
| 2026-07-31 | Accepted | C1 commit gate와 부분 환불 allocation | 202 시점 복구 가능성과 금액 상한 | ADR-035~036 |
| 2026-07-31 | Accepted | REQUEST 3회, UNKNOWN 후 LOOKUP 5회 | 중복 환불 없이 bounded recovery | ADR-037~038 |
| 2026-07-31 | Accepted | BENEFIT_ONLY PAYMENT NOT_REQUIRED | 현금 부수효과 없음의 명시적 표현 | ADR-039 |
| 2026-07-31 | Accepted | source/trigger/policy-aware owner restoration | 이중 복원과 정책 head drift 방지 | ADR-040~043 |
| 2026-07-31 | Accepted | 접수 Delivery 직접 저장, 환불 결과 event 알림 | 알림 유실 방지와 책임 분리 | ADR-044~047 |
| 2026-07-31 | Accepted | settlement exclusion과 compensation coupon cost snapshot | 미완료 거래 수익 위장 방지 | ADR-048~049 |
| 2026-07-31 | Accepted | setup detector, 제한 repair, 2인 승인 | 금융 원천 추정과 단독 오조작 방지 | ADR-050~054 |
| 2026-07-31 | Accepted | MVP scope와 명시적 non-goals | 구현 중 범위 재선택 방지 | ADR-060 |

## Outcomes & Retrospective

아직 구현을 시작하지 않았다. 정책, ADR, OpenAPI와 실행 계획만 확정된 상태다.
구현 완료 시 migration, 테스트 수, 검증 명령의 실제 결과, 측정된 성능과 남은 운영
제약을 여기에 기록한다.

## Revision Notes

- 2026-07-31: ADR-029~060과 동기화한 최초 구현 ExecPlan 작성.
