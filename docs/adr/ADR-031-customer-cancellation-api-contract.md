# ADR-031: 고객 취소 API 계약과 동기·비동기 성공 표현

- **Status:** Accepted
- **Date:** 2026-07-31
- **Amended by:** ADR-038의 고객 환불 projection, ADR-050의 조건부 금액 계약

## Context

ADR-029가 취소 범위와 Order 모델을, ADR-030이 인가와 조회 범위를 확정했다. 남은
문제는 취소 명령의 HTTP 계약이다.

게시된 `openapi/beanflow-v1.yaml`의 `cancelOrder`는 `201`과 `202`를 성공으로 두고
`requestBody`를 선택으로, `CancellationRequest.reason`을 자유 입력 1~500자로
계약하며 그 설명은 `Required only when targetState is REJECTED`였다. `cancellations`
에는 `targetState`가 없으므로 이 설명은 `StoreOrderTransitionRequest`에서 잘못
복사된 결함이다. `Cancellation` 스키마는 `cancellationId`를 필수로 요구하지만
ADR-029가 별도 Cancellation Aggregate를 두지 않기로 해 이 식별자의 출처가 없다.

또한 `PENDING_PAYMENT` 취소는 자원 해제까지 한 트랜잭션에서 commit되는 동기 완결인
반면 `PAID` 취소는 환불과 자원 복원이 비동기로 남는다. 두 결과를 같은 코드로
표현하면 실패 의미론 문서가 금지하는 "완료로 위장"이나 그 역방향 왜곡이 생긴다.

## Decision

- 성공 응답은 취소 시점 Order 상태에 따라 두 갈래다.
  - `PENDING_PAYMENT` 취소는 `200 OK`다. 슬롯·재고·쿠폰·포인트 해제와 Order 전이가
    모두 commit된 뒤에만 반환한다.
  - `PAID` 취소는 `202 Accepted`다. Order `CANCELLED`와 보상 착수 지점이 commit된
    것을 뜻하며 환불·복원·알림 성공을 뜻하지 않는다. 매장 거절의 `202`와 같은 의미다.
- `201 Created`를 사용하지 않는다. 별도 Cancellation Aggregate가 없어 새로 생성되는
  리소스가 없고 `Location`으로 가리킬 대상도 없다.
- `Cancellation` 응답에서 `cancellationId`를 제거하고 `orderId`로 식별한다. 한 주문은
  최대 한 번 취소되므로 별도 식별자가 필요하지 않다.
- `Cancellation` 필수 필드는 `orderId`, `orderState`, `reasonCode`,
  `paymentRecovery`, `cancelledAt`, `correlationId`다. `createdAt` 대신 취소 확정
  시각인 `cancelledAt`을 사용한다.
- `CancellationRefundRecoverySummary`는 state와 함께 `approvedAmountKrw`,
  `succeededRefundAmountBeforeCancellationKrw`,
  `cancellationRequestedRefundAmountKrw`, `remainingRefundableAmountKrw`를 정상
  setup에서 모두 반환한다. ADR-050에 따라 `state`만 항상 required이고 검증할 수 없는
  금액과 시각은 추정하지 않고 생략하는 조건부 계약이다. state는 이번 고객 취소
  Refund만 원천으로 삼고 선행 Refund state를 합성하지 않는다. ADR-038의 customer
  projection에 따라 내부 재시도·불명·수동 검토 상태는 고객용 `PROCESSING`으로
  축약한다.
- 자동 처리가 소진된 내부 `FAILED`·`MANUAL_REVIEW`도 고객 state는 `PROCESSING`이며
  nullable `noticeCode = REFUND_DELAYED`로만 지연 안내를 활성화한다. attempt,
  실패 code와 내부 상태는 응답에 포함하지 않는다.
- 취소 요청 현금액이 0일 때만 `NOT_REQUIRED`다. 양수인데 Refund 또는 필수 recovery
  snapshot이 없으면 내부 `SETUP_INCOMPLETE`지만 ADR-050에 따라 고객에게는
  `PROCESSING + REFUND_DELAYED`로 투영한다. snapshot이 없어 검증할 수 없는 금액은
  0이나 현재값으로 추정하지 않고 생략한다.
- `NOT_REQUIRED`는 요청액 0만 뜻하고 나머지 세 금액의 0을 뜻하지 않는다(ADR-036,
  2026-08-01). schema는 `NOT_REQUIRED`에 대해 `cancellationRequestedRefundAmountKrw`
  0과 `noticeCode` 부재만 강제하고, 네 금액은 all-or-nothing으로 함께 반환하거나
  함께 생략한다. `REQUESTED`, `SUCCEEDED`와 notice 없는 `PROCESSING`은 네 금액을
  모두 반환해야 하며, snapshot이 없는 `PENDING_PAYMENT` 취소와 setup 손상의 지연
  투영만 금액을 생략할 수 있다.
