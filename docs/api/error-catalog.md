# Error Catalog

| Code | HTTP | Retryable | Meaning |
|---|---:|---:|---|
| INVALID_REQUEST | 400 | No | 요청 형식 또는 필드 검증 실패 |
| ACCESS_DENIED | 403 | No | 역할·소유권·매장 소속 불충족 |
| RESOURCE_NOT_FOUND | 404 | No | 접근 가능한 리소스 없음 |
| ORDER_STATE_CONFLICT | 409 | No | 현재 상태에서 명령 불가 |
| IDEMPOTENCY_KEY_REUSED | 409 | No | 같은 키에 다른 payload |
| IDEMPOTENCY_REQUEST_IN_PROGRESS | 409 + Retry-After | Yes, same key after delay | 같은 key·payload의 최초 명령이 아직 처리 중이며 새 실행은 하지 않음 |
| MENU_CONFIGURATION_NOT_AVAILABLE | 409 | Maybe | 유효한 메뉴·옵션 구성이 현재 판매 불가 |
| PICKUP_SLOT_FULL | 409 | Maybe | 슬롯 수용량 없음 |
| STOCK_NOT_AVAILABLE | 409 | Maybe | 판매 재고 부족 |
| COUPON_NOT_AVAILABLE | 409 | No | 쿠폰 만료·사용·조건 불충족 |
| POINT_BALANCE_INSUFFICIENT | 409 | No | 사용 가능 포인트 부족 |
| RESERVATION_EXPIRED | 409 | No | 결제 전 예약 lease 만료 |
| PAYMENT_DECLINED | 422 | Depends | Provider가 명시적으로 거절 |
| PAYMENT_RESULT_UNKNOWN | 202 representation | Poll | 승인 결과 불명, reconciliation 중이며 확정 실패가 아님 |
| PAYMENT_REFUND_UNKNOWN | 202 representation | Poll | 환불 결과 불명, reconciliation 중이며 성공 환불액에 아직 포함하지 않음 |
| PAYMENT_REFUND_EXCEEDED | 409 | No | 누적 환불이 승인액 초과 |
| DEPENDENCY_UNAVAILABLE | 503 | Yes | 필수 외부·DB 의존성 일시 장애 |
| NOTIFICATION_DELIVERY_FAILED | operation-specific | Operator | 주문과 독립된 발송 실패 |
| SETTLEMENT_ALREADY_CONFIRMED | 409 | No | 확정 결과 직접 변경 시도 |
| DISPUTE_WINDOW_CLOSED | 409 | No | 이의제기 기간 종료 |

HTTP와 retry 정책의 초기 계약은 `openapi/beanflow-v1.yaml`을 따른다.

`PAYMENT_RESULT_UNKNOWN`과 `PAYMENT_REFUND_UNKNOWN`은 Error envelope로 확정 실패를
반환하는 경우가 아니다. command가 접수됐지만 외부 결과가 불명확한 경우 202 body의
상태와 correlation ID로 표현한다. 같은 idempotency key/payload의 polling 성격 재시도는
새 Provider 부작용을 만들지 않는다.
