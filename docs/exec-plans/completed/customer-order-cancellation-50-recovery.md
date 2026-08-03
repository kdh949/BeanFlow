# 고객 취소 recovery와 운영 수렴을 구현한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-40-command.md`
> **Completed-At:** `2026-08-03`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

PAID 고객 취소 뒤 Refund, 네 owner 복원, 접수·환불 알림, 정산 제외와 setup 손상을
재시작 가능한 작업으로 수렴시킨다. 외부 결과 불명을 성공/실패로 위장하지 않고 고객은
안전한 요약, 운영자는 실제 내부 상태와 제한 복구 경로를 본다.

## Current State

- direct predecessor Plan 40은 V23과 C0/C1/CT, command-time projection, timeout recovery와
  332-test clean build를 완료했다. 이 plan은 해당 verified completed-plan head를 유일한 parent로
  하는 Draft stack에서 recovery/release evidence를 완성하고 separate migration-writer lease를
  얻지 않는다.
- Refund는 allowlist REQUEST 최대 3회와 UNKNOWN 이후 LOOKUP 최대 5회를 분리하며 claim
  crash도 REQUEST 재전송 없이 LOOKUP으로 복구한다.
- customer cancellation terminal result event/template과 version logical source Delivery가
  구현됐고 기본 CUSTOMER_NOTIFICATION step을 다시 열지 않는다.
- 고객/운영자 projection, inline+1분 batch-100 setup detector/scanner와 source-unique
  Case/Audit가 구현됐다.
- 완전 snapshot+Refund 누락만 서로 다른 활성 operator 두 명이 30분 내 승인해 원
  ID/source/key/amount와 LOOKUP-first 상태로 복원한다. 자동 만료와 repair idempotency 90일
  bounded cleanup을 포함한다.
- 자동 처리가 끝난 기존 terminal Refund는 전용 persistent grant를 가진 단일 운영자가 금융
  입력 없이 같은 Provider key의 LOOKUP 한 번만 재개할 수 있다. 명령 멱등성, Audit, 90일
  batch-100 cleanup과 지연 뒤 실제 성공 알림을 포함한다.
- Plan 30의 stable listener별 owner 수렴과 Plan 20의 Settlement NOT_APPLICABLE consumer를
  combined branch에서 재검증했다.

## Definitions

- **Request retry:** 부수효과 없음과 same-key 안전이 증명된 allowlist 실패만 최대 3회.
- **Lookup reconciliation:** 결과 불명 뒤 request를 중단하고 최대 5회 조회.
- **SETUP_INCOMPLETE:** 양수 Refund 또는 snapshot 필수 자료가 손상된 내부 무결성 상태.
- **Logical source:** event ID와 무관하게 같은 notification/owner work를 식별하는 source.

## Scope

### In Scope

- Refund request/lookup count 분리, allowlist fail-closed와 claim crash recovery
- customer cancellation refund success/delayed event와 NotificationDelivery
- 네 owner listener의 source-aware 수렴과 step update
- customer/operations compensation projection
- inline detector, 1분 batch-100 setup scanner와 source-unique Case/Audit
- 완전 snapshot+Refund 누락만 복구하는 2인 승인 API
- terminal FAILED/MANUAL_REVIEW Refund의 단일 운영자 LOOKUP-only 재개 API
- Settlement NOT_APPLICABLE 연계, idempotency/timeout cleanup과 운영 runbook

### Non-goals

- snapshot 추정 재구성, 새 REQUEST를 만드는 repair와 DB break-glass
- 실제 Provider credential/SLA 선정
- 보상 전체 완료 고객 알림
- 수락 후 취소

## Business Rules and Invariants

