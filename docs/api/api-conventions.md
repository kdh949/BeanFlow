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

## Idempotency

- 주문 생성, 주문 취소, 결제 승인, 환불, 매장 주문 상태 전이, 이의제기와 운영자
  재처리는 `Idempotency-Key`를 요구한다.
- scope: actorId + operation + key
- 같은 key + 같은 payload: 기존 결과
- 같은 key + 다른 payload: `409`
- 처리 중인 키는 부작용을 다시 실행하지 않고 저장된 현재 representation을 반환한다.
- 주문 생성은 같은 key/payload의 `COMPLETED` 또는 `FAILED` record에 저장된 최초
  HTTP status와 body를 그대로 재생한다. 따라서 성공 replay도 최초 `201 Created`를
  유지하고 확정 실패 replay도 최초 4xx/503을 유지한다.
- 주문 생성 record가 아직 `PROCESSING`이면
  `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`와 `Retry-After`를 반환하며 202나 성공
  representation으로 바꾸지 않는다. 새 실행을 원하면 기존 결과가 확정된 뒤 계약에
  따라 새 key를 사용한다.
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
- Refund 상태는 `REQUESTED`, `PROCESSING`, `SUCCEEDED`, `FAILED`, `UNKNOWN`,
  `RECONCILING`, `MANUAL_REVIEW`를 구분한다.
- Order `REJECTED` 또는 `CANCELLED`와 Payment refund 성공은 같은 상태가 아니다.
- 5분 reservation lease가 Payment `UNKNOWN`보다 먼저 끝나면 Order는 `EXPIRED`를
  유지하며, 202 representation은 뒤늦은 승인에 대한 void/refund recovery 상태를
  명시한다.

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
