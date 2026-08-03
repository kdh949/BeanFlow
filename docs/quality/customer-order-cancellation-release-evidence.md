# Customer Order Cancellation Release-Gate Evidence

## Evidence identity

- **Recorded at:** 2026-07-31T23:40:15+09:00
- **Evidence source:** product-owner operational-state attestation
- **Attestor role:** product owner
- **Scope:** BeanFlow의 모든 non-local deployment, shared/production database,
  persistent event publication, external consumer와 rollback artifact
- **Collection method:** 대상 외부 환경과 artifact가 존재하지 않는다는 소유자 확인
- **Related decision:** [ADR-059](../adr/ADR-059-pre-release-compensation-clean-cutover.md)

원본 대화는 저장하지 않는다. 이 문서는 확인된 운영 사실, gate 계산과 재검증 조건만
기록한다.

## Environment inventory

2026-07-31 확인 시점에 local/test 밖의 shared, staging 또는 production 환경이 없다.
따라서 조회할 외부 database, publication registry, 독립 배포 consumer 또는 rollback
artifact repository도 없다. 저장소의 migration과 test fixture는 외부 운영 상태로
계산하지 않는다.

## Gate facts

| Required fact | Confirmed result | Evidence interpretation |
|---|---:|---|
| shared/production deployment environment | 0 | non-local 배포 환경 없음 |
| production/shared compensation schema, table와 row | 0 | 대상 database 자체가 없음 |
| completed `OrderRejectedV1`/`OrderCancelledV1` publication | 0 | 외부 publication registry 없음 |
| incomplete `OrderRejectedV1`/`OrderCancelledV1` publication | 0 | 외부 publication registry 없음 |
| external 또는 independently deployed consumer | 0 | 독립 consumer 배포 없음 |
| rollback 대상 production binary/data | 0 | production 배포·data 없음 |
| production/shared 환경에 적용된 migration | 0 | 적용 대상 환경 없음 |

모든 ADR-059 gate 항목이 unknown이 아니라 명시적 0으로 확인됐다.

```text
CLEAN_CUTOVER_GATE = PASSED
```

## Authorized path

- ADR-059의 pre-release clean-cutover 경로를 사용할 수 있다.
- producer, consumer와 fixture를 같은 변경에서 최종 계약으로 전환한다.
- legacy migration, publication drain, compatibility layer와 version 이중 발행을 만들지
  않는다.
- local/test database는 최종 migration history로 재생성하며 checksum repair로 구·신
  schema를 혼합하지 않는다.

## Validity and invalidation

이 증거는 위 기록 시점의 point-in-time 확인이다. compensation schema 변경 또는 최초
production/shared 배포 직전에 같은 inventory를 다시 확인해야 한다. 다음 중 하나가
생기거나 존재 여부가 unknown이 되면 이 PASS는 즉시 무효다.

- shared/staging/production 환경 또는 database
- 보존해야 할 compensation row나 완료·미완료 V1 publication
- external/independent consumer
- rollback 대상 binary 또는 data

무효가 되면 clean cutover를 중단하고 실제 상태를 입력으로 forward migration,
publication drain, compatibility와 rollback ADR/ExecPlan을 먼저 확정한다.

이 PASS는 migration/event 전략만 허용한다. 부분 환불 allocation, Settlement, 공통
compensation foundation과 고객 취소 command 구현 완료를 그 자체로 의미하지 않는다.

## Plan 30 pre-implementation gate revalidation

- **Recorded at:** 2026-08-03
- **Repository baseline:** local `main`과 `origin/main`이 `52e4320`으로 일치했고 completed
  Plan 11 outcome `59bd6c2`, Plan 20 outcome `3fea6ea`가 모두 ancestor였다.
- **Migration writer:** 다른 worktree/branch의 active Plan 30 migration writer가 없었고
  ADR-072 단일 writer lane을 `feature/order-compensation-foundation`이 획득했다.
- **Deployment inventory:** GitHub deployment와 environment가 모두 0이고 non-local runtime
  DB 설정도 없었다. 외부 consumer, rollback artifact, completed/incomplete/default-listener
  termination V1 publication도 0이었다.
