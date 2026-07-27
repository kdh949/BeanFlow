# Context Map

```text
Identity
  └─ actorId, membership ─────────────────────────────┐
                                                      v
Merchant ── menu, price, business hours ──> Ordering
   └──────────────────────────────> Discovery

Ordering
  ├─ reserve/confirm/release ──> Fulfillment
  ├─ reserve/confirm/release ──> Inventory
  ├─ validate/reserve/use ─────> Promotion
  ├─ reserve/use/restore ──────> Loyalty
  └─ payment request ──────────> Payment

Payment
  ├─ PaymentApproved/Unknown/Refunded ──> Ordering
  └─ payment/refund facts ──────────────> Settlement

Ordering
  ├─ OrderReady ───────────────> Notification
  ├─ OrderCompleted ───────────> Loyalty
  ├─ OrderCompleted ───────────> Settlement
  └─ order facts ──────────────> Analytics

Settlement
  ├─ settlement views ─────────> Dispute
  └─ adjustment command <────── Dispute

All transaction contexts
  └─ facts and failures ───────> Operations / Analytics
```

## Relationship rules

| Upstream | Downstream | Data owner | Interaction | Consistency |
|---|---|---|---|---|
| Merchant | Ordering | Merchant | 공개 동기 조회 | 주문 생성 시 현재 값 필요 |
| Ordering | Fulfillment | Fulfillment | 예약 Application API | 주문 생성 트랜잭션 내 강한 일관성 |
| Ordering | Inventory | Inventory | 예약 Application API | 주문 생성 트랜잭션 내 강한 일관성 |
| Ordering | Promotion | Promotion | 검증·예약 API | 주문 금액 확정 전 필요 |
| Ordering | Loyalty | Loyalty | 포인트 예약 API | 주문 금액 확정 전 필요 |
| Ordering | Payment | 각자 | Payment command, fact event | 외부 호출과 DB tx 분리 |
| Ordering | Notification | Ordering fact | after-commit event | eventual |
| Ordering | Settlement | Ordering fact | idempotent event | eventual |
| Settlement | Dispute | Settlement | 조회 API와 adjustment command | 판정 후 조정은 명시적 명령 |
| Transaction contexts | Analytics | 원본 Context | idempotent event | eventual, 재집계 가능 |

## Translation boundaries

- Payment는 외부 PG SDK 타입을 도메인에 노출하지 않는다.
- Notification은 Provider 상태를 BeanFlow delivery 상태로 번역한다.
- Discovery는 Merchant 쓰기 Entity를 검색 편의로 직접 확장하지 않는다.
- Analytics는 원본 거래 상태의 의미를 자체 지표 정의로 변환한다.
