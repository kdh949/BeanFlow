# ADR-034: 고객 취소와 매장 거절의 이벤트 타입 분리

- **Status:** Accepted
- **Date:** 2026-07-31
- **Amended by:** ADR-044, ADR-055, ADR-059

## Context

ADR-029는 미수락 `PAID` 고객 취소가 매장 거절과 같은 owner 보상 대상과 고객 알림을
갖도록 정했고, ADR-033은 두 흐름이 하나의 주문 보상 Case 구조를 공유하도록 정했다.
구조를 공유하더라도 고객 취소는 고객이 요청한 사실이고 매장 거절은 매장 또는 수락
timeout이 만든 사실이므로 actor, 책임, 사유와 알림 의미가 다르다.

현재 구현의 `OrderRejectedV1`은 `OrderRejectionActorType`, 자유 형식 `reason`,
`rejectedAt`과 만료 혜택 정책 snapshot을 거절 의미로 고정한다. Payment, Fulfillment,
Inventory, Promotion, Loyalty와 Notification listener도 이 구체 타입만 소비하고
owner API와 source reference에 `rejection`을 사용한다. 고객 취소까지 같은 타입으로
보내면 고객 사유를 매장 거절 사유로 오분류하거나 거절 알림과 거절 전용 환불 reference를
생성할 수 있다.

기존 Event Catalog에는 version 접미사와 payload 계약이 없는 `OrderCancelled`가 있었고
Notification consumer도 누락돼 있었다. 구현 가능한 계약을 만들려면 먼저 이벤트
taxonomy를 확정해야 한다.

## Scope of this ADR

현재 결정은 고객 요청 취소와 매장 거절의 **이벤트 타입 관계와 이름**,
`PENDING_PAYMENT`·`PAID`별 발행 범위와 payload를 확정하고 correlation과 causation
계보와 owner 중복 식별 기준을 정한다. 혜택별 정책 snapshot은 ADR-041이 확정했다.
publication은 ADR-035에 따라 원
`PAID` 취소의 필수 내구 항목과 한 transaction에서 commit한다. owner의 중복·지연
상태 판정은 source-aware 수렴으로, 출시 후 호환성은 V1 동결과 V2 이행으로
확정한다. 해당 계약들이 확정되기 전에는 고객 취소 이벤트 구현을 시작하지 않는다.

Provider 명시 거절로 `cancellation_cause = PAYMENT_DECLINED`가 된 사건의 이벤트 계약도
이 ADR의 현재 범위에 포함하지 않는다.

## Decision

- 고객이 요청해 `cancellation_cause = CUSTOMER_REQUEST`로 확정된 취소는 별도
  `OrderCancelledV1` 이벤트로 표현한다.
- 고객 취소에 `OrderRejectedV1`을 발행하지 않는다.
- 매장 또는 수락 timeout 거절은 기존 `OrderRejectedV1`을 유지하고
  `OrderCancelledV1`을 발행하지 않는다.
- 두 이벤트가 ADR-033의 같은 주문 보상 Case와 owner별 보상 구조를 사용할 수 있지만,
  producer와 consumer는 두 사건을 타입 수준에서 명시적으로 구분한다.
- 공통 `OrderTerminatedV1` 이벤트를 새로 만들어 두 사실을 합치지 않는다.
- 기존 `OrderRejectedV1`의 이름과 주문 거절 사실 의미는 변경하지 않는다. 정책
  snapshot 필드 구조만 production 발행 전 ADR-041에 따라 혜택별로 확정한다.
- `OrderCancelledV1`은 미수락 `PAID` 고객 취소에서만 발행한다. 이 event는 Order가
  `CANCELLED`로 확정됐고 owner별 비동기 보상이 시작돼야 한다는 사실을 전달한다.
- `PENDING_PAYMENT` 고객 취소에는 `OrderCancelledV1`이나 다른 취소 event를 발행하지
  않는다. Order 전이와 픽업 슬롯·재고·쿠폰·포인트 예약 해제를 명령 transaction에서
  함께 commit하고 `200`으로 완결한다.
