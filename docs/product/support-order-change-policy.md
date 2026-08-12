# Support Order Change Policy

> **Status:** Lifecycle-aware direct change and ACCEPTED initial confirmation/delegation limits are Accepted in
> ADR-085/SP-19 and implemented by S70 typed Support/Ordering/Fulfillment contracts.

## ACCEPTED store authorization

Initial immutable policy version은 `support-order-change-policy/2026-08-12/v1`이다.

| Action | Delegation TTL | Successful-use budget | Binding |
|---|---:|---:|---|
| cancellation | 10분 | 1회 | store + action + policy version |
| pickup reschedule | 30분 | 3회 | store + action + policy version |

`now >= expiresAt`이면 만료다. 같은 idempotency replay는 budget을 다시 소비하지 않고 owner direct
change와 Support execution outcome이 함께 commit된 최초 실행만 1회를 소비한다. validation 실패,
slot full, transaction rollback과 `RESOLUTION_REQUIRED`는 소비하지 않는다.

건별 store confirmation은 exact Support request/revision/action payload digest/target version에 고정하며
그 request보다 오래 살 수 없다. confirmation/delegation은 store actor membership 확인과 STORE 비용
책임의 명시 수락을 요구한다. 책임 미확정 또는 PLATFORM 귀속이 필요한 건은 direct change를 허용하지
않고 post-acceptance resolution로 보낸다. renewal/한도 변경은 새 authorization 또는 새 immutable policy
version으로만 수행하고 기존 row를 소급 수정하지 않는다.

## Lifecycle-aware behavior

| Order state | Cancellation | Pickup reschedule |
|---|---|---|
| PENDING_PAYMENT | BASIC 후보; Order와 예약 자원 해제, 환불 없음 | 새 slot을 먼저 확보하고 성공 시 기존 slot 해제 |
| PAID, pre-acceptance | 기존 환불·혜택 복원·정산 제외 흐름 사용 | 같은 Store/Item/Option/수량/금액에서 시간만 변경 |
| ACCEPTED, pre-preparation | exact 매장 동의 또는 10분/1회 delegation, STORE 비용 책임 확정 | exact 매장 동의 또는 30분/3회 delegation, STORE 비용 책임 확정 |
| PREPARING/READY | 직접 rollback/cancel 금지; ResolutionCase | 금지 |
| COMPLETED | Order 유지, 별도 refund/benefit/settlement resolution | 금지 |
| CANCELLED/REJECTED/EXPIRED | 재취소·상태복구 금지; 누락 부수효과 reconcile만 | 금지 |

새 slot 확보가 실패하면 기존 예약은 유지한다. ACCEPTED와 PREPARING 경쟁에서는 최신 잠금 상태가 PREPARING이면 직접 변경을 거부하고 post-acceptance resolution로 전환한다. Support는 고객을 가장하지 않고 Ordering/Fulfillment의 별도 공개 command를 호출한다.

S70 runtime은 `POST /support/action-requests/{requestId}/executions`에서 canonical action payload를 다시 hash하고
exact ready revision, policy, verification, permissions와 Ordering version을 검사한다. ACCEPTED authorization은
`POST /stores/{storeId}/support-order-change-authorizations`에서만 생성한다. 실행 결과는 owner before/after
state/version과 closed recovery summary만 포함하고 모든 성공 응답은 `Cache-Control: no-store`다.
