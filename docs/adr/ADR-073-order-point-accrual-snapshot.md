# ADR-073: 주문 시점 일반 적립 snapshot과 frozen V1 trigger boundary

- **Status:** Accepted
- **Date:** 2026-08-01
- **Amends:** ADR-011, ADR-065, ADR-068
- **Implementation owners:** [ordinary accrual policy/snapshot foundation](../exec-plans/completed/ordinary-point-accrual-policy-management.md), [completed Plan 13 consumer](../exec-plans/completed/customer-order-cancellation-13-refund-earned-point-recovery-foundation.md)

## Context

BR-10은 일반 포인트 적립의 기준을 최종 실결제액으로 정했지만, 완료 시점에 live
policy를 읽어 적립률·반올림·issuer·만료를 계산하면 정책 변경 뒤 같은 주문의 결과를
재현할 수 없다. `OrderCompletedV1`은 식별자와 완료 시각만 가진 frozen 계약이고,
Plan 20 소유의 `OrderCompletedV2`는 Plan 16·Plan 13 이후에만 가능하므로 Plan 13이 이를
기다리면 later-accrual milestone에 순환 의존성이 생긴다.

부분 환불은 성공한 conceptual unit을 기준으로 진행된다. 따라서 Order 전체의 gross
적립액만 남기면 어느 unit의 refund가 얼마의 earned-point recovery 대상인지 결정적으로
재현할 수 없다.

현재 부분 환불은 `PAID`, `ACCEPTED`, `PREPARING`, `READY`, `COMPLETED`에서 모두 가능하다.
Provider 결과가 비동기이므로 Refund 요청 시점의 Order 상태는 Refund 성공 시점에 이미
accrual이 존재했는지 증명하지 못한다. 완료 전 성공한 Refund에 pending debt를 만들면 Order가
완료되지 않을 때 존재하지 않았던 credit을 회수하게 된다.

## Decision

### 생성 시점과 소유권

Ordering은 Order 생성 transaction에서 일반 적립 결과를 immutable
`OrderPointAccrualSnapshot`으로 materialize한다. snapshot은 적어도 다음 값을 담는다.

- 주문과 생성 source/version, 적립 정책 version 또는 hash
- 전체 `grossAccrualAmountKrw`
- 각 OrderLine conceptual unit의 immutable accrued amount와 그 합계 tie-out
- 새 PointLot의 issuer type/reference와 만료 시각을 결정하는 immutable policy input

forward migration 전에 존재한 Order는 current policy로 backfill하지 않는다. migration은 이 Order를
`LEGACY_NOT_APPLICABLE`로 표시하는 immutable accrual source만 만들고 policy 값과 unit amount를
저장하지 않는다. 새 코드로 생성한 Order는 `SNAPSHOTTED` source와 완전한 snapshot이 필수다.

BR-10과 ADR-074의 closed vocabulary 안에서 선택된 Operations policy version이
적립률·반올림, issuer type/reference, 만료 rule/기간의 원천이다. 이 ADR은 특정 version의
운영 값을 hard-code하지 않는다. 필수 GLOBAL policy가 없거나 선택·snapshot에 실패하면
configuration default, live fallback, 임의 PLATFORM issuer 또는 0원 credit으로 대신하지 않는다.

BR-10은 unit allocation과 refund timing을 다음처럼 확정한다. `grossAccrualAmountKrw`를
conceptual unit의 `cashPayableKrw` 비례·정수 나눗셈으로 배분하고 remainder는 cash 내림차순,
`lineSequence`·`unitPosition` 오름차순으로 준다. Payment 성공 allocation의
`refundSucceededAt <=` Ordering의 persisted `completedAt`이면 Refund 우선
`EXCLUDED_BEFORE_ACCRUAL`, 그 뒤면 actual `RECOVERY`/pending이다. 완료 전 성공 source는
Payment-owned durable eligibility work로 보존하며 terminal non-completion이면
`NOT_APPLICABLE`로 닫는다.

### 완료 trigger와 typed boundary

`OrderCompletedV1`은 기존 field를 유지한 trigger다. Loyalty의 consumer는
`OrderPointAccrualSnapshotOperations`와 동등한 typed Ordering boundary로 order ID와
completion source/version에 맞는 immutable snapshot을 읽는다. 이 boundary는 현재 Order
상태, 현재 point policy 또는 변경 가능한 pricing data를 반환하지 않으며 public HTTP/event
계약도 아니다.

Loyalty는 snapshot의 gross amount로 새 PointLot과 `ACCRUAL` ledger를 만들고, 같은 local
transaction에서 ADR-065의 oldest-first `PointRecoveryPending` offset을 수행한다. 성공
Refund source consumer는 같은 snapshot의 unit allocation을 candidate로 사용하되, immutable
completion/refund timing 판정 뒤에만 완료 후 unit의 recovery target을 계산한다. 완료 전 성공
unit은 recovery/pending이 아니라 후속 accrual exclusion으로 보존한다. nonterminal Order의
Refund success는 Payment-owned eligibility work가 완료/terminal fact를 얻을 때까지 재시도하며,
request-time Order state나 event delivery 순서를 쓰지 않는다.
`OrderCompletedV1`을 replay해도 completion/accrual source unique와 snapshot
source/version/hash 검증이 중복 accrual과 다른 payload의 덮어쓰기를 막는다.

### 실패와 호환성

