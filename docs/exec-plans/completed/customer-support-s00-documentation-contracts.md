# Customer Support planning baseline을 감사하고 정합화한다

> **Status:** `COMPLETED`
> **Kind:** `ORCHESTRATION`
> **Implementation-Ready:** `false`
> **Writes-Migration:** `false`
> **Depends-On:** —
> **Completed-At:** `2026-08-10`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

기존 S00 산출물을 source of truth로 간주하지 않고 최신 main의 코드·Accepted 정책과 사용자가 확정한
Customer Support 결정에 맞춰 감사한다. 완료 결과는 Accepted/Proposed/Draft/Blocked를 구분하고,
generic OpenAPI와 placeholder implementation plan이 구현 준비도를 과장하지 않게 한다.

## Current State

- 현재 branch `refactor/check-repo`의 base commit은 local/origin `main`과 같은 `e0025cd`다.
- Support/Delivery application module과 runtime endpoint는 없다.
- canonical target/runtime OpenAPI는 Support repair에서 변경하지 않는다.
- 55개 API surface는 별도 DRAFT inventory이며 semantic implementation contract는 0개다.
- Analytics ExecPlan은 ready migration candidate지만 실제 lease acquisition evidence는 없다.
- S10 외 S20~S140 implementation shell은 orchestration roadmap으로 축소한다.

## Definitions

- **Decision recorded:** 제품/구조 결정이 기록됐다는 뜻이며 구현 또는 검증을 뜻하지 않는다.
- **Draft API surface:** endpoint 필요성과 typed contract 입력만 기록됐고 canonical schema가 아니다.
- **Implementation-ready:** direct input이 완료됐고 중요한 결정을 추측하지 않으며 plan만으로 구현 가능한 상태다.
- **Migration lease evidence:** 실행 branch/worktree/task/PR identity와 명시적 획득 기록이다.

## Scope

### In Scope

- ADR-070/090/091, Business Policy와 migration scheduling/dependency 정합화
- canonical OpenAPI 원복과 55-operation DRAFT inventory
- S10 detailed plan, program orchestration roadmap와 S00 completion evidence
- traceability status, capability count, runbook/test 문서의 정직한 분류
- 문서 verifier가 speculative contract를 강제하지 않는지 검증

### Non-goals

- application/frontend implementation, Flyway migration, production dependency, Provider/KMS 선택
- Analytics/S10 실행, commit, push, PR, deployment 또는 법규 준수 주장

## Business Rules and Invariants

사용자가 확정한 SP-01~SP-15 중 frontend implementation boundary를 제외한 제품 결정은 유지한다.
Support Console이 최종 scope라는 결정만 Accepted이고 app/origin/rendering 선택은 Proposed다. Financial
Audit 5년을 PII Audit 2년으로 축소하지 않는다. Unknown Provider result, Audit failure와 partial deletion을
성공으로 표시하지 않는다.

## Architecture and Transaction Boundaries

이번 plan은 문서만 변경해 runtime transaction이 없다. 후속 S10은 Operations-owned local transaction에서
Audit append/policy snapshot과 permission grant/revoke를 fail-closed하게 처리한다. Support는 owner
Repository/table을 직접 사용하지 않는다.

## Alternatives Considered

- 55개 endpoint를 generic schema로 canonical target에 유지: 표현력이 없고 거짓 완전성을 만들어 제외.
- 55개 endpoint-specific schema를 S00에서 완성: owner model/DTO가 없어 추측이 되므로 제외.
- placeholder S20~S140 유지: self-contained가 아니고 자동 후보를 오염시켜 제외.
- Analytics를 S10 dependency로 유지: direct output을 소비하지 않는 fake dependency라 제외.

## Failure Semantics

검증 명령 실패, 누락 planning pack 파일, unresolved decision과 model gap을 성공/완료로 바꾸지 않는다.
S00은 corrections와 독립 validation이 끝난 뒤에만 completed path로 이동한다.

## Data and Migration

Migration을 쓰지 않는다. 최신 source inventory는 V1~V38이다. 이 inventory는 future V-next reservation이나
현재 lease holder 증거가 아니다.

## API and Event Contracts

Canonical target/runtime OpenAPI에는 Support/Delivery/LegalHold operation이 없다. 55개 operation은
`docs/api/support-api-surface.md`에 DRAFT로만 존재한다. 각 Stage가 endpoint-specific typed schema와
security/error/cursor contract를 확정할 때 target에 추가한다.

## Milestones

1. P0/P1 source conflict, current code/model gap과 actual migration evidence를 재감사한다.
2. Accepted/Proposed와 direct dependency/scheduling을 정합화한다.
3. canonical OpenAPI를 원복하고 55개 DRAFT inventory를 검증한다.
4. S10만 detailed active implementation plan으로 남기고 program orchestration을 만든다.
5. traceability, operational/testing classification, index와 audit report를 정합화한다.
6. 지정된 네 명령과 여섯 수동 gate를 실행하고 exact result를 기록한다.

## Required Tests