- **Result:** 모든 항목이 nonzero/unknown이 아닌 explicit 0이므로
  `CLEAN_CUTOVER_GATE = PASSED`를 유지했다.

이 재검증은 해당 시점의 release strategy evidence다. 최초 non-local 배포 직전에는 같은
inventory를 다시 확인하며 하나라도 생기면 V8/V9/V22 clean cutover를 적용하지 않는다.

## Plan 30 implementation evidence

- V8은 legacy rejection Case/step 후보를 먼저 세고 0일 때만 공통 Case, trigger, 두 benefit
  child와 여섯 step의 최종 shape를 만든다.
- V9는 legacy `RELEASED_BY_REJECTION` 후보를 먼저 세고 0일 때만 Pickup·Stock의 공통
  termination state와 trigger/source CHECK를 만든다.
- V22는 legacy Coupon/Point 종료 복원 후보를 먼저 세고 0일 때만 owner metadata,
  compensation coupon terms와 allocation-aware point restoration shape를 만든다.
- PostgreSQL migration fixture는 empty full migration, 각 migration별 legacy row 주입 실패,
  최종 CHECK/FK/UNIQUE/deferred cardinality를 검증한다.
- producer, annotation listener ID, 중앙 target registry와 실제 publication target은 열 개의
  Plan 30 mapping으로 일치한다. legacy/default listener shim, V1/V2 이중 발행과 guessed
  backfill은 없다.
- `OrderCancelledV1` DTO와 네 owner consumer foundation만 준비됐고 고객 취소 HTTP command,
  Refund 생성과 production success endpoint는 포함하지 않았다.

### Validation result (2026-08-03)

- `./gradlew test --tests '*Compensation*' --tests '*StoreOrder*'`: Passed, 21초.
- `./gradlew test --tests '*EventPublication*'`: Passed, 10초.
- `./gradlew test --tests '*ModularityTests'`: Passed, 2초.
- `./gradlew clean build`: Passed, 294 tests, failures/errors/skips 0, 1분 26초.
- `bash scripts/verify-docs.sh`: Passed, target 26/deployed 9 paths, 73 schemas,
  32 policies, 74 ADRs, 140 Markdown files와 24 ExecPlans.
- `git diff --check`: Passed.
- Not run: 없음.

## Plan 10 issuer provenance execution evidence

- **Recorded at:** 2026-08-01
- **Execution inventory:** this workspace had no `BEANFLOW_DB_URL`,
  `BEANFLOW_DB_USERNAME`, or `BEANFLOW_DB_PASSWORD`; no configured runtime database was
  available to reclassify. Repository schema, entities, repositories, and fixtures also
  contained no legacy PointLot issuer source.
- **Interpretation:** this is not evidence that a non-empty future database is mappable.
  V14 accepts the clean empty path, while any non-empty V1–V13 database must present the
  exact one-to-one external `loyalty_point_lot_issuer_precheck` relation with valid issuer
  type/reference, non-blank source reference, and verification timestamp. A missing,
  partial, extra, blank, invalid, or changing mapping fails the migration and prevents
  application activation; V14 never supplies a `PLATFORM` default.
- **Test evidence:** PostgreSQL Testcontainers covered empty final constraints, verified
  exact backfill, missing and invalid mappings, immutable issuer snapshots, DTO projection,
  compensation issuer inheritance, and Spring startup failure.

## Plan 15 settlement-input execution evidence

- **Recorded at:** 2026-08-02
- **Execution inventory:** this workspace had no configured non-local runtime database or
  deployment environment. No Merchant terms, active Campaign, CouponReservation or Order row
  outside repository Testcontainers could be inspected or reclassified. This preserves the earlier
  external-environment inventory of explicit zero; it is not evidence that an unknown future legacy
  database is safely mappable.
- **Migration interpretation:** V18 adds immutable versioned Merchant terms without inventing a
  fee for existing Stores. V19 stops when an active legacy Campaign or any legacy CouponReservation
  lacks verified burden lineage. V20 stops when any legacy Order exists because terms/coupon/point
  source cannot be reconstructed from price totals. Application activation therefore accepts the
  clean path and fails closed for unverified financial history; checksum repair or guessed backfill
  is not an allowed release action.
