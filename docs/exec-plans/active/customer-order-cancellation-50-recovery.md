# 고객 취소 recovery와 운영 수렴을 구현한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-order-cancellation-40-command.md`
> **Completed-At:** `—`

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
- 현재 Refund는 REQUEST 1회 후 LOOKUP 4회인 합산 attempt 모델이다.
- retryable explicit failure allowlist와 request 3/lookup 5 분리 예산이 없다.
- customer cancellation terminal result event/template, setup detector/scanner/repair가 없다.
- Plan 30의 publication exhaustion은 stable listener target별 step-specific recovery로 완료됐다.
- Plan 20의 Settlement foundation과 고객 취소 refund NOT_APPLICABLE evidence consumer가 완료됐다.

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

## Architecture and Transaction Boundaries

- Refund worker: Tx claim → 외부 call → Tx result.
- owner listener: owner Aggregate/allocation transaction.
- terminal result event는 Refund result transaction과 publication을 함께 commit한다.
- Notification/Settlement/setup scanner/repair는 각각 독립 짧은 transaction이다.
- repair 승인 lock은 Order → Payment이고 Provider call은 transaction 밖이다.

## Alternatives Considered

- UNKNOWN 뒤 request 재전송: 중복 환불 위험으로 제외한다.
- terminal Refund polling으로 알림: result publication 원자성을 잃어 제외한다.
- setup snapshot 자동 재구성: 취소 시점 금융 원천을 추정해 제외한다.
- 한 운영자 즉시 repair: 금융 오조작 방어가 부족해 제외한다.

## Failure Semantics

- 세 번째 retryable REQUEST 실패는 FAILED와 PAYMENT/Case MANUAL_REVIEW다.
- 다섯 번째 LOOKUP 불명 또는 최종 claim crash는 추가 외부 작업 없이 MANUAL_REVIEW다.
- detector의 Case/Audit 저장 실패는 조회 503, worker/consumer retry다.
- Notification 네 번째 실패는 Delivery MANUAL_REVIEW와 ReprocessingCase다.
- owner 하나 실패해도 다른 owner 작업을 계속하고 Order CANCELLED를 되돌리지 않는다.

## Data and Migration

ADR-072의 Plan 40→50 shared migration-writer lease에서 completed Plan 40 head를 parent로 시작한 뒤 request/lookup counts, next action, terminal result logical source, setup repair proposal/
decision, scanner index와 retention index를 forward migration으로 추가한다. 계획 00의
migration 전략을 따른다.

## API and Event Contracts

- CustomerCancellationRefundSucceededV1/DelayedV1은 Payment result transaction에서 발행한다.
- payload는 최소 envelope/order/customer/version/amount/outcomeAt만 가진다.
- GET Order와 cancellation response는 같은 customer projection mapper를 쓴다.
- operations view만 attempt/error/setup issue/repair case를 노출한다.

## Milestones

1. request/lookup state machine과 Provider adapter allowlist를 구현한다.
2. claim/result transaction과 terminal event를 구현한다.
3. 네 owner listener와 step-specific recovery를 검증한다.
4. customer/operations projection과 Notification을 구현한다.
5. Settlement exclusion 연계를 검증한다.
6. setup inline/scanner detection과 2인 repair를 구현한다.
7. retention, metrics, alerts, runbook과 release verification을 완료하고 Plan 40+50 combined main-targeted release PR을 만든다.

## Required Tests

- REQUEST 3회, UNKNOWN 뒤 LOOKUP 5회, claim crash와 same Provider key
- allowlist/non-allowlist explicit failure
- result/event publication 원자성, duplicate event/logical source
- owner source conflict와 listener별 exhaustion
- customer projection 전체 상태 oneOf와 internal field 부재
- success/delayed notification, delayed 뒤 success, provider retry
- setup inline+scanner 수렴, 저장 실패, 모든 손상 분류
- self/stale/expired/concurrent 2인 승인과 LOOKUP-only repair
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

- [ ] refund budgets/allowlist
- [ ] result events
- [ ] owner convergence
- [ ] projections/notifications
- [ ] settlement integration
- [ ] setup detection/repair
- [ ] retention/operations/release
- [ ] 전체 검증

## Surprises & Discoveries

- 현재 Refund attempt 5회에는 최초 REQUEST가 포함돼 lookup은 네 번뿐이다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-07-31 | Accepted existing | request 3, unknown 후 lookup 5, 고객 지연 projection | 중복 환불 없이 bounded recovery | ADR-037/038 |
| 2026-07-31 | Accepted existing | 제한 repair는 2인 승인과 LOOKUP-only | 금융 원천 추정·오조작 방지 | ADR-052/053 |
| 2026-08-01 | Accepted | Plan 50은 Plan 40 Draft head의 only child이고 final release PR로 40+50을 함께 main에 병합 | incomplete cancellation endpoint의 intermediate deployment 방지 | ADR-072 |

## Outcomes & Retrospective

미구현 상태다. Plan 40 Draft implementation의 actual outcomes를 parent로 소비하며, 이 계획의
recovery/release verification이 끝난 combined release PR만 고객 취소 capability를 main/deploy한다.

## Revision Notes

- 2026-07-31: readiness audit에서 최초 작성.
- 2026-08-03: Plan 40 completed Draft handoff와 332-test evidence를 확인하고 dependency를 completed
  path로 바꿔 `Implementation-Ready=true`로 전환했다. shared migration-writer lease와 Draft-only
  production gate는 유지한다.