- target OpenAPI Support catch-all/operation 0
- draft inventory operation 55, duplicate 0, 누락된 네 operation 포함
- traceability original ID 236 유지와 허용 status vocabulary
- active Support implementation plan이 S10 하나인지 검증
- queue-only `Depends-On`, unresolved Accepted choice, private context 부재

## Validation Commands

- `git diff --check`
- `./scripts/verify-docs.sh`
- `./gradlew spotlessCheck test`
- `./gradlew test --tests '*ModularityTests'`

## Observability

문서 plan이라 runtime metric을 만들지 않는다. 검증은 command, exit code, 핵심 count와 failure를 Outcomes에
기록한다.

## Documentation Updates

ADR index, Business Policy, API conventions/error candidates, capability map, support overview/query/frontend controls,
ExecPlan index, traceability, operations/testing planning 문서와 repository audit를 함께 갱신한다.

## Progress

- [x] P0/P1 conflict와 current repository evidence 재감사
- [x] canonical target OpenAPI, ADR-070과 verifier의 speculative cursor 변경 원복
- [x] ADR-090 Proposed, ADR-091 Rejected와 SP-14 정합화
- [x] 55-operation DRAFT inventory 작성
- [x] S10 detailed plan과 program orchestration 작성, placeholder plan 제거
- [x] traceability/runbook/test/audit/index 정합화
- [x] final validation 실행, 결과 기록과 completed 이동

## Surprises & Discoveries

- planning pack의 4개 원본 파일은 현재 worktree와 temporary roots에 없었다. 이 repair는 사용자 correction,
  기존 236-row traceability와 current 51-operation skeleton을 대조하고 누락 4개를 복원한다.
- active/ready Analytics metadata와 `Migration lane released`는 실제 execution/lease evidence가 아니다.
- 현재 worktree는 `main` commit과 같은 base지만 branch 이름은 `refactor/check-repo`다.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-10 | Accepted repair | 55개 surface를 canonical OpenAPI가 아닌 DRAFT inventory로 보존 | generic schema와 owner model gap | support-api-surface.md |
| 2026-08-10 | Proposed | Support Console frontend/trust boundary | credential/CORS/CSRF/deployment 미결정 | ADR-090 |
| 2026-08-10 | Rejected | queue priority를 ExecPlan dependency로 표현 | ADR-072 direct-input 의미 보존 | ADR-091 |
| 2026-08-10 | Plan boundary | S10 하나만 detailed Support implementation plan으로 유지 | placeholder 자동 실행 방지 | program orchestration |

## Outcomes & Retrospective

Accepted에는 SP-01~06, SP-08~13, SP-15와 Support Console 최종 scope만 남겼다. SP-07 numeric band는
Initial assumption, frontend boundary/ADR-083/error candidates는 Proposed, 55개 endpoint는 DRAFT, owner/KMS/
Provider/model gap은 Blocked로 분류했다. Canonical target/runtime OpenAPI는 Support planning operation 없이
34 paths/37 operations를 유지하며 semantic Support implementation contract는 0/55다.

Document/manual validation은 통과했다. initial audit에서 기록한 PaymentMethod cursor failure는 final Base64URL
character mutation이 padding bit만 바꿔 같은 HMAC bytes로 decode될 수 있었던 test defect였다. 해당 test는
signature first character를 반드시 바꾸는 existing helper로 안정화했다. 2026-08-10~11 baseline validation은
`for attempt in {1..10}; do ./gradlew test --tests '*PaymentMethodControllerIntegrationTest' --rerun-tasks || exit $?; done`
exit 0 (10회 연속 성공)이다. Docker Desktop daemon이 꺼진 상태의 최초 `./gradlew spotlessCheck test --rerun-tasks`는
608 tests/405 failures와 exit 1이었고, `docker info`도 socket 부재로 exit 1이었다. 이는 통과로 처리하지 않았다.
Docker를 시작한 뒤 `for attempt in {1..2}; do ./gradlew spotlessCheck test --rerun-tasks || exit $?; done`는 exit 0
(각각 7m 29s, 7m 3s), `./gradlew test --tests '*ModularityTests' --rerun-tasks` exit 0,
`./scripts/verify-docs.sh` exit 0 및 `git diff --check` exit 0이다. docs verifier는 target/runtime 34 paths/37
operations, 91 schemas, 33 business policies, 91 ADRs, 223 Markdown files, 35 ExecPlans를 검증했다.

따라서 S10은 implementation-ready이지만 구현 시작, migration lease 획득 또는 번호 예약은 일어나지 않았다.
S00은 application correctness가 아니라 planning repair가 완료됐다는 뜻이다.

## Revision Notes

- 2026-08-10: prior completed claim을 되돌리고 audit/repair orchestration으로 재작성.
- 2026-08-10: corrections와 독립 validation 결과를 기록하고 completed로 이동; full-suite failure는 미해결로 보존.
- 2026-08-11: deterministic cursor tampering test와 complete baseline regression을 재검증해 S10 readiness gate를 통과.