`SNAPSHOTTED` source의 snapshot이 없거나 불변 값이 source/version/hash와 맞지 않으면 Loyalty는 Lot, `ACCRUAL`,
`RECOVERY`, pending summary를 전혀 변경하지 않는다. source 처리는 retry 또는
manual-review/reprocessing 대상으로 남고, Ordering의 기존 완료 사실을 되돌리지 않는다.
typed boundary의 일시 실패도 같은 방식으로 관측한다.

`LEGACY_NOT_APPLICABLE` source는 snapshot 누락 오류가 아니라 명시적인 rollout 이전 Order다.
completion/refund consumer는 이를 terminal no-accrual/no-recovery로 처리하고 current policy를 읽거나
0원 snapshot을 합성하지 않는다. marker가 없는 missing source를 legacy로 추측하지 않는다.

이 boundary는 ADR-068의 좁은 예외이며 `OrderCompletedV1`을 확장하거나 Plan 20의
`OrderCompletedV2` producer/cutover ownership을 바꾸지 않는다. Plan 20은 이후 호환 가능한
immutable source를 V2 payload에 사용할 수 있지만 Plan 13의 완료 조건은 V2에 의존하지
않는다.

## Alternatives Considered

### 완료 시점의 현재 정책으로 재계산

구현은 작아 보이지만 정책 변경이 과거 주문의 accrual과 refund recovery를 바꾸고
ADR-068의 live-policy 금지를 위반하므로 제외한다.

### `OrderCompletedV1`에 적립 fields 추가

consumer가 추가 조회를 하지 않아도 되지만 frozen V1 계약을 깨고 기존 publication의
의미를 바꾸므로 제외한다.

### Plan 20의 `OrderCompletedV2`까지 accrual을 미룸

payload는 완전하지만 Plan 13 → Plan 16 → Plan 20 → Plan 13의 의존 cycle이 생기고
Plan 13의 later-accrual milestone을 충족하지 못하므로 제외한다.

## Rationale

Order가 가격·결제 예정액과 unit allocation을 확정하는 시점에 accrual input도 함께
고정하면, 완료 시점과 부분 환불 시점 모두 live policy 없이 같은 고객 credit/debit을
재현할 수 있다. V1을 trigger로 유지해 계약 호환성을 지키면서도 Plan 13의 자체적
진행을 가능하게 한다.

## Consequences

- ordinary-accrual policy/snapshot foundation이 Ordering snapshot persistence, typed boundary와
  ADR-074 version selection/bootstrap을 구현한다.
- Plan 13은 completed typed boundary를 소비해 `ACCRUAL`/`RECOVERY` ledger와 pending offset을
  구현하고 특정 policy 값을 source default로 고정하지 않는다.
- snapshot schema가 포함된 migration은 ADR-072 migration-writer lease 아래에서만 추가한다.
- snapshot의 raw issuer reference, policy value, order/customer ID는 metric tag나 public
  event payload에 넣지 않는다.

2026-08-02 implementation outcome: Plan 13은 canonical snapshot SHA-256, Payment completion
outcome/refund work와 Loyalty immutable result receipt를 추가했다. normal/pre-completion/out-of-order
flow와 V1 replay가 PostgreSQL/application tests에서 같은 source/version/hash 결과를 냈다.

## Verification

- 주문 생성 뒤 active policy가 바뀌어도 같은 snapshot으로 gross accrual과 issuer/expiry가
  재현된다.
- unit accrual allocation 합계는 gross amount와 같고, 성공 부분 환불 unit의 recovery
  target은 같은 snapshot으로 재현된다.
- 완료 전 성공 Refund는 future accrual에서만 제외되고 pending을 만들지 않으며, 완료 후
  성공 Refund만 실제 `RECOVERY`/pending branch로 간다. 동시 시각과 out-of-order delivery도
  동일 durable timing source로 재현된다.
- V1 replay는 한 번만 accrual하며 source/version/hash가 다른 snapshot은 conflict로
  남는다.
- snapshot 누락·boundary failure·DB write failure는 0원 적립이나 live-policy fallback 없이
  retry/manual-review로 관측된다.
- migration 이전 Order만 `LEGACY_NOT_APPLICABLE`로 terminal 처리되고, 신규 Order의 marker/snapshot
  누락은 같은 상태로 위장되지 않는다.
- gross `ACCRUAL` 뒤 oldest-first pending offset, Account/Lot/pending summary tie-out과
  non-negative balance를 PostgreSQL Testcontainers와 concurrent test로 검증한다.

## Metrics

- `beanflow.loyalty.point_accrual.snapshot.read.count{outcome}`
- `beanflow.loyalty.point_accrual.snapshot.conflict.count{outcome}`

`orderId`, customer ID, issuer reference, policy value와 raw source reference는 metric tag로
사용하지 않는다.

## Revisit Conditions

multiple issuer funding, campaign-specific ordinary accrual, cross-database Ordering/Loyalty storage,
or a retained V1/V2 dual-publication requirement requires a new compatibility and ownership decision.

## Related Decisions

- BR-10, BR-12, BR-13, BR-20
- [ADR-011](ADR-011-point-lot-ledger.md)
- [ADR-014](ADR-014-money-allocation-and-partial-refund.md)
- [ADR-065](ADR-065-refund-earned-point-recovery-ledger.md)
- [ADR-068](ADR-068-immutable-integration-event-snapshots.md)
- [ADR-072](ADR-072-execplan-unattended-execution-and-migration-lane.md)
- [ADR-074](ADR-074-global-and-store-scoped-point-accrual-policy.md)
