# ADR-029: 고객 주문 취소 범위와 보상 경계

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

BR-14는 고객 취소 가능 상태를 `PENDING_PAYMENT`와 미수락 `PAID`로 정했지만,
구현과 API 계약이 존재하지 않는 상태에서 정책만 Accepted였다. 그 사이
`store-order-lifecycle` ExecPlan은 고객 취소를 명시적 Non-goal로 두었고,
`openapi/beanflow-v1.yaml`은 `POST /orders/{orderId}/cancellations`를 이미
계약했으며, `policy-traceability.md`는 BR-14를 `Ready`로 표기했다. 세 문서가
서로 다른 범위를 주장했으므로 구현 착수 전에 범위를 한 번 확정해야 한다.

범위 선택은 환불 필요 여부, 보상 Case 존재 여부, 이벤트 수, 트랜잭션 경계와
API 상태 코드를 모두 결정하는 상류 결정이다.

## Scope of this ADR

이 ADR은 고객 취소의 **기능 범위, 취소 가능 창, 경쟁 판정, Order 원인 모델과 사유
계약** 다섯 가지만 소유한다. API 계약과 상태 코드, 멱등성, 이벤트, 보상 Case, 처리
경계, 외부 환불, 자원 복원과 정산은 이 ADR을 참조하는 별도 ADR이 소유하며 여기에
추가하지 않는다.

## Decision

- 고객 직접 취소 대상 상태는 `PENDING_PAYMENT`와 `ACCEPTED` 이전의 `PAID`뿐이다.
- `PENDING_PAYMENT` 취소는 픽업 슬롯, 재고, 쿠폰, 포인트 예약 해제로 완결하며
  외부 Provider 환불을 생성하지 않는다. 자원 해제 경계는 기존 lease 만료
  transaction과 동일한 owner 집합을 사용한다.
- 두 허용 상태 모두 취소 transaction에서 `ORDER_CANCELLATION_ACCEPTED`
  NotificationDelivery를 내구 저장한다. 외부 Notification Provider 호출은
  transaction 밖에서 수행하며 접수 알림은 환불·복원 완료를 뜻하지 않는다.
- `PAID` 취소는 매장 거절과 동일한 보상 대상을 갖는다. 결제 환불, 확정 슬롯·재고
  복원, 사용 쿠폰·포인트 복원, 고객 알림이 모두 필요하다.
- `ACCEPTED`, `PREPARING`, `READY`, `COMPLETED`와 terminal 상태
  (`EXPIRED`, `CANCELLED`, `REJECTED`)에 대한 고객 취소 명령은 허용하지 않는다.
- `ACCEPTED` 이후 취소는 제조 비용 부담 주체와 취소 수수료 정책이 Accepted 되기
  전까지 고객 API, 상태 전이, 운영자 우회 경로 어디에서도 허용하지 않는다.
- 이 결정은 `store-order-lifecycle` ExecPlan의 "고객 취소" Non-goal을 해제한다.
  해당 완료 ExecPlan은 수정하지 않고 이 ADR과 BR-14 amendment가 우선한다.
- 취소 가능 창은 Order 상태와 기존 두 deadline(`reservationExpiresAt`,
  `acceptanceDeadlineAt`)만으로 판정한다. 취소 전용 시각 필드와 픽업 예정시각 기반
  cutoff를 도입하지 않는다.
- 고객 취소는 만료 worker, 매장 수락, 자동 timeout 거절과 같은 Order row lock 위의
  guarded transition으로 경쟁한다. 분산락을 도입하지 않으며 기존 Tx2 잠금 순서
  `Order → Pickup → 정렬된 Stock → Coupon → Point → Payment/Idempotency/Audit`를
  그대로 사용한다.
- deadline 경계에서는 시간 기반 전이가 이긴다. 각 상태는 기존 명령과 같은
  materialization 규칙을 따른다. `PENDING_PAYMENT`은 만료를 먼저 커밋한 뒤
  `409 RESERVATION_EXPIRED`를 반환한다. `PAID`는 고객 취소 transaction에서 timeout
  거절 전체를 materialize하지 않지만 ADR-058의 deduplicated timeout work와 Audit를
  내구 저장한 뒤 `409 ORDER_STATE_CONFLICT`를 반환한다.
