# API Conventions

## Resource style

```http
GET  /api/v1/stores/nearby
GET  /api/v1/stores/{storeId}/menus
GET  /api/v1/stores/{storeId}/pickup-slots

POST /api/v1/orders
GET  /api/v1/orders/{orderId}
POST /api/v1/orders/{orderId}/cancellations

POST /api/v1/orders/{orderId}/payment-confirmations
POST /api/v1/payments/{paymentId}/refunds

PATCH /api/v1/store-orders/{orderId}/status

GET  /api/v1/point-accounts/{accountId}
GET  /api/v1/stores/{storeId}/settlements
POST /api/v1/settlement-items/{itemId}/disputes
```

## Status codes

- `200 OK`: 조회 또는 동기 처리 결과
- `201 Created`: 새 리소스 생성
- `202 Accepted`: 비동기 처리 접수이며 완료가 아님
- `204 No Content`: 성공했으나 body 없음
- `400 Bad Request`: 형식·validation 오류
- `401 Unauthorized`: 인증 실패
- `403 Forbidden`: 역할 또는 객체 수준 인가 실패
- `404 Not Found`: 접근 가능한 범위에서 리소스 없음
- `409 Conflict`: 상태 전이, 멱등 키 payload, 중복 자원 충돌
- `422 Unprocessable Entity`: 형식은 유효하지만 도메인 규칙 위반을 분리할 필요가 있을 때
- `503 Service Unavailable`: 필수 의존성 일시 장애
- 외부 결과 불명은 API 계약에 명시된 pending/unknown 표현 사용

## Order creation response

`POST /api/v1/orders`의 201 body는 `{order, payment?}` 형태의 상태별 생성 결과다.

- 외부 결제가 필요한 variant는 `order.state=PENDING_PAYMENT`,
  `reservationExpiresAt` 필수, `payment` 필드 없음이다.
- payable 0인 variant는 `order.state=PAID`, `payableKrw=0`,
  active `reservationExpiresAt` 없음, `payment.type=BENEFIT_ONLY`,
  `payment.approvalState=APPROVED`, `approvedAmountKrw=0`이 필수다.
- 두 variant 모두 Order와 필요한 예약·Payment가 commit된 뒤에만 201을 반환한다.
- 같은 주문 생성 idempotency key/payload replay도 저장된 최초 201 envelope를
  그대로 반환한다.

## Customer order cancellation

`POST /api/v1/orders/{orderId}/cancellations`의 성공 표현은 취소 시점 Order 상태에
따라 두 갈래다.

- `PENDING_PAYMENT` 취소는 슬롯·재고·쿠폰·포인트 해제와 Order 전이가 모두 commit된
  뒤 `200 OK`를 반환한다. `paymentRecovery.state`는 `NOT_REQUIRED`다.
- `PAID` 취소는 `202 Accepted`를 반환한다. Order `CANCELLED`가 확정됐다는 뜻이며
  환불·자원 복원·알림 성공을 뜻하지 않는다.
- 별도 Cancellation Aggregate가 없으므로 `201 Created`와 `cancellationId`를 사용하지
  않고 `orderId`로 식별한다.
- request body는 필수이며 `reasonCode`가 필수, `detail`이 선택이다. `detail`은
  저장만 하고 어떤 API 응답, event payload, 감사 기록, Provider 요청과 로그에도
  포함하지 않는다.
- 취소 이후 환불 진행은 새 endpoint 없이 `GET /api/v1/orders/{orderId}`의
  `paymentRecovery`로 조회한다. 이 요약은 Refund aggregate에서만 파생하며 보상 case의
  PAYMENT step 상태에서 파생하지 않는다.
- `paymentRecovery.state`는 이번 고객 취소 source의 Refund 한 건만 원천으로 삼고
  다른 Refund나 보상 step을 합성하지 않는다. 내부
  `PROCESSING`·`RETRY_SCHEDULED`·`UNKNOWN`·`RECONCILING`은 고객
  `PROCESSING`, 내부 `FAILED`·`MANUAL_REVIEW`는 고객
  `PROCESSING + noticeCode: REFUND_DELAYED`로 투영한다. 고객 응답에는 attempt,
  실패 code와 수동 검토 여부를 포함하지 않는다.