- `PENDING_PAYMENT` 고객 취소에는 주문 보상 Case나 event publication 복구 Case를
  생성하지 않는다.
- `OrderCancelledV1`의 기본 payload는 다음 필드다.

  | 필드 | 의미 |
  |---|---|
  | `envelope` | ADR-010과 Event Catalog의 공통 event metadata |
  | `orderId` | 취소된 Order 식별자 |
  | `cancelledAt` | Order 취소가 확정된 시각 |
  | `couponRequired` | `couponDiscountKrw > 0` |
  | `pointsRequired` | `pointsAppliedKrw > 0` |
  | `couponPolicy` | COUPON 정책의 전체 immutable snapshot |
  | `pointsPolicy` | POINTS 정책의 전체 immutable snapshot |

- required flag는 취소 transaction에서 Order의 immutable 금액 snapshot으로 산출한다.
  consumer는 false인 owner 작업을 만들지 않는다.
- 픽업 슬롯과 재고는 미수락 `PAID` Order에서 확정돼 있다는 기존 불변식과 보상 Case의
  필수 step을 따르므로 별도 required flag를 두지 않는다.
- actor는 주문 소유 `customerId`, cause는 event type의 `CUSTOMER_REQUEST`, 취소 전
  상태는 이 event의 `PAID` 발행 범위로 결정되므로 중복 필드를 두지 않는다.
- 자유 입력 `detail`, 금액, 자원 ID, 주문 보상 Case ID와 Provider reference는
  payload에 포함하지 않는다.
- ADR-055에 따라 Notification consumer 제거 뒤 사용처가 없는 `customerId`,
  `storeId`, `reasonCode`도 payload에 포함하지 않는다.
- `paymentRequired`도 payload에 포함하지 않는다. ADR-035의 Tx C1이 필요한 Refund
  `REQUESTED`를 event 발행 전에 이미 생성하기 때문이다.
- `couponPolicy`와 `pointsPolicy`는 required flag와 관계없이 항상 존재하며 각각
  `policyVersionId`, `mode`, `compensationValidityDays`를 포함한다. Tx C1에서
  Case가 참조한 같은 version 값이어야 한다. consumer는 현재 policy head를 조회하지
  않는다.
- envelope의 `correlationId`는 취소 HTTP 요청의 correlation을 전파한다. 요청에
  유효한 값이 없으면 `CorrelationIdSource`가 생성하고 API 응답과 후속 처리에서 같은
  값을 사용한다.
- envelope의 `causationId`는
  `customer-cancellation-command:{cancellationCommandId}` 형식이다.
  `cancellationCommandId`는 ADR-032의
  `ordering_cancellation_command_idempotency.id` 내부 UUID다.
- client `Idempotency-Key`, customer ID와 자유 입력 `detail`을 correlation,
  causation, publication 복구 owner reference나 structured log field로 사용하지
  않는다.
- publication 재시도는 저장된 최초 event envelope를 그대로 사용한다. 재시도마다 새
  correlation이나 causation을 생성하지 않는다.
- 같은 key·payload의 HTTP 멱등 재생은 저장된 응답만 반환하고 event를 다시 생산하지
  않으므로 새 lineage도 만들지 않는다.
- owner consumer의 source reference는
  `order:{orderId}:customer-cancellation:{aggregateVersion}:{step}` 형식이다.
  event consumer `step`의 허용값은 `pickup`, `stock`, `coupon`, `points`다.
  Tx C1이 생성하는 Refund와 NotificationDelivery는 같은 형식의 `payment`,
  `notification` step을 사용하지만 Payment와 Notification은 이 event의 consumer가
  아니다.
- 같은 `orderId`, customer-cancellation trigger, `aggregateVersion`과 step 조합은
  event ID가 같거나 달라도 같은 owner 작업이다. owner의 Unique Constraint 또는
  동등한 원장 불변식으로 한 번만 생성·적용한다.
- event ID는 publication row와 추적에 사용하지만 owner 부수효과의 유일한 중복
  기준으로 사용하지 않는다.