- REQUEST는 최초 포함 3회, LOOKUP은 별도 5회다.
- 어떤 UNKNOWN 뒤에도 REQUEST를 다시 보내지 않는다.
- 미등록 explicit failure는 terminal FAILED/MANUAL_REVIEW다.
- 고객은 내부 FAILED/MANUAL_REVIEW를 PROCESSING+REFUND_DELAYED로 본다.
- 정상 setup 금액 네 개는 모두 존재하고 손상 시 검증 불가 금액을 모두 생략한다.
- repair는 서로 다른 활성 PLATFORM_OPERATOR 두 명과 30분 경계, 재검증을 요구한다.
- terminal Refund reconciliation은 전용 grant를 가진 PLATFORM_OPERATOR 한 명이 기존 key의
  LOOKUP 한 번만 예약하며 새 REQUEST, 수기 성공과 금융 값 입력을 허용하지 않는다.

## Architecture and Transaction Boundaries

- Refund worker: Tx claim → 외부 call → Tx result.
- owner listener: owner Aggregate/allocation transaction.
- terminal result event는 Refund result transaction과 publication을 함께 commit한다.
- Notification/Settlement/setup scanner/repair는 각각 독립 짧은 transaction이다.
- repair 승인 lock은 Order → Payment이고 Provider call은 transaction 밖이다.
- terminal reconciliation 명령은 권한·Refund/PAYMENT step·멱등성·Audit를 한 transaction에
  저장하고 Provider LOOKUP은 transaction 밖의 기존 Refund worker가 정확히 한 번 수행한다.

## Alternatives Considered

- UNKNOWN 뒤 request 재전송: 중복 환불 위험으로 제외한다.
- terminal Refund polling으로 알림: result publication 원자성을 잃어 제외한다.
- setup snapshot 자동 재구성: 취소 시점 금융 원천을 추정해 제외한다.
- 한 운영자 누락 Refund 즉시 repair: 금융 row를 재구성하므로 오조작 방어가 부족해 제외한다.
- terminal Refund의 2인 LOOKUP 승인: 읽기 전용 Provider 조회에도 복구 지연이 커 전용 grant,
  원천 tie-out과 append-only Audit를 갖춘 단일 운영자 명령을 선택했다.

## Failure Semantics

- 세 번째 retryable REQUEST 실패는 FAILED와 PAYMENT/Case MANUAL_REVIEW다.
- 다섯 번째 LOOKUP 불명 또는 최종 claim crash는 추가 외부 작업 없이 MANUAL_REVIEW다.
- detector의 Case/Audit 저장 실패는 조회 503, worker/consumer retry다.
- Notification 네 번째 실패는 Delivery MANUAL_REVIEW와 ReprocessingCase다.
- owner 하나 실패해도 다른 owner 작업을 계속하고 Order CANCELLED를 되돌리지 않는다.
- operator LOOKUP 실패·불명은 자동 budget을 늘리거나 재시도하지 않고 즉시 terminal 지연
  상태로 돌아가며, 다음 시도에는 새 운영 명령과 Audit가 필요하다.

## Data and Migration

ADR-072의 Plan 40→50 shared migration-writer lease에서 completed Plan 40 head를 parent로 시작한 뒤
V24 terminal result logical source, V25 setup integrity, V26 two-person setup repair, V27 terminal
Refund operator reconciliation command/marker/index를 forward migration으로 추가한다. 적용 migration
수정이나 checksum repair 없이 계획 00의 migration 전략을 따른다.

## API and Event Contracts

- CustomerCancellationRefundSucceededV1/DelayedV1은 Payment result transaction에서 발행한다.
- payload는 최소 envelope/order/customer/version/amount/outcomeAt만 가진다.
- GET Order와 cancellation response는 같은 customer projection mapper를 쓴다.
- operations view만 attempt/error/setup issue/repair case를 노출한다.
- operations reconciliation POST는 reason과 `Idempotency-Key`만 받고 `202` 예약 결과만 반환한다.
  unknown JSON·금융 식별자 입력은 fail-closed이며 환불 성공을 뜻하지 않는다.

## Milestones