- `requestBody`는 필수이며 `reasonCode`가 필수, `detail`이 선택이다. `detail`은
  1~200자이고 어떤 API 응답에도 포함하지 않는다. 기존 `reason` 필드와
  `Required only when targetState is REJECTED` 설명을 제거한다.
- `CancellationReasonCode`를 재사용 가능한 schema로 분리하고 BR-14가 정한 여섯 값을
  enum으로 계약한다.
- 취소 이후 보상 진행은 새 endpoint 없이 `GET /api/v1/orders/{orderId}`의 기존
  `Order.paymentRecovery` 필드로 조회한다. `GET /orders/{orderId}/cancellations`
  같은 sub-resource를 만들지 않는다.
- **Order projection amendment (2026-08-01):** 취소 사실은 취소 POST 응답에만
  두지 않고 Order 표현에도 노출한다. 고객용 `Order`는 `rejectedAt`,
  `rejectionReason`과 대칭으로 `cancelledAt`, `cancellationCause`,
  `cancellationReasonCode`를 optional 필드로 갖는다. 세 필드는 ADR-029의 Order
  컬럼과 존재 조건이 같고, `CANCELLED`가 아니면 부재다. 이로써 ADR-050이 요구한
  "취소 시각과 reason code 등 독립적으로 확인 가능한 필드는 정상 반환"이 성립한다.
- 매장용 표현은 별도 `StoreOrder` projection이다. `StoreOrderResult.order`가 이를
  참조하며 `Order`에서 `cancellationReasonCode`와 `paymentRecovery`를 제외한다.
  매장은 고객 취소 사실과 원인을 구분하는 데 필요한 `cancelledAt`,
  `cancellationCause`만 보고, 고객이 신고한 사유와 환불 진행은 보지 않는다
  (ADR-030). 자유 입력 `cancellation_detail`은 두 projection 모두에서 계속
  부재다.
- `Cancellation.paymentRecovery`와 고객 취소 이후 `Order.paymentRecovery`는
  `CancellationRefundRecoverySummary`를 사용한다. 결제 승인 결과 불명과 Provider
  조회 복구를 나타내는 `PaymentConfirmation.recovery`는 상태와 시각만 가진
  `PaymentApprovalRecoverySummary`를 사용하며 두 recovery schema를 공유하지 않는다.
- 비허용 상태는 `409 ORDER_STATE_CONFLICT`, lease 만료는 `409 RESERVATION_EXPIRED`로
  BR-14 Contention Amendment를 따른다.
- ADR-036 amendment: 선행 Refund가 `REQUESTED`, `PROCESSING`, `RETRY_SCHEDULED`,
  `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW`이면 Order 전이 전에
  `409 PAYMENT_REFUND_UNRESOLVED`를 반환한다. 이 오류는 선행 Refund가 확정된 뒤 같은
  key로 재시도 가능하다. `RETRY_SCHEDULED`는 ADR-038이 추가한 상태이며
  2026-08-01 차단 목록에 명시했다.

## Alternatives Considered

### 게시된 `201`/`202` 유지

- OpenAPI 무변경이지만 `201`은 새 리소스 생성을 뜻하는데 대응 리소스가 없어 의미가
  비어 있고 ADR-029의 Aggregate 결정과 모순된다.

### 항상 `202`

- 클라이언트 분기가 사라지지만 결제 전 취소는 이미 자원 해제까지 확정된 완결
  작업이므로 "미완료"로 표현하면 불필요한 폴링을 유발하고 상태를 실제보다 불확실하게
  왜곡한다.

### `cancellationId`를 `correlationId` 기반 값으로 유지

- 계약은 그대로지만 replay 시 값이 달라지지 않도록 안정성 규칙을 별도로 정의해야
  하고, 식별 대상이 없는 식별자가 계약에 남는다.

### 별도 `GET /orders/{orderId}/cancellations`

- 관심사는 분리되지만 Aggregate가 없는데 sub-resource를 만드는 모순이 생기고
  `Order.paymentRecovery`가 이미 존재해 중복 계약이 된다.

## Rationale

매장 거절이 이미 "정상 전이 `200`, 보상이 남은 거절 `202`"를 쓰고 있어 두 갈래
표현이 저장소 안에서 대칭을 이룬다. `api-conventions.md`가 `200`을 동기 처리 결과,
`202`를 완료가 아닌 접수로 정의하고 있어 상태별 실제 완결 수준과 코드가 일치한다.
`Order.paymentRecovery`가 이미 계약에 존재하므로 후속 조회에 새 endpoint가 필요
없고, 고객은 주문 화면 한 곳에서 취소와 환불 진행을 함께 본다.

## Consequences

- 클라이언트는 `200`과 `202`를 분기해야 한다. `202`를 받으면 주문 조회로 폴링한다.
- `Cancellation`과 `Order` 두 표현이 같은 `CancellationRefundRecoverySummary`를 공유하므로
  요약 파생 로직을 한 곳에 둔다.
