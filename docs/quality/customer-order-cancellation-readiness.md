# Customer Order Cancellation Runtime Readiness

## Current verdict

```text
CLEAN_CUTOVER_GATE = PASSED
CUSTOMER_CANCELLATION_RUNTIME = IMPLEMENTED
NON_LOCAL_DEPLOYMENT = NOT EVIDENCED
```

현재 `main`에서 고객 취소 command와 recovery는 구현됐고 source 계약·테스트·quality
evidence가 존재한다. 이 판정은 현재 source runtime capability에 대한 것으로 실제 non-local
배포, 운영 smoke test, SLA 또는 production 안정성을 뜻하지 않는다.

역사적 clean-cutover inventory와 attestation은
[Customer Cancellation Release Evidence](customer-order-cancellation-release-evidence.md)에
있다. `CLEAN_CUTOVER_GATE = PASSED`는 migration/event 전략을 허용한 시점의 증거이며 현재
구현 완료 근거는 아래 코드·테스트·완료 ExecPlan이다.

## Implemented runtime capability

- `POST /api/v1/orders/{orderId}/cancellations`
  - `PENDING_PAYMENT`: Order와 예약 자원 해제를 한 transaction으로 commit하고 `200`
  - 미수락 `PAID`: Order 취소와 durable compensation 시작을 commit하고 `202`
  - 같은 key/payload는 최초 body를 재생하고 다른 payload는 `409`
- `GET /api/v1/orders/{orderId}`
  - 고객용 refund recovery projection을 제공하되 내부 attempt/error/manual-review detail은 숨긴다.
- `GET /api/v1/operations/orders/{orderId}/compensation`
  - audited operator grant와 access reason을 요구하고 여섯 step의 운영 상세를 제공한다.
- `POST /api/v1/operations/orders/{orderId}/customer-cancellation-refund-reconciliations`
  - 불명 환불을 성공으로 단정하지 않고 durable reconciliation work를 접수한다.
- `GET/PATCH /api/v1/store-orders/...`
  - 매장에는 trigger/state/updatedAt만 담은 축약 compensation projection을 제공한다.

전체 operation 목록은
[Runtime OpenAPI](../../openapi/beanflow-v1-runtime.yaml)가 소유하며
`RuntimeOpenApiParityTest`가 Spring MVC mapping과 양방향 검증한다.

## Evidence graph

완료된 direct command/recovery evidence:

- [Plan 40 Customer Cancellation Command](../exec-plans/completed/customer-order-cancellation-40-command.md)
- [Plan 50 Customer Cancellation Recovery](../exec-plans/completed/customer-order-cancellation-50-recovery.md)
- [Completed Master Orchestration](../exec-plans/completed/customer-order-cancellation-and-recovery.md)

완료된 주요 foundation과 후속 capability:

- [Partial Refund Allocation and Restoration](../exec-plans/completed/customer-order-cancellation-12-partial-refund-allocation-and-restoration.md)
- [Refund Earned-Point Recovery](../exec-plans/completed/customer-order-cancellation-13-refund-earned-point-recovery-foundation.md)
- [Immutable Refund and Loyalty Events](../exec-plans/completed/customer-order-cancellation-16-immutable-refund-and-loyalty-event-producer.md)
- [Settlement Foundation](../exec-plans/completed/customer-order-cancellation-20-settlement-foundation.md)
- [Order Compensation Foundation](../exec-plans/completed/customer-order-cancellation-30-order-compensation-foundation.md)
- [Settlement Batch, Adjustment, and Dispute](../exec-plans/completed/settlement-batch-adjustment-and-dispute.md)
- [Audited Loyalty Point Adjustment](../exec-plans/completed/loyalty-point-adjustment-foundation.md)

PointAccount read는 별도 Active work다:

- [Plan 14 PointAccount Read Vertical Slice](../exec-plans/active/customer-order-cancellation-14-point-account-read-vertical-slice.md)

이 두 GET operation의 부재는 customer cancellation command/recovery의 완료 상태를 되돌리지
않는다.

## Protected invariants

- 고객 취소 허용 상태와 acceptance deadline을 벗어나면 state conflict다.
- `PENDING_PAYMENT`의 네 owner release가 하나라도 실패하면 취소 성공을 반환하지 않는다.
- `PAID`의 `202`는 환불·복원·알림 완료가 아니며 외부 결과 불명은 durable 상태로 남는다.
- Refund 요청액, 선행 성공 환불액과 remaining refundable 금액은 snapshot/current 의미를
  섞지 않는다.
- `detail`은 API response, event payload, Provider request와 log에 노출하지 않는다.
- Settlement 확정 금액은 overwrite하지 않고 Adjustment ledger로만 보정한다.
- Aggregate 간에는 ID와 immutable snapshot을 사용하며 cross-context cascade를 만들지 않는다.

## Validation evidence

대표 자동 검증은 다음을 포함한다.

- `CustomerCancellationCommandIntegrationTest`
- `CustomerCancellationPaymentServiceTest`
- `CustomerCancellationCompensationWorkerTest`
- `CustomerCancellationRefundReconciliationServiceTest`
- `OrderControllerContractTest`
- `SettlementDisputeIntegrationTest`
- `RuntimeOpenApiParityTest`
- `ModularityTests`

각 완료 시점의 전체 test count와 명령은 해당 ExecPlan과 release evidence의 역사적 기록이다.
현재 HEAD 검증 결과는 repository truth audit ExecPlan에 별도로 기록한다.

## Remaining work and non-goals

- Active: PointAccount summary/transaction read vertical slice
- Active: Analytics refund/late-event projection
- Active: Nearby Store Discovery
- Not evidenced: non-local deployment와 rollback binary
- Not measured: 운영 traffic에서의 처리량, p95/p99 latency, Provider 장애 주입, SLA
- Non-goal: fake/in-memory/no-op fallback, 실제 PG adapter를 자동 대체하는 local behavior

## Revisit conditions

- 고객 취소 허용 상태, refund projection 또는 compensation ownership 변경
- external consumer 또는 applied production migration 발견
- 최초 non-local deployment 직전 clean-cutover inventory 재검증
- PointAccount read를 customer cancellation command의 선행조건으로 바꾸는 제품 결정

## Revision notes

- 2026-07-31: 계약과 clean-cutover foundation 착수 readiness 감사 작성.
- 2026-08-06: 현재 `main`의 Plan 40/50, Settlement lifecycle과 loyalty adjustment 완료를
  반영해 command blocker를 제거하고 PointAccount read를 독립 Active work로 분리했다.