- 취소 요청 현금액이 0일 때만 `NOT_REQUIRED`이고, 양수인데 Refund 또는 recovery
  snapshot이 없으면 내부 `SETUP_INCOMPLETE`다. 고객에게는
  `PROCESSING + noticeCode: REFUND_DELAYED`로 투영하고 운영자에게만 누락 원천을
  노출한다.
- `NOT_REQUIRED`는 요청액이 0이라는 뜻일 뿐 네 금액이 모두 0이라는 뜻이 아니다.
  선행 환불이 승인액을 전부 반환해 요청액이 0이 된 미수락 `PAID` 취소는
  `NOT_REQUIRED`이면서 `approvedAmountKrw`와
  `succeededRefundAmountBeforeCancellationKrw`가 양수다.
- `BENEFIT_ONLY` 취소는 snapshot과 네 금액이 모두 0이고 Refund가 없으며
  `state = NOT_REQUIRED`, `noticeCode` 부재다. 나머지 비동기 보상 때문에 `PAID`
  취소 응답은 계속 `202`다.
- 정상 setup의 `paymentRecovery`는 `approvedAmountKrw`,
  `succeededRefundAmountBeforeCancellationKrw`,
  `cancellationRequestedRefundAmountKrw`, `remainingRefundableAmountKrw`를 필수로
  반환한다. 앞의 세 값은 취소 Tx C1 snapshot이고, 마지막 값은 조회 시점 승인액에서
  `SUCCEEDED` Refund 성공액만 차감한 현재 실제 잔액이다. snapshot 손상으로 검증할 수
  없는 금액은 0이나 현재값으로 추정하지 않고 생략한다.
- 네 금액은 all-or-nothing이다. `REQUESTED`, `SUCCEEDED`와 notice 없는
  `PROCESSING`은 네 금액을 모두 반환하고, recovery snapshot이 없는
  `PENDING_PAYMENT` 취소와 setup 손상의 `PROCESSING + REFUND_DELAYED` 투영만 네
  금액을 함께 생략할 수 있다.
- 취소 POST의 `paymentRecovery`는 commit 시점 snapshot이고 멱등 재생에서도 그대로다.
  최신 state와 `remainingRefundableAmountKrw`는 Order GET으로 조회한다.
- 보상 step 상세는 운영자 전용 `GET /api/v1/operations/orders/{orderId}/compensation`
  에서만 조회한다. 매장 거절과 고객 취소가 같은 `CompensationSummary`를 사용하고
  `trigger`로 구분하되, 운영 endpoint는 이를 `OperatorCompensationView`로 감싸
  setup issue와 ReprocessingCase를 추가한다. 이 운영자 필드는 매장 응답에 없다.
- 취소 사실은 취소 POST 응답뿐 아니라 Order 표현에도 노출하며 역할별 projection을
  분리한다. 고객용 `Order`는 `rejectedAt`·`rejectionReason`과 대칭으로
  `cancelledAt`, `cancellationCause`(`CUSTOMER_REQUEST`, `PAYMENT_DECLINED`)와
  `cancellationReasonCode`를 optional로 반환하고 `CANCELLED`가 아니면 세 필드가
  모두 부재다. 매장용 `StoreOrder`는 `Order`에서 `cancellationReasonCode`와
  `paymentRecovery`를 제외한 projection이며 `StoreOrderResult.order`가 이를
  참조한다. 자유 입력 `detail`은 두 projection 모두에서 계속 부재다.
- 비허용 상태는 `409 ORDER_STATE_CONFLICT`, lease 만료는 `409 RESERVATION_EXPIRED`
  이며 자원 해제 실패는 `503 DEPENDENCY_UNAVAILABLE`다.
- 존재하지 않는 주문은 `404`, 타 고객 주문은 `403`으로 조회와 같은 코드를 사용한다.

## Idempotency

- 주문 생성, 주문 취소, 결제 승인, 환불, 매장 주문 상태 전이, 이의제기와 운영자
  재처리는 `Idempotency-Key`를 요구한다.
