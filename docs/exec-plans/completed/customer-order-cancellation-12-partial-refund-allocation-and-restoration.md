# 부분 환불 allocation과 포인트 복원 foundation을 만든다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-10-point-lot-issuer-provenance-foundation.md`, `docs/exec-plans/completed/customer-order-cancellation-11-benefit-policy-and-operator-grant-foundation.md`
> **Completed-At:** `2026-08-01`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

부분 환불이 line별 cash/point allocation을 한 번만 확정하고, 만료된 원 PointLot은 snapshot된
`PARTIAL_REFUND×POINTS` 정책에 따라 동일 issuer lineage의 보상 Lot으로 복원하게 만든다.

## Current State

- V15가 immutable request/success allocation, point restoration work와 Loyalty restoration 원장을
  제공한다.
- 공개 부분 환불 command와 별도 Provider/Loyalty result transaction, exact stored replay가 구현됐다.
- Plan 13은 이 Plan의 completed successful Refund source/allocation을 직접 소비할 수 있다.

## Definitions

- **Cash/point allocation:** 성공 Refund가 각 immutable OrderLine 몫에서 실제로 반환한 금액 원장.
- **Coupon attribution:** 복원 사실이 아닌 할인 귀속 감사 원장.

## Scope

### In Scope

- Refund line cash/point/coupon allocation schema와 deterministic partial-refund command
- policy version snapshot, point restoration/compensation, remaining-allocation read/lock API
- Payment/Loyalty owner 간 source-aware internal handoff

### Non-goals

- earned-point `RECOVERY`/pending offset, point-account read, immutable integration-event publication

## Business Rules and Invariants

- successful allocation 합은 line 원금과 Payment approved amount를 넘지 않는다.
- 부분 환불은 CouponIssuance/Reservation state를 바꾸거나 coupon restoration을 시작하지 않는다.
- 동일 source replay만 멱등이며 다른 payload는 conflict다.
- expired Lot 보상은 Plan 10 issuer snapshot과 Plan 11 policy version을 그대로 보존한다.

## Architecture and Transaction Boundaries

Ordering coordinator가 `Order → Payment → 정렬된 allocation → Point source/policy` 순서로 typed owner
API를 호출한다. Payment는 Refund request/result와 allocation/work 원장을 소유하고 Provider를 transaction
밖에서 호출한다. success result는 allocation/source를 commit한다. Loyalty는 별도 local transaction에서
restoration/compensation을 처리하며 Payment Aggregate를 직접 변경하지 않는다.

## Alternatives Considered

- 총 Refund amount에서 remaining을 역산: line별 반올림/복원 순서를 재현할 수 없어 제외한다.
- refund success transaction에서 Loyalty state를 직접 변경: Context boundary를 침범해 제외한다.

## Failure Semantics

policy snapshot/allocation 저장 실패는 Provider 호출 전 rollback/503이다. provider result 뒤 Loyalty
write failure는 0원 또는 성공으로 투영하지 않고 durable retry/manual-review로 남긴다.

## Data and Migration

Refund line allocation, point restoration, coupon attribution과 source/line upper-bound unique/CHECK를
Payment/Loyalty owner 경계에 맞춰 단독 migration한다. issuer schema와 policy/grant tables는 만들지 않는다.

## API and Event Contracts

ADR-061 `/payments/{paymentId}/refunds` 요청/상태 contract를 구현한다. internal source event는
복원 worker용이며 ADR-068 `PaymentRefundedV1` publication은 Plan 16이 소유한다.

## Milestones

1. allocation schema와 tie-out constraints를 구현한다.
2. deterministic request/result flow와 policy snapshot을 구현한다.
3. source-aware point restoration/compensation과 remaining read/lock API를 구현한다.

## Required Tests

- full/partial/replayed refund, rounding, concurrent line/approved upper bound
- policy change 전후 snapshot, expired boundary와 issuer lineage
- coupon non-restoration, later termination remaining allocation, Loyalty failure retry

## Validation Commands