- 외부 Refund Provider 멱등키는 source reference와 별개이며 외부 결제 환불 정책
  결정에서 확정한다.
- owner consumer는 같은 source reference의 work가 진행 중이거나 완료됐으면 새 work,
  attempt와 부수효과를 만들지 않고 기존 상태를 반환한다.
- 아직 event를 적용할 수 있는 owner 상태면 해당 부수효과를 한 번만 적용한다.
- 다른 source, trigger 또는 aggregate version이 owner 상태를 이미 점유했거나 현재
  상태가 event와 모순이면 원하는 terminal 상태가 같더라도 성공으로 간주하거나
  덮어쓰지 않는다. listener는 `COMPENSATION_SOURCE_CONFLICT`로 실패한다.
- source conflict는 event publication의 bounded retry와 `MANUAL_REVIEW` 경로를
  따르며 이미 확정된 Order `CANCELLED`를 되돌리지 않는다.
- listener별 publication이 bounded retry를 소진하면 해당 listener에 대응하는 단일
  보상 step만 `MANUAL_REVIEW`와 `EVENT_PUBLICATION_RETRY_EXHAUSTED`로 전환한다.
- Case state는 step 상태에서 파생해 `MANUAL_REVIEW`가 되지만 다른 owner
  publication과 step은 계속 처리한다.
- publication completion attempt는 owner business attempt와 별도이며 보상 step의
  `attemptCount`를 증가시키지 않는다.
- 이 규칙은 ADR-033의 공통 retry 계약에 따라 `OrderRejectedV1`에도 동일하게
  적용하며, 모든 미완료 step을 한꺼번에 manual review로 바꾸는 기존 코드 동작을
  대체한다.
- `OrderCancelledV1`과 개정된 `OrderRejectedV1` payload는 최초 production
  publication이 저장되는 시점부터 동결한다.
- ADR-059 release gate가 production 배포·외부 사용·완료 및 미완료 publication과
  rollback 대상이 모두 없음을 증거로 확인한 경우에만 `OrderRejectedV1`의 단일 정책
  세 필드를 혜택별 두 snapshot으로 제자리 변경한다. gate가 실패하면 V1을 변경하지
  않고 forward migration과 compatibility를 다루는 별도 Accepted ADR/ExecPlan을 먼저
  만든다.
- 첫 운영 발행 후 필수 필드 제거, 필드 이름·타입 변경과 기존 필드 의미 변경은
  V1에 적용하지 않는다. breaking change는 payloadVersion 2와
  `OrderCancelledV2`로 이행한다.
- 구 consumer가 알 수 없는 필드를 무시할 수 있고 신 consumer가 구 payload를 읽을
  역직렬화 기본값이 있는 선택 필드만 V1에 추가할 수 있다.
- V1 listener target과 과거 target-to-step mapping은 미완료 V1 publication이 0이고
  승인된 rollback 기간이 끝날 때까지 유지한다.
- V1/V2 이중 발행은 기본 이행 방식이 아니다. 필요하면 source reference 충돌,
  consumer 중복과 종료 조건을 정하는 별도 Accepted ADR을 먼저 만든다.
- `OrderCancelledV1`과 listener별 persistent publication은 ADR-035의 Tx C1에서
  Order, 보상 Case, 필요한 Refund, Audit와 취소 멱등 응답과 함께 commit한다.
- event 또는 listener publication 저장 실패는 Tx C1 전체를 rollback하며 in-memory
  event만으로 `202`를 반환하지 않는다.

## Alternatives Considered

### `OrderTerminatedV1`으로 통합

- 공통 보상 consumer를 한 타입으로 구성할 수 있다.
- 기존 `OrderRejectedV1` 이행 또는 폐기가 필요하고 모든 consumer가 `trigger` 분기를
  정확히 구현해야 한다. 분기 누락 시 고객 책임 취소와 매장 책임 거절의 혜택·알림
  정책이 섞일 수 있다.

### `OrderRejectedV1` 재사용 또는 확장

