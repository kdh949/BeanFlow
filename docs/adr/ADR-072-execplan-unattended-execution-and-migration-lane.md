# ADR-072: 무인 ExecPlan 실행과 migration writer lane

- **Status:** Accepted
- **Date:** 2026-08-01
- **Implementation owner:** documentation and delivery workflow

## Context

기존 customer-cancellation master plan은 Plan 10과 Plan 20을 병렬 branch로 시작하고
Plan 40을 여러 head의 통합 baseline에서 시작하도록 표현했다. GitHub Pull Request에는 base가
하나뿐이므로, integration branch·merge commit·diff 정책이 없는 상태에서 이 graph는 자동 실행할 수
없다. 두 branch가 모두 현재 Flyway 마지막 번호를 보고 다음 번호를 선택하면 schema object가 달라도
`V13` 충돌이 난다.

또한 `active/`의 orchestration plan이 implementation plan과 같은 metadata만 가지면 자동 Goal이
코드 작업으로 선택할 수 있다. 완료 plan을 `completed/`로 옮길 때 dependency의 path를 고치지 않으면
canonical graph도 끊긴다.

## Decision

### Canonical metadata와 직접 dependency

모든 ExecPlan은 `Status`, `Kind`, `Implementation-Ready`, `Writes-Migration`, `Depends-On`,
`Completed-At`을 제목 바로 아래 canonical metadata로 가진다.

- `Kind=IMPLEMENTATION`만 자동 실행 후보가 된다. `Kind=ORCHESTRATION`은 graph/release gate를
  설명할 뿐 code, migration 또는 endpoint 작업으로 선택하지 않으며
  `Implementation-Ready=false`여야 한다.
- `Implementation-Ready=true`은 해당 plan이 implementation으로 충분히 명세되었고 모든 direct
  dependency가 completed 경로의 actual outcome/evidence로 충족됐음을 뜻한다. 조건 중 하나라도
  바뀌면 false로 되돌린다.
- `Writes-Migration=true`은 `src/main/resources/db/migration`의 새/변경 migration을 만들 가능성이
  있는 plan이다. 알려진 schema change가 없는 common codec plan은 false다.
- `Depends-On`은 **현재 plan이 시작하기 전에 완료되어야 하는 direct phase input**만 적는다.
  ancestor를 branch base 유지 목적으로 반복하지 않는다. 여러 producer의 independent event contract처럼
  현재 plan이 각각의 actual outcome을 직접 소비할 때만 여러 항목을 적는다.

### Automatic branch와 migration lane

무인 executor는 다음 규칙을 모두 지킨다.

1. candidate branch는 항상 **현재 최신 `main`**에서 만든다. 단, 아래 Plan 40→50 Draft stack의
   child만 verified Plan 40 Draft head에서 시작할 수 있다. `Depends-On`은 branch base를 계산하는
   값이 아니며, active sibling head를 통합 baseline으로 추측하지 않는다.
2. `Writes-Migration=true` candidate는 repository-wide migration-writer lease를 얻은 경우에만
   시작한다. lease가 살아 있는 동안 다른 schema-writing plan은 시작하지 않는다.
3. lease holder는 branch를 만든 직후 최신 `main`의 Flyway 마지막 번호를 읽고 새 번호를 정한다.
   predecessor PR merge 후에만 다음 migration writer를 시작하므로 migration number reservation manifest,
   duplicated DDL, checksum repair와 rebase 경쟁을 사용하지 않는다.
4. non-migration implementation은 dependency와 ownership이 충족되는 경우 병렬일 수 있지만,
   incomplete required behavior를 feature flag/profile로 2xx 성공처럼 노출하지 않는다.

customer cancellation의 direct phase dependency는 아래 graph로 고정한다. migration-writer lease는
schema-writing plan을 한 번에 하나만 실행하게 하지만, 실제로 소비하지 않는 outcome을 queue priority
목적으로 `Depends-On`에 추가하지 않는다.

```text
Plan 00 -> Plan 10 issuer -> Plan 15 snapshot
Plan 00 -> Plan 11 grants -> Plan 12 allocation -> Plan 13 recovery
Plan 11 grants + Plan 13 recovery + signed-cursor foundation -> Plan 14 read
Plan 12 + Plan 13 + Plan 15 -> Plan 16 refund/Loyalty events
Plan 15 + Plan 16 + signed-cursor foundation -> Plan 20
Plan 11 grants + Plan 20 -> Plan 30 -> Plan 40 -> Plan 50
```

Plan 10은 completed Plan 00만 직접 소비한다. signed-cursor foundation은 Plan 10의 migration, issuer
precheck 또는 DTO contract에 input을 주지 않는다. Plan 15는 Plan 10 issuer output만 직접 소비하는
settlement input snapshot foundation이다. Plan 16은 Plan 12/13/15 output을 함께 소비해
`PaymentRefundedV1`, `PointsAccruedV1`, `PointsRestoredV1`만 producer로 만든다. Plan 20은 Plan 15
snapshot, Plan 16 outcome과 signed-cursor foundation을 직접 소비해 `OrderCompletedV1 -> V2` cutover,
Ordering producer와 Settlement consumer를 소유한다. Plan 30의 prior parallel branch는 없으며, Plan 30은
Plan 20 outcome 외에도 Plan 11 policy-head outcome을 직접 소비한다. Plan 40/50은 Plan 30까지의 merged
`main`을 input으로 한다. 이 graph는 logical context ownership을 합치거나 settlement schema ownership을
변경하지 않는다.