```bash
./gradlew test --tests '*Refund*' --tests '*Allocation*' --tests '*Restoration*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

allocation tie-out/restoration disposition metric은 closed tags만 사용한다.

## Documentation Updates

ADR-014/036/061/063/068, OpenAPI/payment runbook과 Plan 13/16/40 evidence를 갱신한다.

## Progress

- [x] 2026-08-01 implementation preflight and contract audit
  - 변경 목적: 공개 full/item partial Refund가 immutable OrderLine cash/points/coupon snapshot과
    성공 allocation을 source-aware 원장으로 보존하고, 현금 성공 뒤 별도 Loyalty transaction에서
    원 PointLot 복원 또는 snapshot policy 기반 보상 Lot을 만들도록 한다.
  - 도메인 불변식: 성공 cash allocation 합은 Payment 승인액과 line cash 원금을, 성공 point
    restoration 합은 line 및 원 PointReservation allocation 금액을 넘지 않는다. 부분 환불은
    CouponIssuance/CouponReservation 및 PointReservation `USED` 상태를 바꾸지 않는다. 같은 source와
    payload만 replay하고 source가 같은 다른 payload는 conflict다.
  - 영향 모듈·Aggregate: Payment의 Payment/Refund와 line·point allocation 및 restoration work,
    Loyalty의 PointAccount/PointLot/PointReservation allocation/PointTransaction, Ordering의 immutable
    OrderLine refund snapshot boundary, Operations의 `PARTIAL_REFUND×POINTS` policy snapshot boundary가
    영향받는다. Context 사이에는 typed application DTO만 전달하고 JPA Entity/Repository를 직접
    참조하지 않는다.
  - 예상 변경 파일: Payment/Loyalty/Ordering application API와 service/persistence/controller/worker,
    `V15__*` Flyway migration, Refund/allocation/restoration PostgreSQL·contract·Modulith tests,
    target/runtime OpenAPI, payment/Loyalty runbook과 관련 ADR/architecture/readiness 문서다. 새 production
    dependency는 필요하지 않다.
  - 트랜잭션 경계와 lock: request transaction은 Payment와 기존 성공 allocation을 고정 순서로 잠근 뒤
    immutable OrderLine/PointReservation allocation과 policy version을 snapshot한다. Provider 호출은 commit
    뒤 DB transaction 밖이다. result transaction은 Payment, 성공 allocation과 durable Loyalty work를
    원자적으로 저장한다. Loyalty worker는 별도 local transaction에서 PointAccount, 원 allocation/Lot을
    정렬 잠금하고 원장·Lot·Account를 함께 commit한다. 주문 종료 caller용 lock API는
    `Order→Payment→정렬된 allocation` 순서를 보존한다.
  - 검토 대안: Refund 시 current price/policy 재계산은 과거 재현성을 깨므로 제외한다. Payment 성공
    transaction에서 Loyalty Entity를 직접 변경하는 방식은 Context/local transaction 경계를 침범해
    제외한다. 실패 시 inline 재시도만 하는 방식은 Provider 성공 뒤 Loyalty DB 실패를 잃으므로 제외하고
    source-aware durable retry/manual-review work를 사용한다.
  - 실패 가능성: policy/allocation snapshot 누락, replay payload conflict, 승인/line/원 point allocation
    upper-bound 경쟁, Provider unknown, Payment result 저장 실패, Loyalty source conflict/DB 실패와 ack 실패를
    각각 rollback, explicit Refund state 또는 durable retry/manual-review로 남긴다. 0, success, cache/fake로
    대체하지 않는다.
  - 테스트 계획: 실제 PostgreSQL Testcontainers에서 full/multiple partial, replay/conflict, line rounding,
    concurrent upper-bound, policy snapshot 전후, 만료 -1ns/at/+1ns, PLATFORM/BRAND/STORE lineage, coupon
    non-restoration, 원 Lot/보상 Lot, Loyalty failure retry/manual-review, remaining lock/read와 V15의
    CHECK/UNIQUE/FK/SQL을 검증한다. 지정 focused/Modulith/clean build/docs/diff 명령을 모두 실행한다.
  - migration/legacy 계획: `main`/`origin/main` `257dde6`에서 branch를 만든 직후 마지막 Flyway `V14`를
    확인해 유일 migration-writer lease의 다음 번호 `V15`를 선택했다. 기존 migration, checksum과 번호
    reservation을 바꾸지 않는다. release evidence의 clean/empty 전제를 재검증하고, existing Refund가
    새 immutable line/point source로 안전하게 매핑되지 않으면 금액이나 line을 추측하지 않고 fail-closed
    precheck 또는 명시적 legacy exclusion constraint로 처리한다.
- [x] 2026-08-01 allocation schema
  - V15가 immutable line/point request와 success allocation, durable restoration work,
    Loyalty restoration ledger를 만들었다. deferred constraint trigger가 request/success tie-out,
    unit overlap, OrderLine quantity/cash/point/coupon 상한, 원 PointReservation allocation 상한을
    commit 시점에 검증한다. line 출처를 재현할 수 없는 legacy Refund가 있으면 migration은
    추측 backfill 대신 fail-closed한다.
- [x] 2026-08-01 partial refund flow
  - Ordering coordinator가 full 또는 명시 line quantity를 conceptual 앞 unit부터 결정적으로
    배분하고, actor+Idempotency-Key advisory serialization과 immutable payload hash를 사용한다.
    Payment owner API는 request snapshot을 저장하고 Provider claim/request/lookup/result를 서로
    다른 transaction으로 처리한다. cash 0은 Provider 없이 성공하며 coupon owner state는 바꾸지 않는다.
- [x] 2026-08-01 restoration and owner boundary
  - 성공 cash result와 allocation/work는 한 Payment transaction에서 commit된다. Ordering worker는
    Payment work를 claim한 뒤 transaction 밖에서 Loyalty API를 호출하고 별도 Payment transaction으로
    ack/failure를 기록한다. Loyalty는 원 Lot이 `refundSucceededAt < expiresAt`일 때만 원 Lot에 복원하고,
    만료 Lot은 snapshot mode에 따라 동일 issuer lineage의 보상 Lot 또는 명시적 preserve disposition으로
    처리한다. 다섯 번째 실패는 `MANUAL_REVIEW`다.
- [x] 2026-08-01 validation evidence
  - `./gradlew test --tests '*Refund*' --tests '*Allocation*' --tests '*Restoration*' --rerun-tasks`:
    PASS, 24 tests.
  - `./gradlew test --tests '*ModularityTests' --rerun-tasks`: PASS, 1 test.
  - `./gradlew clean build -Pkotlin.incremental=false`: PASS, 157 tests와 Spotless/check 전체.
  - final build 전 `spotlessApply`와 focused test를 한 invocation으로 실행한 검증은 Kotlin incremental
    cache가 방금 생성한 main class file을 찾지 못해 `compileTestKotlin`에서 실패했다. assertion/test
    failure는 아니었고, `clean` non-incremental focused run과 위 final clean build를 각각 재실행해 통과했다.
  - `bash scripts/verify-docs.sh`: PASS.
  - `git diff --check`: PASS.

## Surprises & Discoveries

- 2026-08-01: target OpenAPI `RefundLineRequest.quantity`는 한 OrderLine의 일부 수량을 허용하지만
  BR-12/ADR-014는 OrderLine 총액까지의 allocation/remainder 순서만 정한다. 같은 line의 일부 수량을
  여러 Refund로 나눌 때 unit별 cash/points/coupon remainder를 어느 요청에 귀속할지는 미정이며, 선택에
  따라 실제 환불액이 달라진다. 금전 정책이므로 구현 전에 Business Policy/ADR 결정을 기록해야 한다.
- 2026-08-01: current V10 Refund는 positive cash 전용 단일 `requested_amount_krw`, 전체 attempt count와
  rejection 전용 source만 가진다. ADR-037/038의 독립 REQUEST/LOOKUP 예산, `RETRY_SCHEDULED`, immutable
  point policy/request snapshots와 line 성공 원장은 V15에서 forward migration해야 한다.
- 2026-08-01: current Loyalty reservation은 원 PointLot allocation을 보존하지만 OrderLine과 Lot의 교차
  attribution은 저장하지 않는다. Plan 12는 immutable line point totals와 ordered original allocation으로
  결정적인 typed snapshot을 만들고 성공 slice source를 DB 제약으로 보호해야 한다.
- 2026-08-01: Payment 내부에서 Ordering/Loyalty를 직접 조정하면 기존 `Ordering → Payment`와 순환하고
  Payment의 Modulith allowlist를 위반했다. 교차 Context 조정과 전역 Order-first lock 순서는 Ordering으로
  옮기고 Payment에는 Refund 원장·Provider claim/result·restoration work typed API만 남겨 구조 검증을
  통과시켰다.
- 2026-08-01: Loyalty replay ledger가 policy version/mode와 slice 금액만 보존하면 정상 worker 경로 밖의
  typed API 호출에서 validity days 또는 issuer snapshot 변경을 독립적으로 거부했음을 증명할 수 없다.
  V15 restoration row에 issuer type/reference와 policy validity days를 추가하고 두 changed-payload conflict
  회귀를 보강했다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | allocation/restoration을 recovery·event publication과 분리 | Plan 15 의존 없이 부분 환불의 Payment/Loyalty 불변식을 검증 | ADR-063, ADR-065, ADR-068 |
| 2026-08-01 | Accepted | 같은 OrderLine의 부분 수량은 coupon→points→cash 순서로 conceptual unit에 배분하고 성공 Refund가 앞 unit부터 소비 | 반복 분할에서도 unit/line tie-out과 replay 결정성을 유지 | BR-12, ADR-014 |

## Outcomes & Retrospective

완료됐다. V15는 기존 migration을 재작성하지 않고 immutable line/point request, 성공 allocation,
restoration work와 Loyalty restoration 원장을 추가한다. legacy Refund는 line 출처를 안전하게 증명할 수
없으므로 존재 시 migration을 실패시킨다. 성공 allocation은 conceptual 앞 unit, 원 point allocation,
OrderLine/Payment 상한을 PostgreSQL deferred constraints와 owner transaction에서 이중 보호한다.

Provider 호출은 Payment claim/result transaction 밖이고, Loyalty 복원도 Payment transaction 밖의 별도
owner transaction이다. 실패는 exact replay, `RETRY_SCHEDULED`, `UNKNOWN`/`RECONCILING` 또는
restoration `MANUAL_REVIEW`로 남으며 0·성공·fake fallback으로 대체되지 않는다. 부분 환불은 coupon
attribution만 기록하고 CouponIssuance/Reservation을 복원하지 않는다.

PostgreSQL/MockMvc tests는 full·연속 partial·앞 unit rounding·실패 unit 재사용·동시 unresolved 차단,
payload conflict/exact response replay, approved/line/source upper bound, policy snapshot, issuer-preserving
만료 보상, `expiresAt` -1ns/at/+1ns, Loyalty retry/manual review와 V15 empty/legacy migration을 검증했다.
Plan 13의 유일 direct dependency가 충족되어 `Implementation-Ready=true`로 전환했다. Plan 16은 Plan 13과
Plan 15가 아직 active라 not-ready를 유지한다. 새 production dependency와 Plan 16 소유 public event
publication은 추가하지 않았다.

## Revision Notes

- 2026-08-01: 기존 Plan 10의 partial-refund slice를 분리했다.
- 2026-08-01: Plan 10 completion evidence를 반영해 stale issuer dependency 서술을 제거하고
  implementation-ready 상태를 재확인했다.
- 2026-08-01: V15, partial Refund/Loyalty vertical slice와 검증 결과를 완료 outcome으로 기록하고
  completed path로 이동했다.