- 새 타입과 listener 수를 줄일 수 있다.
- 고객 취소를 매장 거절이라는 거짓 사실로 표현한다. 기존 actor, reason,
  `rejectedAt`, source reference와 notification 의미가 고객 취소에 맞지 않고 기존
  consumer가 잘못된 부수효과를 만들 수 있다.

### `PENDING_PAYMENT`와 `PAID` 취소 모두 `OrderCancelledV1` 발행

- 모든 고객 취소를 같은 event stream에서 관찰할 수 있다.
- payload와 모든 consumer가 원 상태 또는 보상 필요 여부를 분기해야 한다. 이미 명령
  transaction에서 예약을 해제한 `PENDING_PAYMENT`를 owner listener가 다시 처리할 수
  있고, 보상 Case가 없는 publication 실패에 별도 운영 의미가 필요하다.

### 상태별 취소 event 분리

- 결제 전·후 취소의 의미를 타입으로 완전히 구분할 수 있다.
- 동기 완결되는 결제 전 취소에 별도 event와 listener 호환성 표면을 추가하며
  `OrderCancelledV1` 외 타입이 하나 더 필요하다.

### 식별자·시각·사유만 담는 최소 payload

- 계약 크기가 작고 required flag 산출이 필요 없다.
- 각 consumer가 혜택 사용 여부를 다시 조회해야 한다. 지연 재처리 시 취소 시점과
  다른 상태를 읽을 수 있고 owner Context 사이 조회 결합이 늘어난다.

### 금액·자원·혜택 내역을 담는 전체 snapshot

- consumer가 원본 상태를 거의 조회하지 않고 재처리할 수 있다.
- event에 필요 이상의 데이터가 복제되고 schema 진화와 보존 비용이 커진다. 금액과
  자원 식별자가 새 소비 목적 없이 장기 publication payload에 남는다.

### Raw `Idempotency-Key`를 causation에 사용

- 기존 매장 전이의 `store-order-command:{key}` 형식과 유사하고 요청 로그에서 직접
  찾기 쉽다.
- 고객이 제공한 token이 event publication, 운영 데이터와 log에 복제된다. actor
  scope가 없는 key 문자열은 서로 다른 고객 사이에서 충돌할 수 있고 key 삭제·보존
  정책과 event 보존이 결합된다.

### Event 전용 correlation·causation 생성

- client token과 명령 저장 구조에서 event를 분리한다.
- 원 HTTP 요청과 Order 전이, 보상 Case, event 사이의 인과 연결이 끊겨 장애 조사와
  중복 실행 판별이 어려워진다.

### Event ID 기반 owner source reference

- 현재 `OrderRejectedV1` consumer와 같은 패턴이고 동일 publication 재시도에는
  충분하다.
- producer 결함이나 운영 재처리로 같은 Order terminal version이 새 event ID로
  생성되면 새 owner 작업으로 보이므로 환불·복원·알림 중복 위험이 남는다.

### Order ID 기반 owner source reference

- 한 주문은 한 번만 취소된다는 불변식에 가장 단순하게 맞는다.
- terminal version을 잃어 잘못된 version의 지연 event와 정상 fact를 구분하기 어렵고,
  향후 교정 event나 복수 보상 요구가 생기면 같은 reference와 충돌한다.

### 원하는 terminal 상태면 source와 무관하게 성공

- owner 구현이 단순하고 이미 복원된 자원을 반복 처리하지 않는다.
- 매장 거절, 고객 취소 또는 운영자 복구가 같은 상태를 만들었어도 원인을 잃는다.
  잘못된 event가 보상을 완료한 것처럼 표시되고 원장·감사 source가 실제 fact와
  달라질 수 있다.

### 이미 처리된 상태도 실패

- 모든 성공이 fresh owner 전이임을 보장한다.
- 정상적인 at-least-once 재전달도 계속 실패해 publication을 불필요하게
  `MANUAL_REVIEW`로 보내므로 멱등 consumer 요구와 양립하지 않는다.

### 하나의 publication 소진에서 모든 미완료 step 전환

