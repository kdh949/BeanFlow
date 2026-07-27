# Error Catalog

| Code | HTTP | Retryable | Meaning |
|---|---:|---:|---|
| INVALID_REQUEST | 400 | No | 요청 형식 또는 필드 검증 실패 |
| ACCESS_DENIED | 403 | No | 역할·소유권·매장 소속 불충족 |
| RESOURCE_NOT_FOUND | 404 | No | 접근 가능한 리소스 없음 |
| ORDER_STATE_CONFLICT | 409 | No | 현재 상태에서 명령 불가 |
| IDEMPOTENCY_KEY_REUSED | 409 | No | 같은 키에 다른 payload |
| PICKUP_SLOT_FULL | 409 | Maybe | 슬롯 수용량 없음 |
| STOCK_NOT_AVAILABLE | 409 | Maybe | 판매 재고 부족 |
| COUPON_NOT_AVAILABLE | 409 | No | 쿠폰 만료·사용·조건 불충족 |
| POINT_BALANCE_INSUFFICIENT | 409 | No | 사용 가능 포인트 부족 |
| RESERVATION_EXPIRED | 409 | No | 결제 전 예약 lease 만료 |
| PAYMENT_DECLINED | 422 | Depends | Provider가 명시적으로 거절 |
| PAYMENT_RESULT_UNKNOWN | 202/409 contract | Yes | 승인 결과 불명, reconciliation 중 |
| PAYMENT_REFUND_EXCEEDED | 409 | No | 누적 환불이 승인액 초과 |
| DEPENDENCY_UNAVAILABLE | 503 | Yes | 필수 외부·DB 의존성 일시 장애 |
| NOTIFICATION_DELIVERY_FAILED | operation-specific | Operator | 주문과 독립된 발송 실패 |
| SETTLEMENT_ALREADY_CONFIRMED | 409 | No | 확정 결과 직접 변경 시도 |
| DISPUTE_WINDOW_CLOSED | 409 | No | 이의제기 기간 종료 |

HTTP와 retry 정책은 OpenAPI 구현 시 구체화한다.