- scope: actorId + operation + key
- 같은 key + 같은 payload: 기존 결과
- 같은 key + 다른 payload: `409`
- business response와 header에는 `replayed` indicator를 넣지 않는다. terminal
  command는 저장된 최초 status/body를 그대로 반환하고 replay 여부는 내부
  IdempotencyRecord, metric과 structured log에서만 관측한다.
- 외부 결과가 non-terminal `UNKNOWN`인 Payment 승인·환불은 새 Provider 호출 없이
  현재 durable representation을 반환하는 예외다. 이 경우에도 replay indicator는
  없다.
- 처리 중인 키는 부작용을 다시 실행하지 않는다. Payment처럼 현재 durable
  representation 계약이 있는 명령만 그 representation을 반환하고, 주문 생성은 아래의
  `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`, 고객 취소는 Order row lock 직렬화 규칙을
  따른다.
- 주문 생성은 같은 key/payload의 `COMPLETED` 또는 `FAILED` record에 저장된 최초
  HTTP status와 body를 그대로 재생한다. 따라서 성공 replay도 최초 `201 Created`를
  유지하고 확정 실패 replay도 최초 4xx/503을 유지한다.
- 주문 생성 record가 아직 `PROCESSING`이면
  `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`와 `Retry-After`를 반환하며 202나 성공
  representation으로 바꾸지 않는다. 새 실행을 원하면 기존 결과가 확정된 뒤 계약에
  따라 새 key를 사용한다.
- 특정 Aggregate를 대상으로 하는 명령은 대상 식별자를 canonical payload에 포함한다.
  고객 취소와 매장 주문 상태 전이 모두 `orderId`를 포함하므로 같은 key를 다른 주문에
  재사용하면 `409 IDEMPOTENCY_KEY_REUSED`이며 다른 주문의 응답을 재생하지 않는다.
- 고객 주문 취소는 `PROCESSING` 사전등록 없이 명령 트랜잭션 하나에서 Order 잠금, 멱등
  레코드 조회, 취소와 최초 응답 저장을 함께 커밋한다. 같은 key·같은 payload 재요청은
  저장된 최초 `200` 또는 `202` body를 그대로 반환하며 `replayed` 같은 표시 필드를
  추가하지 않는다. 재생 body의 `paymentRecovery`는 취소 시점 snapshot이므로 최신 진행은
  `GET /api/v1/orders/{orderId}`로 조회한다.
- 취소의 canonical payload는 `orderId`, `reasonCode`, 정규화한 `detail`이다. 같은 key를
  다른 주문에 재사용하면 `409 IDEMPOTENCY_KEY_REUSED`이며 첫 주문의 응답을 재생하지
  않는다. 다른 key로 이미 취소된 주문을 다시 취소하면
  `409 ORDER_STATE_CONFLICT`다.
- 취소는 `IDEMPOTENCY_REQUEST_IN_PROGRESS`를 사용하지 않는다. 동시 같은 key 요청은
  Order row lock으로 직렬화되며, 잠금 대기가 요청 timeout을 넘기면
  `503 DEPENDENCY_UNAVAILABLE`이다. 롤백된 취소는 멱등 레코드를 남기지 않으므로 확정
  실패를 재생하지 않고 같은 key 재시도가 재실행된다.
- Payment 결과가 `UNKNOWN` 또는 `RECONCILING`이면 `202 Accepted`와 Payment
  representation을 반환한다. 같은 key/payload 재시도는 reconciliation이 진행 중인
  동안 202, 승인 확정 후 200을 반환할 수 있지만 새 승인 요청을 만들지 않는다.
- `PAYMENT_RESULT_UNKNOWN`은 확정 실패를 뜻하는 409 error가 아니다. 409는 상태 전이
  충돌 또는 같은 key의 다른 payload에만 사용한다.

## Error envelope

```json
{
  "code": "PAYMENT_RESULT_UNKNOWN",
  "message": "결제 결과를 확인 중입니다.",
  "correlationId": "string",
  "details": []
}
```

- 내부 예외명, SQL, secret과 stack trace를 노출하지 않는다.
- client가 재시도 가능한지 error catalog에 정의한다.
- 실패를 빈 성공 응답으로 바꾸지 않는다.

## Authentication and authorization