- 현재 거절 구현과 같고 listener target을 step에 매핑할 필요가 없다.
- 실패하지 않은 Refund·복원·알림까지 실패한 것처럼 표시하고, 독립적으로 완료할 수
  있는 owner의 자동 처리를 중단한다.

### Publication ReprocessingCase만 생성

- 전달 장애와 business 보상 상태를 분리한다.
- 보상 step이 `PROCESSING`으로 남아 운영자용 CompensationSummary가 실제 전달 장애를
  표시하지 못하고 Case가 영원히 미완료로 보일 수 있다.

### Payment verifier listener 유지

- 앞서 선택한 `paymentRequired` routing flag와 여섯 listener 구성을 유지할 수 있다.
- Refund는 Tx C1에 이미 존재하므로 listener는 owner work를 만들지 않는다. verifier
  publication 실패가 실제 Refund 진행과 충돌하고 listener-to-step 일대일 규칙에
  불필요한 예외가 생긴다.

### Refund 생성을 Payment listener로 이동

- 기존 거절 event 구조처럼 Payment listener가 Refund를 시작한다.
- Tx C1 commit 시 Refund가 없어 고객 `PaymentRecoverySummary`를 Refund에서 파생할
  수 없으므로 ADR-033과 ADR-035를 개정해야 한다.

### V1 계약 제자리 변경

- 새 타입과 이행 listener가 필요 없어 변경량이 적다.
- DB에 남은 구 publication payload가 신 binary에서 역직렬화되지 않거나 listener
  target 이름 변경으로 라우팅되지 않아 배포 직후 manual review backlog가 생길 수
  있다.

### 모든 변경에서 V1과 V2 이중 발행

- 신·구 consumer를 동시에 운영하며 점진적으로 이행할 수 있다.
- 두 version이 같은 owner work를 요청해 source-aware dedup에 지속적으로 의존하고,
  이중 발행 종료 조건과 publication backlog를 별도로 운영해야 한다.

## Rationale

공유하기로 결정한 것은 보상 진행 구조이지 비즈니스 사실의 의미가 아니다. 타입을
분리하면 consumer가 자신이 지원하지 않는 trigger를 암묵적으로 처리하지 못하고,
책임·사유·알림 정책이 앞으로 달라져도 기존 거절 계약을 깨지 않고 진화시킬 수 있다.

## Consequences

- Eventing API에 `OrderCancelledV1` 타입이 추가되고 Ordering이 생산한다.
- producer는 취소 전 상태가 `PAID`일 때만 이 event를 생산한다.
- Eventing API의 `OrderCancelledV1`은 위 라우팅 snapshot을 직렬화한다.
- `OrderRejectedV1`도 ADR-041의 coupon·points 전체 snapshot을 사용하며 기존 단일
  `policyVersion/policyMode/policyValidityDays` 필드를 제거한다.
- Fulfillment, Inventory, Promotion과 Loyalty는 고객 취소를 지원하는 listener 또는
  명시적 공통 handler를 추가해야 한다.
- Notification은 ADR-044에 따라 취소 transaction에 참여하는 public Application
  API로 접수 delivery를 만들며 `OrderCancelledV1`을 소비하지 않는다.
- Payment는 `OrderCancelledV1`을 소비하지 않는다. Tx C1이 Refund를 만들고 Refund
  worker가 PAYMENT step을 직접 갱신한다.
- owner 내부 로직을 공유하더라도 입력 계약과 source reference는 trigger를 보존해야
  하며 고객 취소를 `rejection`으로 기록하면 안 된다.
- Event Catalog의 기존 `OrderCancelled` 이름은 `OrderCancelledV1`로 교정되고
  네 자원 owner consumer만 포함된다.
- 보상 Case는 ADR-033에 따라 계속 `trigger = CUSTOMER_CANCELLATION`으로 구분한다.
- `PENDING_PAYMENT` 취소는 event publication table, 주문 보상 Case와 owner 비동기
  listener의 부하를 만들지 않는다.
