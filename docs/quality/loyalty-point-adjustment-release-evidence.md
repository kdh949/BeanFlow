# Audited Loyalty Point Adjustment Release Evidence

## Evidence identity

- **Recorded at:** 2026-08-04
- **Implementation commit:** `11e0ffe`
- **Migration:** `V31__create_loyalty_point_adjustment.sql`
- **Decision:** [ADR-066](../adr/ADR-066-audited-loyalty-point-adjustment.md)
- **Runbook:** [Audited Loyalty Point Adjustment](../operations/loyalty-point-adjustment-runbook.md)

## Prerequisite and migration evidence

- local `main`과 `origin/main`은 구현 전 `1d28797`로 일치했고 open PR과 다른 active
  migration writer가 없었다. 이 branch가 ADR-072의 V31 writer lane을 사용했다.
- completed Plan 10 V14는 empty/verified/unresolvable PointLot issuer fixture와 startup
  fail-closed를 제공한다. V31은 issuer schema를 재생성하거나 default를 넣지 않고 final
  issuer rows를 다시 fail-closed 검사한다.
- completed Plan 11 V13의 Operations `POINT_ADJUSTMENT` grant와 public authorization API를
  재사용했다. 새 grant table, permission vocabulary 또는 role/JWT fallback은 만들지 않았다.
- completed Plan 13 V17의 `ACCRUAL`/`RECOVERY` ledger를 확장해 current seven types를
  `CREDIT|DEBIT|NONE`으로 deterministic backfill하고 `ADJUSTMENT`만 CREDIT/DEBIT을
  허용한다.
- PostgreSQL fixture는 current-type backfill, type/effect/source CHECK, corrupted issuer
  activation 거부, terminal response shape, exact 90-day retention, scope UNIQUE,
  retention index와 row update 불변성을 검증했다.

## Runtime evidence

- Application Service lock order는 PointAccount → active `POINT_ADJUSTMENT` grant → terminal
  idempotency → `(expiresAt, pointLotId)` ordered PointLot이다.
- CREDIT은 caller issuer/expiry의 Lot 하나와 positive public transaction을 만든다. DEBIT은
  expired Lot과 reserved amount를 제외하고 multi-Lot magnitude를 차감하며 public amount는
  negative다. 부족 시 부분 write와 PointRecoveryPending 없이 409로 rollback한다.
- Account/Lot/PointTransaction/idempotency/Audit/outbox 각각의 강제 persistence failure에서
  Account balance와 모든 child evidence가 rollback됨을 PostgreSQL trigger injection으로
  확인했다.
- same-key/same-payload는 최초 body를 재생하며 Audit, event와 transaction을 추가하지 않는다.
  changed payload/account는 409이고 동시 debit/cross-account 요청에서 한 command만 commit된다.
- terminal retention은 due 101건을 100+1로 삭제하고 `now` 경계를 포함하며 미래 row를
  보존한다. cleanup failure는 `FAILED`와 null deleted count를 기록하고 due row를 남겨 다음
  실행에서 재시도한다.
- `PointsAdjustedV1` exact fixture는 CREDIT issuer 포함, DEBIT issuer 생략, signed amount,
  envelope version/source 관계와 actor/evidence/key/issuer reference 부재를 검증한다. replay는
  publication을 추가하지 않고 Analytics consumer는 구현하지 않았다.

## Validation result

- `./gradlew test --tests '*PointAdjustment*' --tests '*Loyalty*'`: Passed, 19초, exit 0.
- `./gradlew test --tests '*ModularityTests'`: Passed, 4초, exit 0.
- `bash scripts/verify-docs.sh`: Passed at implementation checkpoint, target 27/deployed 14 paths,
  75 schemas, 32 policies, 75 ADRs, 144 Markdown files와 24 ExecPlans, exit 0.
- `git diff --check`: Passed at implementation checkpoint, exit 0.
- 첫 `./gradlew clean build`: Failed, 새 Kotlin 파일의 Spotless 줄바꿈 위반으로 test 실행 전
  중단, exit 1. 기능 성공으로 계산하지 않았다.
- `./gradlew spotlessApply` 뒤 최종 `./gradlew clean build`: Passed, 417 tests,
  failures/errors/skips 0, clean compile, Spotless, bootJar와 PostgreSQL Testcontainers 포함,
  2분 45초, exit 0.
- completed 이동과 Analytics successor readiness 갱신 뒤 `bash scripts/verify-docs.sh`: Passed,
  target 27/deployed 14 paths, 75 schemas, 32 policies, 75 ADRs, 146 Markdown files와
  24 ExecPlans, exit 0.

## Deployment limits

실제 non-local database migration, Platform Operator credential, external Analytics consumer와
production smoke는 실행하지 않았다. 이 저장소에는 해당 환경과 credential이 없으며 local
completion은 배포 성공을 주장하지 않는다. 최초 non-local migration 전 V14 issuer evidence,
V31 precheck와 audited grant를 환경별로 다시 확인한다.