- `CANCELLED`는 여러 원인이 공유하는 단일 상태로 유지하고, 원인은 별도 Aggregate가
  아니라 Order 내부 필드로 구분한다. `ordering_order`에 다음 네 컬럼을 추가한다.

  | 컬럼 | 의미 | 존재 조건 |
  |---|---|---|
  | `cancelled_at` | 취소 확정 시각 | `CANCELLED`에서 필수 |
  | `cancellation_cause` | 시스템이 판정한 취소 원인 | `CANCELLED`에서 필수 |
  | `cancellation_reason_code` | 고객이 신고한 사유 code | `cause = CUSTOMER_REQUEST`에서만 필수 |
  | `cancellation_detail` | 고객이 입력한 자유 서술 | 항상 선택 |
- `cancellation_cause`의 초기 값 집합은 `CUSTOMER_REQUEST`와 `PAYMENT_DECLINED`다.
  기존 결제 명시 거절 경로(BR-03 Payment Decline Amendment)는 `PAYMENT_DECLINED`를
  사용한다.
- `CANCELLED` 상태에서 `cancelled_at`과 `cancellation_cause`는 필수이며 CHECK 제약과
  `cancellation_cause` 허용값 CHECK로 강제한다. `CANCELLED`가 아닌 상태에서는 위
  네 컬럼이 모두 `NULL`이어야 한다.
- forward migration은 기존 `CANCELLED` row의 `cancelled_at`을 `updated_at`으로,
  `cancellation_cause`를 `PAYMENT_DECLINED`로 backfill하고
  `cancellation_reason_code`와 `cancellation_detail`은 `NULL`로 둔다. 현재 유일한
  생성 경로가 결제 명시 거절이기 때문이다. production row가 존재하면 backfill 전에
  별도 운영 검증을 수행한다.
- **Clean-cutover note (2026-08-01):** ADR-059 release gate가 `PASSED`인 동안 위
  backfill의 대상 row는 0이므로 migration은 네 컬럼과 세 CHECK를 처음부터 만든다.
  backfill 규칙 자체는 삭제하지 않는다. gate가 nonzero 또는 unknown이 되면 그대로
  forward-migration 경로의 계약이 된다. migration은 어느 경로에서든 후보 row 수를
  확인하고, clean-cutover 전제에서 row가 발견되면 조용히 통과하지 않고 실패한다.
- 별도 `Cancellation` Aggregate와 Repository는 도입하지 않는다. Order Aggregate가
  자신의 취소 사실과 불변식을 계속 소유한다.
- 고객 취소 사유는 닫힌 reason code를 필수로 받고 자유 입력 상세 사유는 선택으로
  받는다. 자유 입력을 필수로 요구하지 않는다. 이는 BR-30이 정한
  "자유 입력 reason은 수동·운영자 명령에서만 필수" 원칙을 고객 명령에 적용한 것이다.
- `cancellation_reason_code`와 `cancellation_cause`는 서로 독립한 축이다. 전자는
  고객이 스스로 신고한 사유이고 후자는 시스템이 판정한 원인이다. 특히
  `PAYMENT_ISSUE` reason code는 결제수단을 바꾸고 싶거나 결제 금액을 잘못
  선택했다고 고객이 판단해 **직접 취소한 경우**이며, 언제나
  `cancellation_cause = CUSTOMER_REQUEST`와 함께 기록된다. Provider가 승인을 명시
  거절해 시스템이 취소한 `cancellation_cause = PAYMENT_DECLINED`와는 발생 주체와
  경로가 모두 다르며 그 취소에는 reason code가 존재하지 않는다.
- reason code 허용 값과 상세 사유 제약은 BR-14 Cancellation Reason Amendment가
  소유한다. DB는 `cancellation_reason_code` 허용값 CHECK, `cancellation_detail`의
  `trim` 후 200자 상한 CHECK와 cause별 존재 조건 CHECK로 이를 강제한다.
- `cancellation_detail`은 Order row 밖으로 나가지 않는다. `OrderCancelled` payload,
  AuditRecord `reason`, 외부 Provider 요청과 애플리케이션 로그는
  `cancellation_reason_code`만 사용한다. 이는 BR-30의 민감정보 제한을 자유 입력
  경로에 적용한 것이다.

## Alternatives Considered

### 결제 전(`PENDING_PAYMENT`)만 허용