- 향후 결제 전 취소 알림이나 분석 event가 필요하면 이 보상 event를 의미 확장하지
  않고 별도 요구와 전달 계약을 결정한다.
- required flag가 false인 COUPON, POINTS listener는 owner work와 보상 step 갱신을
  시작하지 않는다. ADR-033의 Case 생성 시 해당 step은 `NOT_REQUIRED`여야 한다.
- 취소 명령 레코드 ID가 event의 안정적인 원인 식별자가 된다. 운영 도구는 raw key가
  아니라 command ID와 correlation ID로 명령·event·보상 흐름을 연결해야 한다.
- owner schema와 API는 trigger, Order terminal version과 step을 보존한 source
  reference를 받아야 한다. 기존 거절 전용 event ID reference를 그대로 재사용하지
  않는다.
- owner API는 applied, already-applied-same-source, in-progress-same-source와
  source-conflict를 구분할 수 있는 결과를 반환해야 한다.
- 네 publication target과 Pickup, Stock, Coupon, Points step 사이에 검증 가능한
  일대일 매핑이 필요하다. Payment와 Notification step은 각각 Tx C1에 내구 저장한
  Refund와 NotificationDelivery 상태로 갱신한다.
- 운영 조회는 step의 `lastErrorCode = EVENT_PUBLICATION_RETRY_EXHAUSTED`와 별도
  `EVENT_PUBLICATION` ReprocessingCase를 함께 보여줄 수 있어야 한다.
- 배포 전 미완료 publication을 event type과 listener target별로 확인하는 gate가
  필요하다. mapping 제거는 자동 코드 정리가 아니라 운영 승인 대상이다.

## Failure Scenarios

- 취소 producer가 실수로 `OrderRejectedV1`을 발행하면 거절 전용 환불 reason, 자원
  상태 또는 알림 template이 생성될 수 있다.
- 공통 handler가 event type 또는 trigger를 source reference에 보존하지 않으면 서로
  다른 사실의 중복 판정이 충돌할 수 있다.
- 한 취소에서 두 타입을 모두 발행하면 owner 보상이 두 번 요청될 수 있다. owner
  Unique Constraint가 최종 방어선이어야 하지만 producer 계약도 이를 금지한다.
- 새 타입이 publication 복구 worker의 보상 Case 실패 처리에서 빠지면 재시도 소진이
  보상 Case의 `MANUAL_REVIEW`에 반영되지 않을 수 있다.
- `PENDING_PAYMENT` 취소에서 event를 잘못 발행하면 이미 해제된 예약을 PAID 보상
  consumer가 다시 처리하고 없는 Refund·보상 Case를 요구할 수 있다.
- required flag가 Order snapshot과 다르면 필요한 혜택 복원이 영구히 빠지거나
  불필요한 owner 작업이 실패 publication으로 남을 수 있다.
- consumer가 지연 시점의 Order 금액으로 required 여부를 다시 계산하면 event
  snapshot과 보상 Case step이 갈릴 수 있다.
- Case FK version과 event 혜택 snapshot이 다르면 재시도 결과를 재현할 수 없다.
- production publication이 존재하는데 pre-release 전제를 잘못 적용하면 저장된
  `OrderRejectedV1` 역직렬화가 실패한다.
- 자유 입력 `detail`이나 금액·자원 식별자를 payload에 추가하면 BR-14의 데이터 최소화
  경계와 이 contract를 위반한다.
- raw `Idempotency-Key`를 causation이나 log에 쓰면 고객 제공 token이 event 보존
  경계로 확산된다.
- publication 재시도에서 새 envelope를 생성하면 하나의 fact가 여러 correlation으로
  보여 운영자가 중복 사건으로 오인할 수 있다.
- 같은 Order version의 event가 새 event ID로 중복 생성됐는데 owner가 event ID만
  중복 기준으로 쓰면 같은 자원 복원을 다시 만들 수 있다.
- source reference에서 trigger 또는 step을 빼면 매장 거절과 고객 취소, 서로 다른
  owner 작업이 같은 key로 충돌한다.
