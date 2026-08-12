# Post-Acceptance Resolution Policy

> **Status:** Accepted initial policy in S80 and ADR-085.

`PostAcceptanceResolutionCase`는 PREPARING, READY, COMPLETED 주문의 제조·준비·완료 사실을 되돌리지 않고
환불, 원혜택 복구와 정산 조정을 조정한다. Goodwill point/coupon은 S90 CompensationRequest가 소유하며 이
workflow에서 발급하지 않는다.

## Approval and exact binding

S60 `SupportActionRequest`의 승인된 `POST_ACCEPTANCE_RESOLUTION` exact revision이 유일한 승인 source다.
ResolutionCase는 SupportCase, Order, request/revision, action payload digest, policy/verification, trigger Order
state/version에 묶인다. S80은 별도 approval endpoint나 approval Aggregate를 만들지 않는다. assigned executor가
execution 시작 시 승인 revision을 한 번 소비하며 requester와 모든 reviewer는 executor가 될 수 없다.

## Responsibility and outcome

책임은 `CUSTOMER | STORE | PLATFORM | SHARED | UNDETERMINED`다. Resolution outcome은
`FULL_REFUND | PARTIAL_REFUND | NO_MONETARY_RESOLUTION | MANUAL_SETTLEMENT_REVIEW`다. 사용 포인트와 쿠폰
복구는 선택된 원혜택 restoration step이며 goodwill compensation이 아니다.

`UNDETERMINED`에서도 승인된 고객 현금 환불과 원혜택 복구는 진행할 수 있다. 다만 비용 귀속과 Settlement
adjustment는 `BLOCKED`/`MANUAL_REVIEW`로 남기고 Store 차감이나 Platform 부담으로 fallback하지 않는다.
STORE/SHARED만 exact approved plan의 store adjustment amount를 사용한다. PLATFORM/CUSTOMER는 자동 Store
adjustment가 `NOT_REQUIRED`이며, manual settlement review를 성공으로 표시하지 않는다.

## Case and step states

ResolutionCase 상태는 다음 closed vocabulary를 사용한다.

- `PLANNED`: exact approved revision으로 immutable plan과 steps가 생성됐지만 실행을 시작하지 않음
- `EXECUTING`: 하나 이상의 required step이 pending/processing/retry 상태
- `PARTIALLY_RESOLVED`: 하나 이상의 required step은 성공했지만 다른 required step이 blocked/manual-review/failed
- `RECONCILING`: 외부 결과가 불명확해 조회 또는 owner reconciliation 중
- `RESOLVED`: notification을 제외한 모든 required step이 `SUCCEEDED | NOT_REQUIRED`
- `MANUAL_REVIEW`: required step이 자동 진행 불가능하고 성공한 required step이 없음

Step 상태는 `PENDING | PROCESSING | RETRY_SCHEDULED | SUCCEEDED | NOT_REQUIRED | UNKNOWN |
RECONCILING | MANUAL_REVIEW | BLOCKED`다. `PARTIALLY_RESOLVED`, `UNKNOWN`, `RECONCILING`,
`MANUAL_REVIEW`를 완료나 성공으로 표시하지 않는다. Notification delivery는 독립 step/result이며 그 실패가
financial Resolution을 rollback하지 않는다.

## Owner and failure rules

- Payment cumulative succeeded Refund는 승인 금액을 넘지 않으며 기존 unresolved Refund가 있으면 새 Refund를
  만들지 않는다. Provider timeout은 `UNKNOWN`, lookup은 `RECONCILING`, exhaustion은 `MANUAL_REVIEW`다.
- Loyalty와 Promotion은 resolution source에 묶인 owner-local idempotent restoration을 수행한다. Order
  termination trigger를 가장하거나 S90 goodwill benefit을 발급하지 않는다.
- Settlement는 confirmed fact를 overwrite하지 않고 immutable adjustment만 추가한다.
- owner success 뒤 다른 step이 실패해도 성공 결과를 rollback하지 않는다. 같은 source/payload replay로 Support
  result를 복구하며 다른 payload reuse는 거부한다.
- Order는 PREPARING/READY/COMPLETED에서 과거 상태로 rollback하거나 CANCELLED로 변경하지 않는다.
