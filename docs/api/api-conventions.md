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

## Idempotency

- 주문 생성, 결제 승인, 환불과 운영자 재처리는 Idempotency-Key를 검토한다.
- scope: actorId + operation + key
- 같은 key + 같은 payload: 기존 결과
- 같은 key + 다른 payload: `409`
- 처리 중인 키의 응답과 polling/retry 계약을 명시한다.

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

## Dates and money

- API 시각은 ISO-8601 offset 또는 UTC instant
- 제품 기준 timezone은 `Asia/Seoul`
- KRW 금액은 정수 원
- float/double로 금액을 표현하지 않는다.

## Pagination

- 목록은 안정적인 cursor를 우선 검토한다.
- 매장 거리 검색 cursor는 `(distance, storeId)`를 사용한다.
- 정렬 기준과 tie-breaker를 문서화한다.

## Versioning

초기 URI version은 `/api/v1`을 사용한다. 호환되지 않는 변경은 별도 version 또는 명시적 migration을 요구한다.