- 다른 source가 만든 같은 terminal 상태를 단순 성공으로 반환하면 보상 Case가 실제
  event를 적용하지 않고도 완료될 수 있다.
- 같은 source의 정상 재전달에서 attempt를 증가시키면 전달 횟수를 business attempt로
  오인해 조기 `MANUAL_REVIEW`가 발생할 수 있다.
- 하나의 listener 소진에서 모든 미완료 step을 manual review로 바꾸면 실제 실패
  범위를 과장하고 정상 publication의 완료를 막는다.
- V1 필드나 listener target을 제자리 변경하면 구 publication이 역직렬화·라우팅되지
  않아 원 Order는 취소됐지만 보상이 시작되지 않을 수 있다.
- V1/V2를 승인 없이 이중 발행하면 같은 Order version source reference에 대한 충돌과
  불필요한 publication backlog가 생긴다.
- event serialization 또는 publication 저장 실패를 삼키면 Order 취소는 확정됐지만
  owner 보상 전달이 없는 숨은 성공이 생긴다.

## Verification

- 고객 요청 취소는 `OrderCancelledV1`만 한 번 생산한다.
- 그 고객 요청 취소의 직전 상태가 `PAID`일 때만 생산한다.
- `PENDING_PAYMENT` 취소에는 취소 event와 publication row가 없다.
- 기본 payload의 required flag가 취소 시점 Order 금액 snapshot과 일치한다.
- required flag가 false여도 coupon·points policy snapshot은 모두 존재한다.
- false인 required flag의 owner listener는 work를 만들지 않는다.
- 직렬화한 publication payload에 금지 필드가 없다.
- HTTP 요청, Order 전이, event와 owner work가 같은 correlation을 유지한다.
- causation이 실제 취소 멱등 레코드 UUID를 가리키고 raw key를 포함하지 않는다.
- publication 재시도 전후 envelope가 같은 lineage 값을 가진다.
- 같은 Order version·step의 event ID 동일/상이 중복이 owner work 하나로 수렴한다.
- 다른 trigger, Order version 또는 step은 서로 다른 source reference를 갖는다.
- 같은 source의 진행·완료 재전달은 상태와 attempt가 변하지 않는다.
- 다른 source와 모순 상태는 owner 데이터를 바꾸지 않고 명시적 conflict로 남는다.
- 한 listener 소진은 대응 step과 Case만 manual review로 만들고 다른 step은 계속
  진행·완료한다.
- publication completion attempt 변화가 step `attemptCount`를 바꾸지 않는다.
- 신 binary가 구 V1 payload와 listener target을 처리하고 구 binary가 허용된 선택
  필드 추가 payload를 처리한다.
- Tx C1 rollback과 commit에서 Order, Case, Refund, Audit, idempotency와 publication
  row가 각각 부분적으로 남지 않는다.
- 매장·timeout 거절은 `OrderRejectedV1`만 한 번 생산한다.
- consumer contract test는 지원하지 않는 반대 타입을 처리하지 않음을 검증한다.
- 고객 취소의 owner 기록과 접수 알림에 매장 거절 trigger가 남지 않는다.

## Required Tests

- 고객 취소 producer의 `OrderCancelledV1` 단일 발행
- 고객 취소 시 `OrderRejectedV1` 미발행
- `PAID` 취소의 `OrderCancelledV1` 발행과 publication 저장
- `OrderCancelledV1` payload와 publication target에 Payment·Notification 항목 부재
- Tx C1 Refund source reference의 terminal Order version과 `payment` step 일치
- Refund worker가 PAYMENT step을 직접 갱신하고 event listener가 갱신하지 않음
- `PENDING_PAYMENT` 취소의 취소 event·publication·주문 보상 Case 부재
- `PENDING_PAYMENT` 취소가 owner 비동기 listener를 호출하지 않음
- 쿠폰 미사용·포인트 미사용 조합별 required flag
- required flag와 주문 보상 Case `NOT_REQUIRED` step의 일치
- false required flag의 쿠폰·포인트 owner work 부재
- event JSON에 actor, `cancellationCause`, 취소 전 상태, `detail`, 금액, 자원 ID,
  Case ID와 Provider reference가 없음
