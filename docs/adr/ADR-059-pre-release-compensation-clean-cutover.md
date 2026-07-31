# ADR-059: OrderCompensation의 pre-release clean cutover

- **Status:** Accepted
- **Date:** 2026-07-31
- **Amends:** ADR-033의 forward rename/backfill migration 전략

## Context

ADR-033은 거절 전용 `RejectionCompensationCase/Step`을
`OrderCompensationCase/Step`으로 일반화하면서 기존 table rename과
`trigger = STORE_REJECTION` backfill을 forward migration으로 수행하도록 정했다.
이후 event·정책 설계에서 `OrderRejectedV1`과 관련 schema가 production 발행 전이고
보존 publication·외부 consumer가 없다는 pre-release 전제가 제안됐다. 이 전제는
저장소 문구가 아니라 아래 release gate의 외부 운영 증거로 확인해야 한다.

현재 production 배포와 보존해야 할 실사용 Case·step·publication이 없다면 legacy
shape를 이행하는 migration과 불완전 row 복구 scanner는 존재하지 않는 운영 상태를
지원하기 위한 영구 복잡도가 된다.

## Decision

- OrderCompensation 일반화는 **pre-release clean cutover**로 수행한다.
- 기존 거절 전용 schema, producer, consumer, fixture와 test를 한 변경에서 새
  OrderCompensation 계약으로 갱신한다.
- pre-release migration source는 처음부터 다음 최종 shape를 생성하도록 수정한다.
  - `operations_order_compensation_case`
  - `operations_order_compensation_step`
  - `trigger = STORE_REJECTION | CUSTOMER_CANCELLATION`
  - Case당 COUPON·POINTS policy snapshot child row
  - ADR-033 이후 확정된 step/source/constraint
- `ALTER TABLE ... RENAME`, legacy table copy와 기존 row trigger backfill migration을
  추가하지 않는다.
- 개발·테스트 database는 새 migration history로 재생성한다. 이미 적용된 local
  checksum을 우회하거나 repair해서 혼합 schema를 유지하지 않는다.
- 기존 Case·step·event publication을 backfill, 추정 또는 자동 보완하지 않는다.
- `LEGACY_COMPENSATION_INCOMPLETE` ReprocessingCase와 legacy integrity scanner를
  도입하지 않는다.
- 구현·배포 직전 release gate가 다음을 확인하고 결과를 release evidence로 남긴다.
  - production database/table/row가 없음
  - `OrderRejectedV1`, `OrderCancelledV1` 완료·미완료 publication 0건
  - 외부 consumer와 별도 배포 consumer 0개
  - rollback 대상 production binary/data가 없음
- 하나라도 0이 아니거나 존재가 불명확하면 clean cutover 배포를 중단한다. 그 경우
  기존 데이터를 변경하지 않고 forward migration, publication drain, compatibility와
  rollback을 다루는 새 ADR/ExecPlan을 먼저 만든다.
- 최초 production data/publication이 생성된 뒤에는 migration history와 V1 event
  계약을 동결하고 이후 변경부터 forward-only migration과 ADR-034 호환성 정책을
  적용한다.
- 이 결정은 대상 ADR의 **migration 실행 방식만** 대체하고 schema 최종 shape, 제약,
  trigger, step, owner source와 API 계약은 그대로 유지한다.

### Scope of the replaced migration mechanics (2026-08-01)

release gate가 PASSED인 동안 다음 pre-release migration 서술은 모두 "적용 대상 row
0"이라는 같은 사실 위에 있으므로 legacy 이행이 아니라 최종 shape 직접 생성으로
수행한다. 각 ADR이 정의한 컬럼, CHECK, 존재 조건과 실패 규칙은 변하지 않는다.

| ADR | 서술된 legacy 작업 | Clean-cutover 경로의 실행 |
|---|---|---|
| ADR-029 | 기존 `CANCELLED` row의 `cancelled_at`/`cancellation_cause` backfill | 대상 row 0. migration source가 네 컬럼과 세 CHECK를 처음부터 만든다 |
| ADR-033 | `rejection_compensation` table rename과 `trigger` backfill | 최종 `operations_order_compensation_*` shape를 직접 생성한다 |
| ADR-040 | `RELEASED_BY_REJECTION` → `RELEASED_AFTER_TERMINATION` rename과 trigger backfill | 대상 row 0. V9 대체 migration이 최종 상태 enum과 CHECK를 직접 만든다 |
| ADR-042 | 기존 `STORE_REJECTION` 복원 row의 trigger·policy version backfill과 precheck 실패 | 대상 row 0. precheck는 실행되지만 후보 row가 없어 통과한다 |

- **ExecPlan ownership clarification (2026-08-01):** 이 표는 migration 실행 방식만
  열거하며 하위 ExecPlan의 소유 범위를 뜻하지 않는다. ADR-033/040/042 schema와
  precheck는 `30-order-compensation-foundation`이 소유한다. ADR-029의 Order 취소
  네 컬럼·세 CHECK와 precheck는 해당 필드를 Domain/JPA command에 연결하는
  `40-command`가 단독 소유한다. Plan 30은 ADR-029 migration을 생성하거나 완료 조건으로
  요구하지 않는다.

- 각 ADR의 backfill 규칙과 precheck 실패 조건은 삭제하지 않는다. gate가 nonzero
  또는 unknown이 되면 그 서술이 그대로 forward-migration 경로의 계약이 된다.
