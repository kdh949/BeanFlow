# 환불 적립 포인트 회수 foundation을 만든다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-12-partial-refund-allocation-and-restoration.md`, `docs/exec-plans/completed/ordinary-point-accrual-policy-management.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

성공 Refund에 대응하는 실제 차감은 `RECOVERY` transaction으로 남기고, 부족액만
PointRecoveryPending으로 보존하여 이후 적립에서 oldest-first로 정확히 한 번 상계한다.

## Current State

- `RECOVERY` enum/type CHECK와 PointRecoveryPending persistence가 없다.
- 환불 적립 포인트 회수와 이후 적립 상계의 durable owner flow가 없다.
- completed ordinary-accrual policy plan이 Operations policy, verified bootstrap, legacy activation,
  Order 생성 immutable snapshot과 typed Ordering boundary를 V16으로 구현했다.
- 현재 부분 환불은 `PAID`부터 `COMPLETED`까지 가능하고 Provider success는 비동기다.
  Refund 성공이 완료 전이면 future accrual exclusion만 남겨야 하며, 완료 후이면 recovery/pending이
  필요하다. BR-10은 `refundSucceededAt <= completedAt`에 Refund 우선 exclusion과 Payment-owned
  eligibility work를 확정했다.

## Definitions

- **RECOVERY:** 실제 Lot/Account 가용 잔액을 줄인 append-only debit.
- **PointRecoveryPending:** 아직 회수하지 못한 양수 잔액의 Loyalty Aggregate.

## Scope

### In Scope

- recovery/pending schema, Account summary tie-out, refund source consumer
- Payment-owned refund eligibility work와 completion/refund timing source
- future accrual의 oldest-first offset과 source conflict/retry semantics

### Non-goals

- partial-refund allocation/restoration, point-account HTTP read, ADR-068 public producer publication

## Business Rules and Invariants

- available Account/Lot은 음수가 될 수 없다.
- PENDING은 positive remaining, SETTLED는 zero remaining이다.
- actual debit과 uncollected pending은 서로 대체하지 않는다.
- 일반 적립의 gross amount, issuer/expiry input과 unit allocation은 Order 생성 snapshot과
  tie-out하며 완료/환불 시 live policy로 재계산하지 않는다.
- predecessor의 `LEGACY_NOT_APPLICABLE`은 terminal no-accrual/no-recovery이고 `SNAPSHOTTED` source만
  complete snapshot으로 처리한다. missing source/snapshot을 legacy나 0원으로 추측하지 않는다.
- 완료 전 성공 Refund unit의 future-accrual exclusion은 완료 후 성공 Refund의
  `RECOVERY`/pending과 서로 대체하지 않는다.

## Architecture and Transaction Boundaries

predecessor가 구현한 typed Ordering boundary는 immutable source/snapshot만 반환한다. Loyalty는
Account 뒤 Lot/pending 정렬 lock으로 transaction을 수행한다. Refund source consumer와
later `OrderCompletedV1` accrual은 각각 자기 local transaction에 summary, ledger, pending state를
함께 저장하고, typed boundary로 받은 snapshot의 completion source/version/hash를 검증한다.
Refund success consumer는 request-time Order 상태를 사용하지 않고 immutable refund/completion timing
fact가 확정될 때까지 Payment-owned eligibility work를 durable하게 보존한다.

## Alternatives Considered

- 부족액을 RECOVERY row 하나로 기록: 실제 debit과 미회수 의무를 섞으므로 제외한다.
- 완료 시 현재 적립 정책을 읽음: 과거 결과를 변경하고 ADR-068을 위반하므로 제외한다.
- Plan 20의 `OrderCompletedV2`까지 적립을 지연: Plan 13의 later-accrual milestone과
  dependency cycle을 만들므로 제외한다.

## Failure Semantics

source conflict, summary mismatch, snapshot 누락/불일치, timing fact 누락/불일치, typed boundary와
DB failure는 rollback/retry/manual-review이며 0 recovery·0 accrual·negative balance·live-policy fallback이
아니다.

## Data and Migration

