# ADR-064: 위험 기반 멱등성 모델 선택

- **Status:** Accepted
- **Date:** 2026-08-01
- **Amends:** ADR-032의 일반 사전등록/명령 트랜잭션 선택 기준

## Context

BeanFlow에는 사전등록 모델과 명령 트랜잭션 모델이 있다. 사전등록 모델은 `PROCESSING`
레코드를 먼저 commit하고 외부 결과 불명 또는 root 생성 경쟁을 reconciliation으로 다룬다.
명령 트랜잭션 모델은 기존 Aggregate root lock, 도메인 전이와 최초 응답을 하나의 로컬
transaction에서 commit한다.

기존 BR-25와 ADR-032는 외부 호출과 새 Aggregate 생성을 함께 사전등록 기준으로
서술했지만, `transaction-boundaries.md`는 둘 중 하나만 있어도 사전등록이라고
서술했다. 미수락 `PAID` 고객 취소 C1은 Order row lock 아래에서
OrderCompensationCase, Refund, NotificationDelivery와 publication을 새로 저장하지만
외부 Provider를 명령 transaction 안에서 호출하지 않는다. 새 Aggregate 생성만으로
사전등록을 강제하면 ADR-032와 ADR-035가 확정한 C1 command transaction을 뒤집게 된다.

## Decision

### 모델 선택 기준

- **명령 트랜잭션 모델**은 다음이 모두 성립할 때 사용한다.
  1. 명령 시작 전에 기존의 lock 가능한 Aggregate root가 경쟁을 직렬화한다.
  2. 그 root의 guarded transition, 새로 만드는 로컬 Aggregate, persistent publication과
     최초 응답을 하나의 짧은 로컬 DB transaction에서 함께 commit하거나 rollback한다.
  3. 명령 transaction 안에는 외부 Provider 호출이나 결과 불명 구간이 없다.
- **사전등록 모델**은 다음 중 하나라도 성립할 때 사용한다.
  1. 기존 직렬화 root가 없어 같은 key의 동시 요청이 새 root 또는 외부 부수효과를
     경쟁적으로 시작할 수 있다.
  2. 외부 Provider 호출 또는 결과 불명 구간이 최초 terminal 응답을 내구 저장하기 전에
     존재한다.
- 새 Aggregate 생성 자체는 선택 기준이 아니다. 기존 root lock과 원자적 로컬 commit이
  있으면 C1처럼 여러 로컬 Aggregate를 만들어도 명령 트랜잭션 모델을 사용한다.

### 기존 명령 분류

| 명령 | 모델 | 근거 |
|---|---|---|
| 주문 생성 | 사전등록 | 기존 Order root가 없고 생성 경쟁을 먼저 arbitration해야 함 |
| 빠른 재주문 | 사전등록 | source Order는 immutable 입력이고 결과인 새 Order root가 아직 없어 기존 주문 생성 Tx I1/Tx O/Tx I2를 재사용해야 함 |
| 결제 승인 | 사전등록 | 외부 Provider 결과가 불명일 수 있음 |
| 매장 주문 상태 전이 | 명령 트랜잭션 | 기존 Order lock과 로컬 guarded transition |
| 고객 취소 C0 | 명령 트랜잭션 | 기존 Order lock, 로컬 해제·Audit·Delivery와 최초 200 commit |
| 고객 취소 C1 | 명령 트랜잭션 | 기존 Order lock, 모든 durable 후속 work와 최초 202의 로컬 commit, Provider 호출은 밖 |
| 감사형 포인트 조정 | 명령 트랜잭션 | 기존 PointAccount lock, Lot·원장·Audit·201의 로컬 commit, Provider 호출 없음 |

`IDEMPOTENCY_REQUEST_IN_PROGRESS`와 stuck-record reconciliation은 사전등록 모델에만
사용한다. 명령 트랜잭션 모델에서 rollback된 요청은 멱등 레코드를 남기지 않으며,
외부 부수효과가 rollback 뒤 재실행될 수 있는 구조를 추가해서는 안 된다.

## Alternatives Considered

### 외부 호출과 새 Aggregate가 모두 있을 때만 사전등록

- C1을 command transaction으로 유지한다.
- 기존 root가 없는 생성 경쟁을 충분히 설명하지 못하고, 어떤 root가 직렬화를 제공하는지
  검증 기준이 없다.

### 외부 호출 또는 새 Aggregate가 있으면 사전등록

- 기준이 짧고 보수적이다.
- C1의 원자적 local commit을 불필요하게 두 transaction과 `PROCESSING` lifecycle로
  바꾸며 ADR-032/035와 충돌한다.

### 모든 멱등 명령에 사전등록 적용

- 운영 절차가 하나로 통일된다.
- 외부 결과 불명 구간이 없는 명령에도 stuck reconciliation과 `MANUAL_REVIEW`를 도입해
  실패 표면과 운영 비용을 늘린다.

## Rationale