- 환불, 보상 Case, 환불 이벤트와 정산 영향이 전부 불필요해 구현 범위가 가장 작다.
- 그러나 BR-14를 축소 개정하고 게시된 OpenAPI 계약을 좁혀야 하며, BR-06의 3분
  수락 대기 동안 고객에게 어떤 이탈 경로도 남지 않는다.

### `ACCEPTED` 이후까지 허용

- 고객 경험은 가장 넓지만 BR-07, BR-14와 Order 상태 머신을 동시에 개정해야 한다.
- 제조 착수 후 비용 부담 주체와 취소 수수료에 대한 Accepted 정책이 저장소에 전혀
  없어 금전 손실 책임을 결정할 근거가 없다.

### 별도 `Cancellation` Aggregate

- OpenAPI `Cancellation.cancellationId`와 1:1로 대응하고 취소 이력 메타데이터를 담기
  좋지만, Repository, migration, 트랜잭션 경계와 불변식 문서가 모두 추가된다.
- 같은 성격의 `REJECTED`가 Order 내부 필드 모델을 쓰고 있어 비대칭이 생긴다.
- 취소 확정과 Cancellation 생성이 두 Aggregate에 걸쳐 부분 실패할 수 있다.

### 원인을 구분하지 않고 `cancelled_at`만 추가

- 컬럼은 최소지만 결제 거절 취소와 고객 취소를 DB에서 구분할 수 없어 보상 분기와
  정산 조정이 이벤트 역추적에 의존하게 된다. 이벤트 유실·지연 시 원인 복구가 불가능하다.

### `PAID`만 허용하고 `PENDING_PAYMENT`은 lease 만료에 위임

- 명시 취소 경로가 하나로 단순해지지만 결제 전 자원이 최대 5분간 점유되어 슬롯과
  재고 회전율이 나빠진다.

## Rationale

BR-14가 이미 Accepted였으므로 이 범위를 유지하는 것이 문서 정합성 비용이 가장 낮다.
`RejectionCompensationCase`, `payment_refund`와 네 owner 복원 consumer가 이미
존재하므로 `PAID` 취소의 보상 인프라를 새로 만들지 않아도 된다. `PAID` 창이 BR-06에
의해 최대 3분으로 제한되어 노출되는 보상 빈도의 상한이 구조적으로 작다.

## Consequences

- `PENDING_PAYMENT` 취소와 `PAID` 취소는 부수효과 집합이 다르므로 API 응답과
  성공 의미가 두 갈래로 갈린다. 두 갈래의 표현은 후속 API ADR이 결정한다.
- `Order.CANCELLED`가 결제 거절과 고객 취소 두 원인을 공유하지만 네 컬럼과 CHECK
  제약으로 DB 층에서 구분되므로, 보상 대상 판정과 정산 조정이 이벤트 역추적에
  의존하지 않는다.
- `ordering_order`에 네 컬럼과 세 종류의 CHECK 제약이 추가되고 기존 `CANCELLED`
  row backfill이 필요하다. `V7`의 `chk_order_state_lifecycle`을 확장해야 한다.
- reason code 집합이 API 계약, DB CHECK, 클라이언트 enum 세 곳에 동시에 존재하므로
  확장 시 배포 순서를 관리해야 한다.
- `policy-traceability.md`의 BR-14 Readiness와 Decision record를 정정했다.
- 아래는 이 ADR이 결정하지 않고 후속 ADR로 넘기는 항목이다.
  - ~~`RejectionCompensationCase`의 일반화 또는 분리~~ — ADR-033이 `OrderCompensation`
    계열로 일반화하고 `trigger` 컬럼을 추가하도록 확정했다.
  - ~~Pickup·Stock의 `RELEASED_BY_REJECTION`~~ — ADR-040이
    `RELEASED_AFTER_TERMINATION`과 별도 `restoration_trigger`로 일반화했다.
  - ~~`uq_payment_rejection_refund WHERE reason='STORE_ORDER_REJECTED'`의 고객 취소
    재사용~~ — ADR-036이 고객 취소 전용 reason과 partial unique index를 별도로
    확정했다.
  - ~~PointTransaction `RESTORE_SKIPPED_EXPIRED`/`COMPENSATION`의 고객 취소
    의미~~ — ADR-042가 결과 type은 유지하고 별도 trigger·policy version metadata로
    원인을 구분하도록 확정했다.
  - ~~OpenAPI `Cancellation.cancellationId` 식별자의 출처~~ — ADR-031이 해당 필드를
    제거하고 `orderId`로 식별하도록 확정했다.