PointAccount pending summary, PointRecoveryPending, PointTransaction `ACCRUAL`/`RECOVERY` type,
Payment-owned refund eligibility work와 source constraints를 하나의
forward-only migration writer lane에서 구현한다. 실제 migration 번호는 branch/lease 획득 뒤 최신
main 기준으로 정한다.

## API and Event Contracts

public signed transaction amount와 `recoveryPendingKrw` projection semantics는 ADR-065를 따른다.
`OrderCompletedV1` payload는 바꾸지 않고 ADR-073 typed boundary를 사용한다.
`PointsAccruedV1` publication은 Plan 16에서 활성화한다.

## Milestones

1. Payment-owned refund eligibility work/completion fact와 recovery/pending schema constraints를
   하나의 V17 forward migration에 구현한다.
2. Ordering coordinator가 immutable snapshot/timing source를 검증하고 Payment work를 claim해
   Loyalty recovery owner transaction으로 전달하는 flow를 구현한다.
3. frozen `OrderCompletedV1` trigger에서 완료 전 Refund unit을 제외하고 gross `ACCRUAL` 뒤
   oldest-first pending `RECOVERY` offset을 같은 Loyalty transaction에 구현한다.
4. source/version/hash replay, concurrent Refund, out-of-order completion/refund와 rollback/retry/manual-review
   검증을 완료한다.

## Required Tests

- full/partial recovery, residual pending and summary tie-out
- replay/conflict/concurrent refunds, non-negative balances
- frozen V1 replay, snapshot source/version/hash conflict, missing snapshot/boundary failure
- pre-completion/after-completion/concurrent Refund success timing, exclusion/recovery branch와
  out-of-order delivery
- gross accrual then oldest-first recovery, PENDING→SETTLED, rollback/retry

## Validation Commands