멱등성 모델은 생성한 Aggregate 수가 아니라 중복 경쟁을 직렬화할 durable root의 존재와
외부 결과 불명 위험으로 선택해야 한다. C1은 Order lock이 경쟁을 직렬화하고 모든 local
후속 작업·응답이 함께 rollback될 수 있으므로 사전등록의 `PROCESSING` 창이 필요 없다.
반대로 기존 root 없이 시작하는 주문 생성과 외부 Provider 결과를 먼저 다루는 결제 승인은
사전 arbitration과 reconciliation이 필요하다.

## Consequences

- C1은 `PROCESSING`, stuck reconciliation, `IDEMPOTENCY_REQUEST_IN_PROGRESS` 없이
  command transaction을 유지한다.
- 새 command는 구현 전에 직렬화 root, local commit 범위, 외부 호출 위치를 설계 문서와
  Required Tests에 기록해야 한다.
- 명령 transaction에 외부 호출 또는 rollback 뒤 남는 부수효과를 추가하려면 모델을
  재평가하고 새 ADR amendment를 작성한다.
- `transaction-boundaries.md`, BR-25와 ADR-032의 일반 기준은 이 ADR에 맞춘다.

## Failure Scenarios

- 기존 root lock 없이 새 Aggregate를 만들면 같은 key 요청이 서로 다른 root를 만들 수
  있으므로 사전등록 없이 command transaction으로 시작하지 않는다.
- Provider 호출 뒤 DB commit이 실패하면 외부 결과가 불명이다. 이를 command transaction
  rollback으로 성공처럼 감추지 않고 사전등록·reconciliation 상태를 사용한다.
- C1에 외부 호출을 넣으면 rollback된 요청이 Provider 부수효과를 재실행할 수 있으므로
  현재 모델을 유지하지 않는다.

## Verification

- 고객 취소 C0/C1은 새 local Aggregate 수와 무관하게 Order lock 아래 한 transaction으로
  최초 200/202 body를 저장한다.
- 같은 C1 key·payload 재요청은 Case, Refund, Delivery, publication을 다시 만들지 않는다.
- 주문 생성은 기존 root 없이 같은 key의 동시 요청을 사전등록 unique record로 arbitration한다.
- 빠른 재주문은 source Order를 target root로 오인하지 않고 `REORDER_ORDER_V1` 사전등록과
  기존 원자적 주문 생성 transaction으로 새 Order 하나를 만든다.
- 결제 승인 Provider timeout은 `PROCESSING`/`UNKNOWN`과 reconciliation으로 남는다.
- 새 명령 설계 review가 직렬화 root·외부 호출 위치·rollback 뒤 부수효과를 명시하지 않으면
  구현을 시작하지 않는다.

**Point adjustment implementation evidence (2026-08-04):** 기존 PointAccount를 먼저 잠그고
grant, terminal response, Lot/ledger/Audit/outbox를 한 local transaction에 저장한다. Provider
호출과 `PROCESSING` 상태는 없고 rollback은 terminal row를 남기지 않는다. replay, changed
account/payload와 동시 debit/cross-account command를 PostgreSQL에서 검증했다.

## Required Tests

- C1의 Case·Refund·Delivery·publication과 최초 202 response의 단일 local commit
- C1의 같은 key 동시 요청에서 하나의 durable work set과 동일 body 재생
- C1 저장 실패 rollback 뒤 Provider 호출과 persistent publication 부재
- 주문 생성의 사전등록 `PROCESSING`과 stuck reconciliation
- 빠른 재주문의 source/request payload conflict, PROCESSING과 terminal exact replay
- 결제 승인 timeout의 unknown/reconciliation과 재승인 방지
- 새 command 분류 review fixture: root 없음, 외부 호출 있음, 둘 다 없는 local transition

## Metrics

- `beanflow.order.idempotency.model_selection.count{model,operation,outcome}`
- `beanflow.order.idempotency.command_transaction.rollback.count{operation}`
- `beanflow.order.idempotency.preregistration.reconciliation.count{operation}`

Order, customer, store ID와 `Idempotency-Key`는 metric tag로 사용하지 않는다.

- **Not measured:** 모델별 실제 재시도율과 lock wait 분포

## Revisit Conditions

새 외부 Provider, 기존 root 없는 복합 생성 명령, C1 외부 호출, cross-database transaction,
또는 lock wait 병목 측정 결과가 도입될 때

## Related Decisions

- BR-25, BR-26
- [ADR-006](ADR-006-external-payment-transaction-boundary.md)
- [ADR-007](ADR-007-payment-idempotency-reconciliation.md)
- [ADR-025](ADR-025-order-creation-idempotency-transaction.md)
- [ADR-032](ADR-032-customer-cancellation-idempotency.md)
- [ADR-035](ADR-035-paid-cancellation-transaction-boundary.md)
- [ADR-056](ADR-056-ordering-idempotency-retention-worker.md)
- [ADR-066](ADR-066-audited-loyalty-point-adjustment.md)
- [ADR-077](ADR-077-fast-reorder-order-creation-api-identity.md)