- **Runtime interpretation:** a Store without exactly one applicable terms version, an incomplete
  coupon burden snapshot, mismatched PointLot issuer allocation or monetary/hash tie-out failure
  returns `SETTLEMENT_INPUT_UNAVAILABLE` and rolls back Order plus all reservations. No local,
  in-memory, current-value or zero-cost fallback is active.
- **Test evidence:** PostgreSQL Testcontainers covered V18–V20 constraints and legacy gates,
  applicable/overlapping/concurrent terms, all coupon burden modes and integer remainder, mixed
  issuer allocation, source/hash/formula tie-outs, exactly-one replay and forced persistence rollback.
  `OrderCompletedV2` contract tests covered exact fixture mapping and Payment mismatch without adding
  a producer/outbox or Settlement consumer.

## Plan 40 and Plan 50 combined Draft execution evidence

- **Recorded at:** 2026-08-03
- **Repository baseline:** local `main`과 `origin/main`의 `5f52320`에서 Plan 40→50 단일 Draft
  stack을 시작했다. Plan 40 completed handoff `7a0d636` 뒤 Plan 50을 같은 branch와 shared
  migration-writer lease에서 계속했다.
- **Migration:** Plan 40은 V23, Plan 50은 V24 terminal notification source, V25 setup integrity,
  V26 two-person repair, V27 terminal Refund operator reconciliation을 forward migration으로 추가했다.
  적용된 migration 수정, checksum repair, guessed financial backfill은 없다.
- **Runtime:** Refund REQUEST는 allowlist 안에서 최초 포함 최대 3회, UNKNOWN 뒤 LOOKUP은 별도 최대
  5회다. 네 owner 수렴, 고객/운영자 projection, terminal notification, Settlement
  `NOT_APPLICABLE`, setup detector/scanner, LOOKUP-only two-person repair와 terminal Refund
  single-operator LOOKUP이 명시적 상태로 수렴한다. 직접 저장하는 financial target publication은
  최초 `FAILED`/attempt 0으로 남겨 실제 bounded recovery worker가 consumer를 호출한다.
- **Missing-Refund repair boundary:** 서로 다른 활성 operator 두 명, 30분 proposal TTL, 승인 시 원 snapshot과
  Refund 부재 재검증, 원 ID/source/provider key/amount의 정확한 복원만 허용한다. Provider request는
  보내지 않고 `RECONCILING/LOOKUP`에서 시작하며 proposal, decision idempotency와 Audit를 보존한다.
- **Terminal-Refund reconciliation boundary:** 전용 persistent grant를 가진 operator 한 명이
  `FAILED`/`MANUAL_REVIEW` Refund를 같은 Provider key의 LOOKUP 한 번으로만 다시 연다. 새 REQUEST,
  수기 성공과 금융 입력은 금지하고 명령 멱등성, Audit, 지연 뒤 실제 성공의 별도 Delivery를 보존한다.
- **Deployment gate:** 이 증거 작성 시 Plan 40/50 branch의 main merge 또는 non-local deployment는
  수행하지 않았다. V27 이후 final read-only preflight에서 local `main`, `origin/main`과
  remote `main`이 모두 `5f5232054206b8324e35ca488857e485a59a8fba`임을 확인했다. remote
  feature branch와 기존 PR, GitHub deployment와 environment는 각각 0이었다. 이후 사용자 승인으로
  구현 head `19d69f2`를 `feature/customer-order-cancellation-command`에 push하고 Plan 40+50 combined
  main-targeted ready PR #39를 생성했다. Plan 50 completion 이동은 같은 PR에 포함하며 main merge,
  deployment와 environment 생성은 수행하지 않았다.

### V24~V26 validation baseline (2026-08-03)

- `./gradlew test --tests '*Refund*' --tests '*CustomerCancellation*'`: Passed, 76 tests,
  failures/errors/skips 0, 30초, exit 0.