- Payment 승인 recovery와 고객 취소 환불 recovery가 별도 schema이므로 각 enum과
  필수 필드를 독립적으로 진화시킨다.
- POST 응답과 멱등 재생의 금액은 취소 commit 시점 snapshot이다.
  `remainingRefundableAmountKrw`의 최신 실제 잔액은 Order GET에서 갱신된다.
- OpenAPI 계약이 축소·변경되므로 `/api/v1` 안에서의 호환성 영향을 확인해야 한다.
  현재 구현된 client가 없어 실제 파급은 없다.
- `detail`은 저장은 되지만 어떤 응답에도 나타나지 않는 write-only 필드가 된다.

## Failure Scenarios

- `PAID` 취소에 `200`을 반환하면 고객이 환불 완료로 오인한다. 상태별 코드 분기를
  응답 계약 테스트로 고정한다.
- `PENDING_PAYMENT` 취소에 `202`를 반환하면 완결된 작업을 미완료로 표시해 고객이
  무한 폴링한다.
- 자원 해제 트랜잭션이 commit되기 전에 `200`을 반환하면 stale 상태가 성공으로
  노출된다. 기존 lease 만료 materialization과 같은 규칙을 적용한다.
- `detail`이 응답 DTO에 실수로 포함되면 BR-14와 ADR-030의 비노출 결정이 깨진다.
- `reasonCode` enum이 API·DB CHECK·클라이언트 세 곳에 존재하므로 배포 순서가 어긋나면
  유효한 취소가 `400 INVALID_REQUEST`로 거부된다.

## Verification

- `PENDING_PAYMENT` 취소가 `200`, `PAID` 취소가 `202`를 반환한다.
- 성공 응답 필드 집합이 계약과 정확히 일치하고 `detail`과 `cancellationId`가 없다.
- `GET /api/v1/orders/{orderId}`가 취소 후 `paymentRecovery`를 반환한다.
- 고객 `Order`가 `cancelledAt`, `cancellationCause`, `cancellationReasonCode`를
  반환하고 `StoreOrder`는 `cancellationReasonCode`와 `paymentRecovery`를 반환하지
  않는다.

## Required Tests

- `PENDING_PAYMENT` 취소 `200`과 `paymentRecovery.state = NOT_REQUIRED`
- `PAID` 취소 `202`와 진행 중 `paymentRecovery`
- 선행 부분 환불 후 네 금액 필드와 고객 취소 Refund 단일 state
- 진행 중·불명·실패 Refund가 성공 환불액과 실제 잔액 차감에서 제외됨
- 필요한 Refund/snapshot 누락의 고객 지연 투영과 운영자 `SETUP_INCOMPLETE`
- 응답에 `cancellationId`, `detail`, 보상 step 배열 부재
- `requestBody` 누락 요청 `400 INVALID_REQUEST`
- `reasonCode` 누락과 미정의 값 `400 INVALID_REQUEST`
- `detail` 200자 경계와 초과 `400 INVALID_REQUEST`
- 비허용 상태 `409 ORDER_STATE_CONFLICT`
- lease 만료 취소 `409 RESERVATION_EXPIRED`
- 미확정 선행 Refund의 `409 PAYMENT_REFUND_UNRESOLVED`와 상태 확정 후 재시도
- 취소 후 `GET /orders/{orderId}`의 `paymentRecovery` 반영
- 취소 후 고객 `Order`의 `cancelledAt`·`cancellationCause`·`cancellationReasonCode`
  반환과 비`CANCELLED` 주문의 세 필드 부재
- `StoreOrder` 응답의 `cancellationReasonCode`·`paymentRecovery` 부재와
  `cancelledAt`·`cancellationCause` 존재
- 두 projection 모두에서 `cancellation_detail` 부재
- `PaymentConfirmation.recovery`와 Cancellation/Order `paymentRecovery`의 schema
  참조 분리
- 자원 해제 실패 시 `200`이 아닌 `503 DEPENDENCY_UNAVAILABLE`

## Metrics

- `beanflow.order.customer_cancellation.response.count{status}` — `200`, `202`,
  `4xx`, `503` 분포
- `beanflow.order.customer_cancellation.async_ratio` — 전체 취소 중 `202` 비율

Order, Store, Customer ID와 `detail`은 metric tag로 사용하지 않는다.

## Revisit Conditions

취소 후 보상 진행을 주문 조회와 분리해야 할 만큼 응답이 커지거나, 운영자 취소가
도입되어 별도 명령 표현이 필요하거나, 클라이언트가 `200`/`202` 분기를 감당하기
어렵다는 근거가 측정될 때

## Related Decisions

- BR-14, BR-25
- [ADR-015](ADR-015-store-acceptance-timeout-compensation.md)
- [ADR-029](ADR-029-customer-cancellation-scope.md)
- [ADR-030](ADR-030-customer-cancellation-authorization.md)
- [ADR-038](ADR-038-retryable-refund-failure-and-customer-projection.md)
