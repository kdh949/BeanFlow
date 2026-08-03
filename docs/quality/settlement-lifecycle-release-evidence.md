# Settlement Lifecycle Quality Evidence

## Evidence identity

- **Recorded at:** 2026-08-03
- **Scope:** 일별 Settlement Batch 계산·확정, 확정 후 Refund/Dispute Adjustment,
  OWNER Dispute 접수·재이의·판정 handoff, persistent Audit/event/reprocessing
- **Database:** PostgreSQL 17.6 Testcontainers, Flyway V1~V30
- **Runtime:** Java 21, Spring Boot 4.1, Spring Modulith 2.1
- **Non-goal:** 실제 계좌 지급, 외부 evidence 저장소, 공개 Dispute 판정 endpoint와 non-local 배포

## Durable controls

- V28은 Batch summary/carry/transition, confirmed mutation guard, immutable Adjustment,
  Dispute active/idempotency/state/refile와 Audit/publication query index를 추가한다.
- V29는 Adjustment creation-time ingestion cursor를 추가해 늦게 생성된 과거-effective
  Adjustment가 다음 Batch에 한 번 포함되도록 한다.
- V30은 재이의 evidence 배열의 순서·축약 변경이 아니라 이전 배열에 없던 reference가 실제로
  하나 이상 있어야 한다는 조건으로 DB trigger를 강화한다.
- Application Service는 Batch/Item 객체 graph를 확장하지 않고 500건 keyset DTO projection을
  사용한다. confirmed Item/Batch 조회와 Adjustment command만 Context public surface로 제공한다.
- filing은 Item 및 actor-key PostgreSQL transaction advisory lock, active OWNER membership,
  terminal 201 response와 canonical payload hash를 사용한다. accepted decision은 Adjustment
  `REQUIRES_NEW` 선커밋 뒤에만 terminal이고 후속 실패는 source-unique Case로 남는다.

## Failure and privacy evidence

- Batch Audit/outbox 실패는 `CALCULATED`, filing Audit/outbox 실패는 Dispute/idempotency row 0건으로
  rollback한다. unconfirmed Refund는 Adjustment 0건과 미완료 publication으로 남는다.
- 같은 Adjustment source의 다른 target/reason/amount는 기존 row를 덮어쓰지 않고 503 source
  conflict와 ReprocessingCase로 남는다.
- accepted Adjustment 뒤 Dispute event 저장 실패는 Adjustment를 보존하고 Dispute
  `UNDER_REVIEW`/held와 manual Case를 유지한다. retry는 기존 Adjustment exact replay 뒤
  Dispute `ACCEPTED`, held 0, Case `RESOLVED`로 수렴한다.
- Dispute persistent event에는 evidence reference, 자유 입력 reason, actor와 Idempotency-Key가
  없다. metric tag는 closed state/reason/outcome만 사용한다.

## Focused validation

- `SettlementBatchLifecycleIntegrationTest`: Passed, 6 tests, failures/errors/skips 0.
- `SettlementDisputeIntegrationTest`: Passed, 9 tests, failures/errors/skips 0.
- 앞선 구현 checkpoint에서 Batch lifecycle/query/migration/Modulith 11 tests,
  Adjustment/refund exclusion/Modulith 16 tests와 Dispute aggregate/migration/Modulith 묶음이 통과했다.
- 첫 metric 회귀 실행은 Kotlin 증분 compiler cache가 삭제된 class file을 참조해 실패했다.
  source assertion 실패로 계산하지 않았고 `./gradlew clean test`로 같은 15 tests를 재실행해 통과했다.

## Release validation (2026-08-03)

- `./gradlew test --tests '*Settlement*' --tests '*Dispute*'`: Passed, 64 tests,
  failures/errors/skips 0, 44초.
- `./gradlew test --tests '*Refund*' --tests '*ModularityTests'`: Passed, 70 tests,
  failures/errors/skips 0, 35초.
- `./gradlew clean build`: Passed, 398 tests, failures/errors/skips 0, 2분 39초. clean compile,
  Spotless, bootJar와 PostgreSQL Testcontainers를 포함한다.
- `bash scripts/verify-docs.sh`: Passed, target/deployed 27/13 paths, 75 schemas,
  32 business policies, 75 ADRs, 144 Markdown files와 24 ExecPlans.
- `git diff --check`: Passed.
- Not run: 실제 Provider credential 기반 외부 E2E, non-local deployment/production smoke,
  push/PR/merge. 현재 작업 권한과 실제 지급 Non-goal 밖이다.

이 증거는 local implementation readiness를 뒷받침하지만 non-local release 승인이나 배포 증거는 아니다.

## Measurement evidence

단일 로컬 fixed fixture이며 기준선/SLA/개선율이 아니다.

| Input / observation | Measured result |
|---|---:|
| PostgreSQL / Item fixture / chunk | 17.6 / 1,000 / 500 |
| first 500-row keyset plan | `idx_settlement_item_batch_cursor` Index Scan |
| plan buffers / planning / execution | shared hit 20 / 0.110ms / 0.119ms |
| Batch calculation / confirmation | 36.054ms / 17.260ms |
| controlled same-row lock hold / observed wait | 200ms / 203.086ms |

warm p50/p95/p99, RPS, GC/allocation, Hikari pending, 10k+ Item과 multi-store backlog는
`Not measured`다. 실제 데이터 분포와 SLO가 생기면 같은 schema/query/chunk 조건으로 재측정한다.