```bash
./gradlew test --tests '*PointRecovery*' --tests '*PointTransaction*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

recovery outcome/pending state metrics use closed vocabulary only.

## Documentation Updates

BR-10 parameter amendment, ADR-011/065/068/073, point ledger documentation과 Plan 16/40 successor
evidence를 갱신한다.
일반 적립 policy와 Ordering snapshot 선행 범위는
[`ordinary-point-accrual-policy-management` spec](../../specs/ordinary-point-accrual-policy-management.md)을
따르는 [completed ExecPlan](../completed/ordinary-point-accrual-policy-management.md)의 typed outcome을 소비한다.

## Progress

- [x] ordinary-accrual policy/snapshot predecessor completion — V16, typed read와 193-test full build
- [x] 2026-08-02 implementation preflight and migration-writer lease
  - latest `origin/main`과 local `main`은 `0d28acf`로 일치하고 Plan 12와 ordinary-accrual
    predecessor merge를 모두 포함한다. 현재 branch는 이 commit에서 만든
    `feature/refund-earned-point-recovery`다.
  - Codex task/worktree inventory에 다른 active BeanFlow schema-writing task가 없고 남아 있는
    worktree는 completed Plan 10/11과 signed-cursor branch뿐이다. Plan 13이 ADR-072의 유일한
    migration-writer lease holder이며 최신 Flyway `V16` 다음 `V17`을 사용한다.
  - Payment는 Refund 성공과 immutable timing eligibility work/completion fact를 소유하고,
    Ordering coordinator는 기존 `Ordering -> Payment/Loyalty` 방향으로 owner API만 호출한다.
    Loyalty는 Account를 먼저, Lot과 pending을 정렬 잠금해 actual recovery 또는 accrual/offset을
    각각 하나의 local transaction에 commit한다.
  - `OrderPointAccrualSnapshotOperations`의 complete `SNAPSHOTTED` source만 금액·issuer·expiry·unit
    원천으로 사용한다. source/schema/hash conflict, unresolved Refund, DB/owner ack 실패는
    0·legacy·live policy fallback이 아니라 rollback, retry 또는 manual review다.
  - 실제 PostgreSQL migration/owner/concurrency tests, application event replay, Modulith와 full build,
    docs verifier와 diff check를 milestone checkpoint마다 실행한다.
- [x] 2026-08-02 later-accrual ledger representation clarification
  - BR-13/ADR-065의 gross `ACCRUAL` PointLot/transaction을 먼저 기록한 뒤 같은 transaction에서
    pending 상계분을 그 Lot의 `RECOVERY`로 debit하는 표현을 유지한다. commit 뒤 available에는
    net만 남지만 append-only ledger에는 gross와 offset이 모두 남는다.
- [x] 2026-08-02 recovery/pending schema and constraints
  - V17은 `payment_order_point_accrual_outcome`과 durable refund recovery work를 만들고
    Loyalty Account pending summary, `PointRecoveryPending`, recovery/accrual result receipt,
    `ACCRUAL|RECOVERY` transaction과 pending FK를 추가했다.
  - deferred constraint trigger가 Account pending summary와 PENDING row 합을 commit 시점에
    검증하고 pending identity/remaining/state는 단조롭게만 바뀐다. outcome과 result receipt는
    DB trigger로 append-only다.
  - verified pre-release clean activation과 달리 이미 `SNAPSHOTTED` completion 또는 성공 Refund가
    있으면 V17은 owner source를 추측 backfill하지 않고 migration을 실패시킨다.
  - `./gradlew test --tests '*PointRecoveryMigrationTest' --rerun-tasks`: PASS, 3 PostgreSQL tests.
- [x] 2026-08-01 BR-10 ordinary-accrual policy vocabulary decision
- [x] 2026-08-01 GLOBAL default + STORE override precedence decision
- [x] 2026-08-01 append-only INHERIT_GLOBAL override lifecycle decision
- [x] 2026-08-01 verified offline initial GLOBAL policy bootstrap decision
- [x] 2026-08-01 legacy Order forward-only activation decision
- [x] 2026-08-01 BR-10 completion/refund timing decision
- [ ] refund recovery
- [ ] later-accrual offset
- [ ] validation evidence

## Surprises & Discoveries

- 2026-08-01: Plan 12 completed outcome은 successful Refund line/point allocation과 durable
  restoration source를 제공하며 public event publication은 의도적으로 Plan 16에 남겼다. Plan 13은
  event를 기다리지 않고 이 verified owner source를 typed boundary로 소비해야 한다.
- 2026-08-01: `OrderCompletedV1`은 financial payload가 없는 frozen contract이고 Plan 20의 V2는
  Plan 13 이후에만 가능하다. 당시에는 ADR-073으로 Order 생성 snapshot trigger boundary만 정했고
  BR-10의 ordinary-accrual parameter 값이 없어 readiness를 일시적으로 false로 되돌렸다. 이후의
  closed vocabulary 결정과 completed predecessor가 이 gate를 닫았다.
- 2026-08-01: 부분 환불은 `PAID`부터 `COMPLETED`까지 가능하다. request-time state만으로는
  Provider success가 accrual 전인지 후인지 알 수 없으므로, 완료 전 성공 unit의 exclusion과 완료 후
  recovery/pending을 가르는 immutable timing policy가 필요했다. BR-10은
  `refundSucceededAt <= completedAt`에 Refund 우선 exclusion을 확정했다.
- 2026-08-01: completed predecessor는 신규 Order마다 policy version/hash, selection source, gross와
  conceptual-unit allocation을 같은 transaction에 저장하고 missing/inconsistent source를 typed 503으로
  처리한다. Plan 13이 live policy 또는 pricing graph를 다시 조회할 이유가 없어졌다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-01 | Accepted | recovery를 allocation/restoration에서 분리 | debt lifecycle과 lock/tie-out 검증을 독립시킨다 | ADR-065 |
| 2026-08-01 | Accepted | Order-created immutable snapshot, frozen V1 trigger | live policy 없이 accrual/recovery를 재현하고 Plan 20 cycle을 피한다 | ADR-073 |
| 2026-08-01 | Accepted | Refund 우선 timing과 unit cash allocation | 완료 전 refund debt를 막고 replay·out-of-order에도 같은 target을 재현한다 | BR-10, ADR-073 |
| 2026-08-01 | Accepted | GLOBAL 기본 head와 STORE override 우선순위 | store exact-match와 하나의 명시적 fallback으로 future Order 정책을 결정한다 | BR-10, ADR-074 |
| 2026-08-01 | Accepted | STORE override의 INHERIT_GLOBAL 전환 | 과거 version을 삭제하지 않고 미래 Order만 당시 GLOBAL 정책으로 복귀시킨다 | BR-10, ADR-074 |
| 2026-08-01 | Accepted | 일반 적립 closed policy vocabulary | 운영자가 바꿀 수 있는 계산·issuer·만료 범위를 DB/API/snapshot에서 동일하게 제한한다 | BR-10, ADR-074 |
| 2026-08-01 | Accepted | verified offline 최초 GLOBAL bootstrap | 임의 migration seed와 초기화 전 HTTP 성공 없이 완전한 정책을 배포 gate로 만든다 | BR-10, ADR-069, ADR-074 |
| 2026-08-01 | Accepted | migration 이전 Order의 LEGACY_NOT_APPLICABLE marker | initial policy 소급 적용 없이 기존 결과와 신규 snapshot 의무를 구분한다 | BR-10, ADR-073, ADR-074 |
| 2026-08-02 | Accepted | 이후 적립은 gross ACCRUAL과 pending RECOVERY를 모두 기록하고 available만 net으로 남김 | 감사 가능한 gross credit과 실제 offset debit을 분리하고 PointLot 연결·signed ledger tie-out을 유지한다 | BR-13, ADR-065 |

## Outcomes & Retrospective

recovery/pending 자체는 미구현 상태다. 그러나 Plan 12의 successful Refund unit allocation과 V16의
completed `OrderPointAccrualSnapshotOperations`가 모두 actual outcome과 PostgreSQL/full-build evidence를
남겼다. 일반 적립률·반올림·issuer·만료, legacy 처리와 refund/completion timing 결정도 닫혀 있어
`Implementation-Ready=true`다. 구현 branch는 predecessor가 main에 병합된 뒤 최신 main에서 ADR-072
migration-writer lease를 새로 확인하고 시작한다.

## Revision Notes

- 2026-08-01: 기존 Plan 10의 earned-point recovery slice를 분리했다.
- 2026-08-01: Plan 12 completion path/outcome을 반영해 direct dependency를 충족하고
  implementation-ready로 전환했다.
- 2026-08-01: ADR-073 trigger-only snapshot boundary와 BR-10 timing/unit allocation을 반영하고,
  미결정 rate/rounding·issuer·expiry gate로 implementation-ready를 false로 유지했다.
- 2026-08-01: 일반 적립 policy를 필수 GLOBAL 기본값과 선택적 STORE override로 관리하기로 했고,
  override는 append-only `INHERIT_GLOBAL` 전환으로 복귀한다. 초기 version·rounding/expiry
  vocabulary가 남아 readiness를 false로 유지했다.
- 2026-08-01: bps 0..10000, FLOOR/HALF_UP, completion 기준 exact/서울 달력일 expiry와 literal
  issuer vocabulary를 확정했다. 최초 GLOBAL version 생성 경로만 readiness gate로 남겼다.
- 2026-08-01: 최초 GLOBAL policy는 verified-release-principal offline command로 생성하고 정상
  server는 그 head가 없으면 시작 실패하기로 했다. 남은 gate는 spec/API/task review다.
- 2026-08-01: policy/bootstrap/Ordering snapshot ownership을 별도 predecessor ExecPlan으로 분리하고,
  이 plan은 completed typed boundary를 소비하는 Loyalty/Payment recovery scope로 되돌렸다.
- 2026-08-01: predecessor의 V16/API/bootstrap/Order atomic snapshot과 193-test full build가 완료되어
  dependency를 completed path로 바꾸고 implementation-ready로 전환했다.
- 2026-08-02: latest main과 migration lease를 확인하고 milestone을 Payment eligibility work,
  Loyalty recovery와 completion accrual offset의 실제 owner 순서로 구체화했다. later-accrual은
  ADR-065에 따라 gross credit과 offset debit을 모두 원장에 남기는 것으로 명확히 했다.