- `./gradlew test --tests '*Notification*' --tests '*Settlement*'`: Passed, 42 tests,
  failures/errors/skips 0, 23초, exit 0.
- `./gradlew test --tests '*Repair*' --tests '*SetupIntegrity*'`: Passed, 6 tests,
  failures/errors/skips 0, 13초, exit 0.
- `./gradlew test --tests '*ModularityTests'`: Passed, 1 test, failures/errors/skips 0,
  3초, exit 0.
- Plan 20/30 owner·Settlement 연계 묶음: Passed, 16 tests, failures/errors/skips 0, exit 0.
- 첫 `./gradlew clean build`: Failed, 기본 512MiB test JVM heap 고갈로 345 tests 중 14 context
  initialization failure와 1 skip, exit 1. 기능 assertion 실패가 아니라 `OutOfMemoryError`였으며
  실패를 release success로 계산하지 않는다.
- completion audit에서 violation-only scanner backlog 진행, customer/operator/Refund worker/Settlement
  즉시 감지, Refund/snapshot 단독·동시 누락과 source/amount 위반, 감지 증적 저장 실패 rollback을
  추가 검증했다.
- test-only `maxHeapSize = "1g"` 보정 뒤 최종 `./gradlew clean build`: Passed, 358 tests,
  failures/errors/skips 0, 2분 25초, exit 0.
- `bash scripts/verify-docs.sh`: Passed, target 26/deployed 12 paths, 73 schemas,
  32 policies, 74 ADRs, 141 Markdown files와 24 ExecPlans, exit 0.
- `git diff --check`: Passed, exit 0.

### V27 and financial-publication correction validation (2026-08-03)

- `./gradlew cleanTest test --tests '*Refund*' --tests '*CustomerCancellation*'`: Passed, 89 tests,
  failures/errors/skips 0, 37초, exit 0. operator claim marker와 result metadata 불일치가 자동
  LOOKUP budget으로 흐르지 않는 Aggregate guard도 포함한다.
- `./gradlew cleanTest test --tests '*Notification*' --tests '*Settlement*'`: Passed, 43 tests,
  failures/errors/skips 0, 28초, exit 0.
- `./gradlew cleanTest test --tests '*Repair*' --tests '*SetupIntegrity*'`: Passed, 13 tests,
  failures/errors/skips 0, 18초, exit 0. terminal command 101개 중 첫 실행 100개, 두 번째 실행
  잔여 1개를 삭제하는 retention 상한을 실제 PostgreSQL에서 검증했다.
- `./gradlew cleanTest test --tests '*ModularityTests'`: Passed, 1 test,
  failures/errors/skips 0, 4초, exit 0.
- `bash scripts/verify-docs.sh`: Passed, target 27/deployed 13 paths, 75 schemas,
  32 policies, 75 ADRs, 142 Markdown files와 24 ExecPlans, exit 0.
- 첫 `./gradlew clean build`: Not completed. Testcontainers가 Docker container inspect에서 대기하던 중
  Docker backend가 `no space left on device`를 기록하고 daemon을 종료했다. 이 실행은 수동 중단
  exit 130이며 기능 성공·실패로 계산하지 않는다.
- 디스크 여유 공간 확보와 Docker daemon 재시작 뒤 최종 `./gradlew clean build`: Passed, 365 tests,
  failures/errors/skips 0, 2분 10초, exit 0. clean compile, Spotless, bootJar와 PostgreSQL
  Testcontainers를 포함한다.
- V27 이후 `git diff --check`: Passed, exit 0. 변경 파일 secret/personal-context/generated-artifact,
  fallback/예외 삼킴, production dependency와 migration inventory 점검도 통과했다.
- final pre-push remote gate: Passed. remote `main=5f52320`, feature branch 0, head PR 0,
  deployment 0, environment 0이었다. 승인 뒤 feature branch와 ready PR #39만 생성했고 base는 main이다.
- Not run: 실제 Provider credential을 이용한 외부 E2E, non-local deployment와 production smoke.
  현재 외부 환경·credential·SLA가 없고 Draft-only release gate가 배포를 금지하므로 의도적으로
  실행하지 않았다.