1. request/lookup state machine과 Provider adapter allowlist를 구현한다.
2. claim/result transaction과 terminal event를 구현한다.
3. 네 owner listener와 step-specific recovery를 검증한다.
4. customer/operations projection과 Notification을 구현한다.
5. Settlement exclusion 연계를 검증한다.
6. setup inline/scanner detection과 2인 repair를 구현한다.
7. terminal Refund의 single-operator LOOKUP recovery와 지연 뒤 성공 수렴을 구현한다.
8. retention, metrics, alerts, runbook과 release verification을 완료하고 Plan 40+50 combined main-targeted release PR을 만든다.

## Required Tests

- REQUEST 3회, UNKNOWN 뒤 LOOKUP 5회, claim crash와 same Provider key
- allowlist/non-allowlist explicit failure
- result/event publication 원자성, duplicate event/logical source
- owner source conflict와 listener별 exhaustion
- customer projection 전체 상태 oneOf와 internal field 부재
- success/delayed notification, delayed 뒤 success, provider retry
- setup inline+scanner 수렴, 저장 실패, 모든 손상 분류
- self/stale/expired/concurrent 2인 승인과 LOOKUP-only repair
- terminal FAILED/MANUAL_REVIEW의 grant/revoke, 멱등 replay/conflict, 동시 명령, 정확히 한 번
  LOOKUP, 불명 즉시 종결, 지연 뒤 성공 알림과 batch-100/90일 retention
- Settlement Item/Adjustment 부재와 Audit
- restart, Modulith, architecture/startup failure
- Plan 40/50 Draft stack에서는 production deployment가 없고, final combined release PR만 main base를
  갖는지 확인

## Validation Commands

```bash
./gradlew test --tests '*Refund*' --tests '*CustomerCancellation*'
./gradlew test --tests '*Notification*' --tests '*Settlement*'
./gradlew test --tests '*Repair*' --tests '*SetupIntegrity*'
./gradlew test --tests '*ModularityTests'
./gradlew clean build
bash scripts/verify-docs.sh
git diff --check
```

## Observability

refund reason/provider/mode/outcome, compensation trigger/step/state, notification template/
state, settlement exclusion, setup age/lag와 repair outcome을 닫힌 tag로 측정한다.

## Documentation Updates

ADR-072, failure semantics, event catalog, OpenAPI, payment/store lifecycle runbook, 새 cancellation
runbook, test strategy와 quality evidence를 actual behavior와 맞춘다.

## Progress

- [x] refund budgets/allowlist
- [x] result events
- [x] owner convergence
- [x] projections/notifications
- [x] settlement integration
- [x] setup detection/repair
- [x] terminal Refund single-operator LOOKUP recovery
- [x] retention/operations
- [x] 전체 release verification
- [x] combined PR과 completed 이동 — PR #39
- [x] V27 이후 전체 검증

## Surprises & Discoveries

- 현재 Refund attempt 5회에는 최초 REQUEST가 포함돼 lookup은 네 번뿐이다.
- Operations가 Payment API를 직접 호출하면 기존 Payment→Operations API와 module cycle이
  생긴다. Operations-owned repair port를 Operations API에 두고 Payment가 구현하는 의존성
  역전으로 proposal 소유권과 단방향 Modulith 경계를 함께 보존했다.
- Plan 20/30의 Settlement exclusion과 owner listener/exhaustion은 새 구현 대상이 아니라
  verified foundation이었다. Plan 50 branch에서 16개 관련 테스트로 실제 연계를 재확인했다.
- explicit financial target publication은 listener를 직접 호출하지 않으므로 최초 row가
  `PUBLISHED`이면 Modulith 2.1 bounded resubmission 대상이 되지 않았다. `FAILED`, attempt 0,
  last-resubmission null로 저장해 실제 recovery worker가 Notification/Settlement consumer를
  호출하도록 ADR-068과 Plan 16 evidence를 바로잡았다.
- PR #39 최초 GitHub Actions runner는 나노초 정밀도의 `Instant`를 반환해, PostgreSQL에 저장된
  마이크로초 Refund 시각과 원본 `PaymentRefundedV1` 시각이 달라지는 플랫폼 의존 결함을 드러냈다.
  Provider 결과 기록 경계에서 한 번 정규화해 Refund, Payment, event와 compensation이 같은 시각을
  사용하도록 보정했다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-07-31 | Accepted existing | request 3, unknown 후 lookup 5, 고객 지연 projection | 중복 환불 없이 bounded recovery | ADR-037/038 |
