# Customer Order Cancellation and Recovery

> **Status:** `COMPLETED`
> **Kind:** `ORCHESTRATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-08-06`

## Purpose

고객 주문 취소를 계약 baseline, 환불 allocation, 포인트·쿠폰 복원, immutable event,
정산 snapshot, 공통 compensation, command와 recovery까지 순서대로 닫은 orchestration의
완료 기록이다. 이 문서는 구현 계약을 새로 정의하지 않고 direct successor들의 결정과
검증 증거를 찾아가는 입구다.

PointAccount read는 고객 취소 command/recovery와 독립된 completed work다. 따라서 summary와
transaction 조회의 완료가 이미 닫힌 고객 취소 command/recovery의 scope를 바꾸지 않는다.

## Current State

현재 `main`에는 다음 runtime capability가 존재한다.

- 고객 소유 Order의 `PENDING_PAYMENT` 동기 취소와 미수락 `PAID` 비동기 보상 시작
- `Idempotency-Key` 기반 최초 `200`/`202` body 재생과 다른 payload 충돌
- 환불 allocation, 쿠폰·사용 포인트 복원, 적립 포인트 회수와 부족분 pending
- Refund·Loyalty immutable integration event와 Settlement input snapshot
- 공통 CompensationCase/Step, 자동 worker, 운영자 repair proposal과 refund reconciliation
- 고객·매장·운영자 역할별 recovery projection

Runtime operation과 target operation의 차이는
[Runtime OpenAPI](../../../openapi/beanflow-v1-runtime.yaml)와
[Target OpenAPI](../../../openapi/beanflow-v1.yaml)에서 관리한다. source 구현은 실제 non-local
deployment 증거가 아니다.

## Scope

완료된 orchestration 범위는 다음과 같다.

1. cancellation API와 clean-cutover contract baseline
2. PointLot issuer provenance와 만료 혜택 복원 정책
3. 부분 환불 allocation 및 복원
4. 환불 적립 포인트 회수와 immutable event producer
5. Order settlement input snapshot과 Settlement foundation
6. trigger-aware 공통 compensation
7. 고객 취소 command와 durable recovery

## Non-goals

- PointAccount summary/transaction read 구현
- 실제 PG adapter 또는 non-local deployment
- Analytics dependency 구조 변경
- 새 endpoint, event 또는 migration 추가

## Business Rules and Invariants

- 고객 취소 허용 상태는 `PENDING_PAYMENT`와 매장 수락 전 `PAID`다.
- `PENDING_PAYMENT` 취소는 Order와 네 예약 자원 해제가 한 local transaction에서 모두
  commit된 뒤에만 `200`이다.
- `PAID` 취소의 `202`는 Order 취소와 durable compensation 시작만 뜻하며 외부 환불 성공을
  뜻하지 않는다.
- `detail`은 저장할 수 있지만 API 응답, event, Provider 요청과 log에 노출하지 않는다.
- 외부 결과 불명은 성공 또는 확정 실패로 축약하지 않고 `UNKNOWN`, `RECONCILING`,
  `MANUAL_REVIEW` 등 durable 상태로 남긴다.
- 확정 Settlement Item은 덮어쓰지 않고 Adjustment ledger로만 보정한다.

## Aggregate and Transaction Boundaries

- Ordering이 Order 취소 전이와 명령 transaction을 소유한다.
- Payment가 Refund와 외부 Provider 호출의 Tx1/Provider/Tx2 경계를 소유한다.
- Loyalty와 Promotion은 공개 owner command로 포인트·쿠폰 복원을 수행한다.
- Settlement는 immutable input snapshot을 소비하고 확정 금액은 Adjustment로만 변경한다.
- Operations가 CompensationCase/Step, reconciliation과 privileged repair audit를 소유한다.
- Aggregate 간 참조는 식별자와 immutable snapshot을 사용하며 객체 graph/cascade로 연결하지
  않는다.

## Completed Successors

- [Plan 00 Contract Baseline](customer-order-cancellation-00-contract-baseline.md)
- [Plan 10 PointLot Issuer Provenance](customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md)
- [Plan 11 Benefit Policy and Operator Grant](customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md)
- [Plan 12 Partial Refund Allocation and Restoration](customer-order-cancellation-12-partial-refund-allocation-and-restoration.md)
- [Plan 13 Refund Earned-Point Recovery](customer-order-cancellation-13-refund-earned-point-recovery-foundation.md)
- [Plan 15 Settlement Input Snapshot](customer-order-cancellation-15-settlement-input-snapshot-foundation.md)
- [Plan 16 Immutable Refund and Loyalty Events](customer-order-cancellation-16-immutable-refund-and-loyalty-event-producer.md)
- [Plan 20 Settlement Foundation](customer-order-cancellation-20-settlement-foundation.md)
- [Plan 30 Order Compensation Foundation](customer-order-cancellation-30-order-compensation-foundation.md)
- [Plan 40 Customer Cancellation Command](customer-order-cancellation-40-command.md)
- [Plan 50 Customer Cancellation Recovery](customer-order-cancellation-50-recovery.md)

독립 후속 작업이었던
[Plan 14 PointAccount Read Vertical Slice](customer-order-cancellation-14-point-account-read-vertical-slice.md)는
customer/operator read와 V32 ledger index evidence로 완료됐다.

## Progress

- [x] 제품 정책과 ADR baseline 확정
- [x] 부분 환불 allocation과 benefit 복원
- [x] refund earned-point recovery
- [x] immutable integration event와 settlement snapshot
- [x] settlement 및 common compensation foundation
- [x] customer cancellation command
- [x] customer cancellation recovery
- [x] PointAccount read를 독립 Active work로 분리
- [x] master metadata와 경로를 completed로 정합화

## Validation Evidence

- [Customer Cancellation Release Evidence](../../quality/customer-order-cancellation-release-evidence.md)
- [Settlement Lifecycle Release Evidence](../../quality/settlement-lifecycle-release-evidence.md)
- [Quality Evidence Map](../../quality/quality-evidence-map.md)
- `CustomerCancellationCommandIntegrationTest`
- `CustomerCancellationPaymentServiceTest`
- `CustomerCancellationCompensationWorkerTest`
- `CustomerCancellationRefundReconciliationServiceTest`
- `PaymentEventConsumerTest`, `LoyaltyEventConsumerTest`
- `ModularityTests`

각 successor의 완료 시점 명령과 결과는 해당 ExecPlan과 release evidence에 기록한다. 이
orchestration 완료 표시는 실제 deployment, 운영 smoke test 또는 SLA를 주장하지 않는다.

## Outcomes and Retrospective

고객 취소는 하나의 거대한 transaction으로 묶지 않고 로컬 상태 전이와 durable recovery를
연결하는 구조로 완성됐다. command와 recovery가 닫힌 뒤에도 PointAccount read가 같은 master에
남아 있어 전체 상태가 Active로 보이던 drift를 제거했다.

비용은 여러 Context의 상태와 evidence를 함께 추적해야 한다는 점이다. 향후 고객 취소의
허용 상태, 환불 projection 또는 보상 ownership이 바뀌면 Business Policy와 관련 ADR을 먼저
갱신하고 새 implementation ExecPlan을 작성한다.

## Revision Notes

- 2026-08-06: 현재 `main`의 command/recovery 및 successor 완료 사실을 반영해 master를
  completed로 이동하고, PointAccount read를 독립 Active work로 분리했다.