- clean-cutover 경로에서도 precheck 자체는 구현하고 실행한다. "row가 없어야 한다"는
  전제를 migration이 스스로 확인하지 않으면 gate 무효화를 배포 시점에 감지할 수
  없다. 후보 row가 하나라도 발견되면 migration은 조용히 통과하지 않고 실패한다.
- 개발자 local DB에 이전 shape의 row가 남아 있으면 checksum repair나 수동 backfill로
  섞지 않고 database를 재생성한다.

### 2026-07-31 readiness audit clarification

- 저장소, commit `04e2b4819a66966952c5436342a05149fd7ac6ee`, 병합 PR #17에는
  production DB/table/row, 완료·미완료 publication, 외부 consumer, rollback binary와
  적용 환경을 입증하는 release evidence가 없다.
- 확인할 수 없는 항목은 0으로 간주하지 않으므로 해당 감사 시점의 결과는
  `CLEAN_CUTOVER_GATE = FAILED`다.
- 기존 V8 migration과 V1 event 계약을 수정하지 않는다. 구현 전 외부 운영 사실을
  검증해 전부 0임을 입증하거나, forward migration·publication drain·compatibility·
  rollback을 다루는 새 Accepted ADR/ExecPlan을 먼저 만든다.

### 2026-07-31 operational-state evidence update

- product owner의 운영 상태 확인에 따라 현재 local/test 밖의 shared, staging 또는
  production 환경이 없다.
- production/shared database와 compensation schema·row, 완료·미완료
  `OrderRejectedV1`/`OrderCancelledV1` publication, 외부·독립 consumer, rollback 대상
  binary/data와 적용 migration이 모두 명시적 0으로 확인됐다.
- 확인 시점, 범위, 항목별 결과와 무효화 조건은
  [release-gate evidence](../quality/customer-order-cancellation-release-evidence.md)에
  기록한다.
- 따라서 현재 `CLEAN_CUTOVER_GATE = PASSED`이며 이 ADR의 pre-release clean-cutover
  경로를 사용할 수 있다. 구현·배포 직전 inventory 재확인은 계속 필요하다.

## Alternatives Considered

### Forward rename과 backfill

- production data가 있어도 이행할 수 있다.
- 현재 존재하지 않는 legacy data와 rollback binary를 영구 지원하는 migration,
  mapping과 테스트가 추가된다.

### Legacy 불완전 scanner 추가

- 과거 일부 row가 있다면 탐지할 수 있다.
- 실사용 legacy row가 없다는 전제에서 불필요한 worker, case type과 운영 runbook이
  남는다.

### 기존 schema 명칭 유지

- migration 변경이 작다.
- 고객 취소까지 rejection 명칭으로 저장되어 ubiquitous language가 어긋난다.

## Rationale

호환 대상이 실제로 없을 때 clean cutover는 최종 schema만 테스트하게 해 이행 분기와
숨은 fallback을 줄인다. 다만 pre-release 전제는 추측이 아니라 release gate의
증거로 확인하고, 전제가 깨지면 배포를 중단해야 안전하다.

## Consequences

- 개발자는 local/test DB를 재생성해야 한다.
- pre-release migration checksum은 바뀌며 기존 local DB를 그대로 재사용할 수 없다.
- legacy migration·scanner·ReprocessingCase 구현 범위가 제거된다.
- 최초 production 배포가 migration/event 동결 경계가 된다.

## Failure Scenarios

- production row가 있는데 clean cutover migration을 적용하면 data가 유실되거나
  startup checksum이 실패한다.
- local DB를 repair해 구/신 schema를 섞으면 테스트와 새 설치 결과가 달라진다.
- publication 존재를 보지 않고 listener/type을 바꾸면 미완료 보상이 처리되지 않는다.
- “pre-release”를 문서 주장만으로 판단하고 release evidence를 남기지 않으면 실제
  외부 consumer를 놓칠 수 있다.

## Verification

- empty database의 전체 migration과 최종 schema
- code/fixture/test의 rejection 전용 table/type 참조 0건
- clean checkout에서 test DB 재생성
- release gate의 DB/publication/consumer 0 증거
- nonzero fixture에서 release gate 실패

## Required Tests

- empty PostgreSQL full migration
- 새 schema CHECK, FK, unique와 trigger 두 값
- store rejection/customer cancellation 통합 test
- 구 table/type 이름 정적 검색
- legacy table이 있는 DB에 clean cutover 자동 적용 금지
- completed/incomplete V1 publication 각각 release gate 차단
- 외부 consumer inventory nonempty 차단
- ADR-029/033/040/042 각 migration의 precheck가 후보 row 0에서 통과하고 row가
  주입되면 실패함
- 이전 shape row가 남은 DB에서 clean-cutover migration이 통과하지 않음

## Metrics

pre-release cutover 자체에는 runtime metric을 추가하지 않는다.

- **Not measured:** legacy migration 대비 구현량 감소

## Revisit Conditions

release gate가 기존 data/publication/consumer를 하나라도 발견할 때 이 결정을 적용하지
않고 forward migration ADR로 대체한다.

## Related Decisions

- BR-06, BR-14
- [ADR-010](ADR-010-initial-event-publication.md)
- [ADR-029](ADR-029-customer-cancellation-scope.md)
- [ADR-033](ADR-033-order-compensation-case-generalization.md)
- [ADR-034](ADR-034-customer-cancellation-event-contract.md)
- [ADR-040](ADR-040-order-termination-resource-release.md)
- [ADR-041](ADR-041-trigger-and-benefit-scoped-restoration-policy.md)
- [ADR-042](ADR-042-benefit-restoration-ledger-metadata.md)