| 2026-07-31 | Accepted existing | 제한 repair는 2인 승인과 LOOKUP-only | 금융 원천 추정·오조작 방지 | ADR-052/053 |
| 2026-08-01 | Accepted | Plan 50은 Plan 40 Draft head의 only child이고 final release PR로 40+50을 함께 main에 병합 | incomplete cancellation endpoint의 intermediate deployment 방지 | ADR-072 |
| 2026-08-03 | Accepted | terminal Refund는 전용 grant의 단일 운영자 명령으로 기존 key LOOKUP 한 번만 재개 | 새 금융 부수효과와 수기 성공 없이 실제 Provider 결과로 지연 뒤 성공을 수렴 | ADR-075 |
| 2026-08-03 | Corrected | 직접 저장한 financial target publication 최초 상태는 FAILED/attempt 0 | Modulith bounded resubmission이 실제 listener를 선택하도록 함 | ADR-068 |

## Outcomes & Retrospective

구현은 V24 terminal notification source, V25 setup integrity, V26 two-person repair와 V27 terminal
Refund single-operator reconciliation까지 확장됐다. Refund state machine, 고객/운영자 projection,
notification, owner convergence, Settlement exclusion, 두 repair 경계와 retention의 대상 검증이
통과했다. completion audit에서 violation-only scanner backlog 진행, customer/operator/Refund
worker/Settlement 즉시 감지, 모든 setup 손상 분류, 감지 증적 저장 실패 rollback과 실제 financial
publication consumer 전달을 보강했다. V27 이후 문서/OpenAPI 검증과 대상 test 묶음은 통과했지만
초기 전체 build는 Docker backend의 디스크 고갈과 daemon 종료로 완료되지 않았다. 디스크 여유 공간과
daemon을 복구한 뒤 365-test clean build를 failure/error/skip 0으로 재검증했다. final diff/security/
remote preflight에서도 origin/main 불변, remote feature branch/PR/deployment/environment 부재를
확인했다. 이후 사용자 승인으로 구현 head `19d69f2`를 push하고 Plan 40+50 전체 diff를 담은 main 대상
ready PR #39를 생성했다. 이 completion 이동도 같은 PR에 포함한다. main merge나 deployment는 수행하지
않았으며 shared migration-writer lease는 PR #39 merge까지 유지한다. PR 최초 build 실패는 성공으로
계산하지 않았고 PostgreSQL 시각 정밀도 보정과 고정 나노초 회귀 입력을 추가한 뒤 대상 테스트와
365-test clean build를 다시 통과했다. 보정 head의 원격 build 성공을 merge gate로 유지한다.

## Revision Notes

- 2026-07-31: readiness audit에서 최초 작성.
- 2026-08-03: Plan 40 completed Draft handoff와 332-test evidence를 확인하고 dependency를 completed
  path로 바꿔 `Implementation-Ready=true`로 전환했다. shared migration-writer lease와 Draft-only
  production gate는 유지한다.
- 2026-08-03: V24~V26 구현, owner/Settlement 재검증과 358-test baseline을 완료했다. 이후 ADR-075와
  V27 single-operator terminal reconciliation, 실제 financial publication consumer 전달을 보강했다.
  V27 이후 365-test clean build와 문서/OpenAPI 검증을 완료했다.
- 2026-08-03: 구현 head `19d69f2`를 push해 main 대상 ready PR #39를 만들고 completed path로 이동했다.
  main merge/deployment는 없으며 shared migration-writer lease는 PR merge까지 held 상태로 유지한다.
- 2026-08-03: PR #39 최초 build의 Linux/JVM 나노초 대 PostgreSQL 마이크로초 불일치를 수정하고
  고정 나노초 회귀 입력, 대상 테스트와 365-test clean build를 재검증했다.