- 초기 계약은 Bearer token 인증을 전제로 한다.
- 401은 인증 실패, 403은 역할 또는 객체 수준 인가 실패다.
- 존재 여부 노출을 제한해야 하는 리소스는 인가 정책에 따라 404를 사용할 수 있지만,
  같은 operation 안에서 일관되게 적용한다.
- Store Owner와 Staff 요청은 `storeId` 소유권 또는 membership을 Application Service가
  검증한다.

주문 생성의 menu ID가 존재하지 않거나 option ID가 해당 menu에 속하지 않거나
정규화한 option 집합에 대응하는 MenuConfiguration이 없으면
`400 INVALID_REQUEST`다. 존재하는 MenuConfiguration이 현재 판매 불가하면
`409 MENU_CONFIGURATION_NOT_AVAILABLE`이며 재고 부족과 구분한다.

## Dates and money

- API 시각은 ISO-8601 offset 또는 UTC instant
- 제품 기준 timezone은 `Asia/Seoul`
- KRW 금액은 정수 원
- float/double로 금액을 표현하지 않는다.

## Pagination

- 목록은 안정적인 cursor를 우선 검토한다.
- 매장 거리 검색 cursor는 `(distance, storeId)`를 사용한다.
- 정렬 기준과 tie-breaker를 문서화한다.
- cursor는 내부 값을 직접 수정할 수 없는 opaque string으로 전달한다.
- 응답은 `nextCursor`가 있을 때만 다음 page가 있음을 뜻한다.

## Payment and asynchronous recovery

- `202 Accepted`는 승인 또는 환불 성공을 뜻하지 않는다.
- Payment approval 상태는 `APPROVED`, `UNKNOWN`, `RECONCILING`,
  `MANUAL_REVIEW`를 구분한다.
- `PaymentConfirmation.recovery`는 상태와 시각만 가진
  `PaymentApprovalRecoverySummary`를 사용한다. 고객 취소 환불의 notice와 금액
  allocation을 이 schema에 넣지 않는다.
- 내부 Refund 상태는 `REQUESTED`, `PROCESSING`, `RETRY_SCHEDULED`, `SUCCEEDED`,
  `FAILED`, `UNKNOWN`, `RECONCILING`, `MANUAL_REVIEW`를 구분한다. 고객 취소
  `CancellationRefundRecoverySummary`는 위 customer projection을 적용한다.
- Order `REJECTED` 또는 `CANCELLED`와 Payment refund 성공은 같은 상태가 아니다.
- 5분 reservation lease가 Payment `UNKNOWN`보다 먼저 끝나면 Order는 `EXPIRED`를
  유지하며, 202 representation은 뒤늦은 승인에 대한 void/refund recovery 상태를
  명시한다.

## Expired benefit restoration policy

- 정책 resource key는 `trigger × benefitType`이다.
- base GET은 네 현재 head를 안정적으로 trigger, benefit type 순서로 반환한다.
- keyed PATCH는 path의 trigger·benefit type 한 head만 새 append-only version으로
  갱신한다. `Idempotency-Key`, `expectedPolicyVersionId`, mode, validity days와
  reason이 필수다.
- version row는 수정·삭제하지 않고 과거 Case는 저장한 COUPON·POINTS version FK를
  계속 사용한다.

## Reservation expiry materialization

- `now >= reservationExpiresAt`인 `PENDING_PAYMENT` Order의 조회와 결제 명령은
  응답 전에 동일한 idempotent expiry transaction을 실행한다.
- 조회는 만료와 네 자원 해제가 모두 commit된 뒤 `EXPIRED` Order를 반환한다.
- 결제 명령은 만료 commit 뒤 `409 RESERVATION_EXPIRED`를 반환한다.
- expiry transaction이 실패하면 stale `PENDING_PAYMENT`나 부분 해제를 정상
  response로 반환하지 않고 `503 DEPENDENCY_UNAVAILABLE`를 반환한다.
- GET이 일으키는 write는 clock에 의해 이미 due가 된 상태 전이를 materialize하는
  범위로 한정하며 반복 조회가 추가 release를 만들지 않는다.

## Versioning

초기 URI version은 `/api/v1`을 사용한다. 호환되지 않는 변경은 별도 version 또는 명시적 migration을 요구한다.