## Failure Scenarios

- 고객 취소 명령과 매장 수락이 동시에 도달해 둘 다 성공하면 이미 수락된 주문이
  환불된다. Order row의 guarded transition이 하나만 성공시킨다.
- 고객 취소 명령과 3분 timeout 자동 거절이 동시에 도달해 `CANCELLED`와 `REJECTED`
  보상이 중복 실행되면 자원이 이중 복원된다. 같은 row lock과 상태 guard가 이를 막는다.
- `PENDING_PAYMENT`은 만료를 동기 materialize하고 `PAID`는 timeout work를 내구
  요청하는 비대칭이 남는다. `PAID` 취소가 `409 ORDER_STATE_CONFLICT`를 받은 직후
  조회하면 아직 `PAID`일 수 있지만 즉시 wakeup과 periodic worker가 같은 work를
  처리한다. work 저장 실패는 503이다.
- `PENDING_PAYMENT` 취소가 환불 경로를 만들면 존재하지 않는 승인에 대한 Provider
  호출이 발생한다. 상태별 보상 대상 분기를 명령 진입점에서 확정해야 한다.
- `ACCEPTED` 이후 취소를 운영자 경로로 우회하면 정산·수수료 근거 없이 금액이
  변경된다.
- 자유 입력 상세 사유가 event payload나 Provider 요청에 실리면 고객이 입력한
  개인정보가 외부와 감사 저장소로 복제된다. code만 전파하는 경계를 계약 테스트로
  고정해야 한다.
- reason code 집합이 늘어날 때 CHECK 제약과 클라이언트 enum이 함께 배포되지 않으면
  유효한 취소가 `INVALID_REQUEST`로 거부된다.

## Verification

- 허용 두 상태와 비허용 전 상태의 명령 결과가 상태별로 결정적이다.
- `PENDING_PAYMENT` 취소 경로에서 Provider 호출과 Refund record가 생성되지 않는다.
- `PAID` 취소 경로가 매장 거절과 동일한 owner 자원 최종 수량에 도달한다.

## Required Tests

- `PENDING_PAYMENT` 취소의 4자원 해제와 외부 호출 부재
- `CANCELLED` row의 `cancelled_at`·`cancellation_cause` 필수 CHECK 위반 거부
- 비`CANCELLED` 상태에서 취소 필드가 설정된 row의 CHECK 위반 거부
- 결제 명시 거절 취소가 `PAYMENT_DECLINED` cause로 기록됨
- 기존 `CANCELLED` row backfill 후 CHECK 제약 통과와 재실행 안전성
- `PAID` 취소의 환불·복원·알림 대상 생성
- `ACCEPTED`, `PREPARING`, `READY`, `COMPLETED` 고객 취소 거부
- `EXPIRED`, `REJECTED`, `CANCELLED` 고객 취소 거부
- 고객 취소와 매장 수락의 동시 실행에서 단일 최종 상태
- 고객 취소와 3분 timeout 자동 거절의 동시 실행에서 단일 보상 적용

## Metrics

- `beanflow.order.customer_cancellation.count{from_state,outcome}`
- `beanflow.order.customer_cancellation.count{reason_code}` — reason code를 여섯 개로
  둔 근거인 사유 분포를 측정한다. `OTHER` 비중이 높으면 집합 설계를 재검토한다.
- `beanflow.order.customer_cancellation.detail_present.count` — 상세 사유 입력 비율
- `beanflow.order.customer_cancellation.rejected_state.count{state}`
- `beanflow.order.customer_cancellation.contention.count{winner}` — 취소 대 수락 대
  timeout 경쟁의 승자 분포

Order, Store, Customer ID와 `cancellation_detail`은 metric tag로 사용하지 않는다.

- **Not measured:** 실제 고객 취소율, 상태별 분포와 reason code 분포

## Revisit Conditions

제조 단계별 취소 수수료, 매장별 취소 가능 시간 또는 `ACCEPTED` 이후 고객 취소 요구가
Accepted Business Policy로 확정될 때

## Related Decisions

- BR-14, BR-03, BR-06, BR-07, BR-15
- [ADR-013](ADR-013-payment-unknown-reservation-expiry.md)
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
- [ADR-028](ADR-028-expired-benefit-restoration-policy.md)
- [ADR-042](ADR-042-benefit-restoration-ledger-metadata.md)