- 200자 `detail`이 있는 취소의 event payload와 publication JSON에 `detail` 부재
- 요청 correlation 전파와 부재 시 생성
- `causationId`의 command record UUID 일치
- event publication row·structured log에 raw `Idempotency-Key`와 customer ID 부재
- publication 재시도 전후 correlation·causation 동일
- 같은 key·payload 응답 재생 시 event와 lineage 추가 생성 부재
- 같은 event ID의 중복 전달에서 owner work·원장 한 번
- 새 event ID지만 같은 Order terminal version인 중복 전달에서 owner work 한 번
- trigger·version·step 각각이 다른 source reference 조합 테스트
- 매장 거절 source reference와 고객 취소 source reference의 비충돌
- 같은 source가 이미 진행 중·완료된 각 owner의 재전달과 attempt 불변
- 다른 trigger·version source가 이미 적용된 owner의 `COMPENSATION_SOURCE_CONFLICT`
- 오래된 aggregate version event가 최신 owner 상태를 덮어쓰지 않음
- source conflict의 bounded retry 소진과 `MANUAL_REVIEW`
- source conflict 후 Order가 `CANCELLED`를 유지함
- 다섯 listener 각각의 retry 소진과 정확한 단일 step 매핑
- Coupon publication 소진 중 Refund·Notification worker와 Pickup·Stock·Points 계속 처리
- Case `MANUAL_REVIEW`와 성공한 다른 step 상태의 공존
- publication completion attempt와 step `attemptCount` 불일치가 의도대로 유지됨
- 기존 `OrderRejectedV1`의 단일 step exhaustion 회귀 테스트
- production 발행 전 구 `OrderRejectedV1` policy field·fixture 부재
- 개정된 `OrderRejectedV1` producer와 모든 consumer의 혜택별 snapshot 일치
- legacy listener target-to-step mapping과 정확한 step manual review
- 미완료 V1 publication이 있는 배포에서 mapping 제거 방지
- breaking fixture가 V1 contract test를 실패하고 V2에서만 허용됨
- 별도 ADR 없는 V1/V2 이중 발행 부재
- event serialization·publication insert 실패의 Tx C1 전체 rollback
- 수동·timeout 거절 시 `OrderCancelledV1` 미발행
- 두 이벤트 타입의 consumer routing 분리
- 공통 owner handler를 사용할 때 trigger별 source reference 분리
- Pickup·Stock의 `CUSTOMER_CANCELLATION` trigger와
  `RELEASED_AFTER_TERMINATION` 수량 한 번 복원
- 잘못된 교차 타입 전달이 부수효과를 만들지 않는 contract test
- publication 재시도 소진 시 올바른 주문 보상 Case가 `MANUAL_REVIEW`로 전환됨

## Metrics

- `beanflow.order.termination.event.count{event_type}` — `event_type`은
  `ORDER_CANCELLED_V1`, `ORDER_REJECTED_V1`
- `beanflow.order.termination.event.routing_error.count{event_type,consumer}`

Order, Customer, Store ID와 취소 상세 사유는 metric tag로 사용하지 않는다.

- **Not measured:** 고객 취소량, 거절량과 consumer별 처리 지연

## Revisit Conditions

고객 취소와 매장 거절 외에 동일한 보상 대상과 의미를 가진 종료 원인이 여러 개
추가되어 별도 타입 증가가 운영·호환성 비용으로 측정되거나, 외부 소비자가 공통 종료
fact를 명시적으로 요구할 때

## Related Decisions

- BR-14
- [ADR-010](ADR-010-initial-event-publication.md)
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
- [ADR-029](ADR-029-customer-cancellation-scope.md)
- [ADR-033](ADR-033-order-compensation-case-generalization.md)
- [ADR-040](ADR-040-order-termination-resource-release.md)
- [ADR-041](ADR-041-trigger-and-benefit-scoped-restoration-policy.md)
- [Event Catalog](../architecture/event-catalog.md)