Plan 14는 Plan 11의 grant enforcement, Plan 13의 실제 `recoveryPendingKrw` summary와 ledger type,
signed-cursor codec을 각각 직접 소비한다. Plan 13이 Plan 11의 transitive successor여도 grant와
recovery는 서로 다른 직접 input이므로 둘을 모두 기록한다. Plan 14는 세 dependency가 모두 completed인
마지막 completion commit에서만 `Implementation-Ready=true`로 전환한다.

### Plan 40 production gate

Plan 40은 Plan 50이 없이는 production success endpoint를 만들 수 없다. 따라서 Plan 40은 latest main base의 **Draft PR**로만 검토하고 merge/deploy하지 않는다. Plan 50은 Plan 40 head를
유일한 parent로 하는 Draft stack에서 구현한다. Plan 50 validation이 끝나면 Plan 50 head를 main에
target한 release PR 하나가 Plan 40+50 전체 diff를 포함해 merge된다; Plan 40 draft는 superseded로
닫는다. 독립 Plan 40 merge, temporary feature flag 또는 profile-based success path는 허용하지 않는다.

### Draft stack handoff와 migration-writer lease

Plan 40→50은 하나의 Draft stack과 하나의 migration-writer lease로 취급한다. Plan 40이 latest
main에서 첫 migration 번호를 정하고 lease를 얻은 뒤, Plan 50 combined release PR이 main에 merge될
때까지 lease를 유지한다. Plan 50은 별도 lease를 얻거나 independent latest-main migration writer로
시작하지 않는다. Plan 40의 verified outcome 뒤 Plan 40 Draft branch에서 completion commit을 먼저
만들어 `active → completed` 이동, Plan 50의 direct dependency path 갱신과
`Implementation-Ready=true` 갱신을 함께 기록한다. Plan 50은 그 verified completed Plan 40 head에서
시작하고, 새 migration이 필요하면 이미 stack에 있는 Plan 40 migration 뒤의 다음 번호를 사용한다.

stack lease가 살아 있는 동안 unrelated schema writer는 시작하지 않는다. parent 또는 child가
rebase/lease 복구를 요구하면 automation은 멈추고, deployment되지 않은 Draft stack에서 latest main과
combined migration inventory를 다시 검증한 뒤에만 재개한다. applied migration을 checksum repair하거나
published schema를 재번호화하지 않는다.

### Completion path update

plan completion commit은 반드시 다음을 같은 atomic documentation change에 포함한다.

1. `active/`에서 `completed/`로 파일 이동과 `Status=COMPLETED`, 완료일 갱신
2. 모든 direct successor의 `Depends-On` path를 새 completed path로 갱신
3. 모든 direct dependency가 completed가 된 successor만 `Implementation-Ready=true`로 갱신
4. dependency graph/document validation 실행 결과 기록

Plan 40의 completion은 Draft parent branch에서 위 규칙을 수행하는 유일한 예외다. 해당 commit은
Plan 50 child branch의 verified starting point가 되며, final combined release PR이 merge될 때까지
main의 implementation candidate를 바꾸지 않는다.

## Alternatives Considered

### 병렬 branch와 암묵적 통합 baseline 유지

PR base가 하나라는 제약과 Flyway 번호 경쟁을 자동화가 추측하게 만든다.

### migration number reservation manifest만 도입

번호 충돌만 막고 branch base, schema review order와 concurrent DDL ownership 문제를 남긴다.

### Plan 40을 main에 merge하고 feature flag로 숨김

배포 시 flag 설정 실수로 미완성 financial path가 성공할 수 있고 explicit failure semantics를
위반한다.

## Consequences

- 자동 Goal은 master orchestration plan을 구현 작업으로 선택하지 않는다.
- 모든 migration-writing plan은 한 번에 하나만 시작하므로 Flyway 번호는 branch 시작 시점의
  main에서 결정적이다.
- direct dependency와 execution scheduling을 혼동하지 않아 Plan 50 같은 transitive
  dependency 중복이 사라진다.
- Plan 10은 Plan 00 outcome이 완료되면 ready가 될 수 있으며, signed-cursor implementation의 queue
  priority는 dependency가 아니라 executor/lease policy로 관리한다.
- Plan 40/50은 merge 전에는 production에 배포되지 않으므로 별도 runtime flag가 필요 없다.

## Verification

- documentation verifier는 metadata enum, active/completed path, graph cycle, orchestration readiness와
  migration metadata를 검증한다.
- automation smoke test는 active orchestration plan을 candidate에서 제외하고, simultaneous
  migration writers를 거부하며, successor path update가 빠지면 실패한다.
- customer-cancellation sequence에서 Plan 20이 Plan 15 이전, Plan 30이 Plan 20 이전, Plan 50이
  Plan 40 이전에 ready가 되지 않음을 확인한다.
- Plan 14가 Plan 11, Plan 13과 signed-cursor foundation 중 하나라도 미완료면 ready가 되지 않음을
  확인한다.
- Plan 10이 signed-cursor foundation을 direct dependency로 다시 추가하지 않고 Plan 00 outcome만
  소비하는지 확인한다.

## Related Decisions

- [ADR-067](ADR-067-settlement-batch-creation-and-schema-ownership.md)
- [ADR-068](ADR-068-immutable-integration-event-snapshots.md)
- [ADR-070](ADR-070-signed-cursor-and-pagination-contract.md)
- [ADR-071](ADR-071-settlement-input-snapshot-foundation.md)
