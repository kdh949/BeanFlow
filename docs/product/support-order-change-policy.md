# Support Order Change Policy

> **Status:** Lifecycle-aware direct change versus post-acceptance resolution is Accepted in ADR-085; command/DTO names
> and ACCEPTED-state delegation numbers remain DRAFT or Initial assumptions until S70.

## Lifecycle-aware behavior

| Order state | Cancellation | Pickup reschedule |
|---|---|---|
| PENDING_PAYMENT | BASIC 후보; Order와 예약 자원 해제, 환불 없음 | 새 slot을 먼저 확보하고 성공 시 기존 slot 해제 |
| PAID, pre-acceptance | 기존 환불·혜택 복원·정산 제외 흐름 사용 | 같은 Store/Item/Option/수량/금액에서 시간만 변경 |
| ACCEPTED, pre-preparation | 매장 건별 동의 또는 versioned delegation, 비용 책임 확정 | 매장 동의/위임; 초기 횟수·시간 수치는 Assumption |
| PREPARING/READY | 직접 rollback/cancel 금지; ResolutionCase | 금지 |
| COMPLETED | Order 유지, 별도 refund/benefit/settlement resolution | 금지 |
| CANCELLED/REJECTED/EXPIRED | 재취소·상태복구 금지; 누락 부수효과 reconcile만 | 금지 |

새 slot 확보가 실패하면 기존 예약은 유지한다. ACCEPTED와 PREPARING 경쟁에서는 최신 잠금 상태가 PREPARING이면 직접 변경을 거부하고 post-acceptance resolution로 전환한다. Support는 고객을 가장하지 않고 Ordering/Fulfillment의 별도 공개 command를 호출한다.
