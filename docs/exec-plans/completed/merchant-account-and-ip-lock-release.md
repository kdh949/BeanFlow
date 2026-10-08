# 점주 계정 잠금과 연결된 IP 제한을 한 번에 해제

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** —
> **Completed-At:** `2026-10-01`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

운영자의 기존 점주 잠금 해제 명령으로 계정과 관련 IP 차단을 함께 해제하여 정상 로그인을 복구한다.

## Current State

account와 LOGIN_ID만 지워 IP 429가 남는다. ID/IP 연결은 없다. main/origin/main/remote main은
`ec6172959dcebdd55519d44babea9e80b13d27b4`, 마지막 migration은 V93이다. 열린 PR 199~202와
존재하는 worktree의 migration 변경은 없다. 단일 writer lease는
`/private/tmp/beanflow-migration-writer-lease/owner`에 획득했으며 통합 또는 명시적 취소까지 유지한다.
작업은 사용자가 승인한 로컬 변경이며 commit/push/PR/deploy를 포함하지 않는다.

## Definitions

LOGIN_ID는 actor별 canonical 사용자명 HMAC 제한, IP는 actor별 source IP HMAC 제한이다.
source 연결은 점주 ID HMAC과 IP HMAC 및 마지막 로그인 시도 시각이다.

## Scope

### In Scope

Identity attempt 연결·보존·해제, Merchant 로그인 잠금 순서, Operations 감사 count, OpenAPI와 테스트.

### Non-goals

고객 제한 해제, 비밀번호 초기화 범위 확장, UI 입력 추가, 임계값·Session 정책 변경과 배포.

## Business Rules and Invariants

[BR-35](../../product/business-policy-decisions.md)와 [ADR-139](../../adr/ADR-139-merchant-account-and-ip-lock-release.md)를 적용한다.
현재 창의 관측된 연결만 해제하며 CUSTOMER와 무관한 IP를 유지한다. IP 원문·HMAC을 Audit에 남기지 않는다.

## Architecture and Transaction Boundaries

Operations가 권한·reason·idempotency·Audit와 Identity MANDATORY port를 같은 DB transaction으로 묶는다.
Merchant 로그인과 해제는 account row 먼저, 그 뒤 IP·LOGIN_ID를 잠근다. hash 검증은 transaction 밖이다.

## Alternatives Considered

전역 IP 삭제, IP 수동 입력, 최근 IP 하나와 ID/IP별 제한은 ADR-139의 이유로 제외한다.

## Failure Semantics

필수 저장 실패는 503·rollback이며 Audit 실패 시 일부 해제를 남기지 않는다. retention은 throw·metric·log와
다음 scheduled run의 재시도를 유지한다. legacy 연결을 추정하거나 제한을 우회하지 않는다.

## Data and Migration

V94에 MERCHANT/IP 제약과 IP attempt composite FK cascade를 갖는 source table, PK와 retention index를 추가한다.
24시간 bounded purge를 추가한다. 기존 applied migration 변경과 근거 없는 backfill은 없다.

## API and Event Contracts

`POST /operations/merchant-accounts/{merchantAccountId}/lock-releases`의 reason/key/204를 유지하고 description을 갱신한다.
Operations-owned port result에 해제 count를 추가하며 Audit summary에만 사용한다. 새 event는 없다.

## Milestones

1. 정책·ADR·계획과 migration lane 확인.
2. source persistence, 계정 우선 잠금, 원자 해제와 Audit 구현.
3. 정상·격리·실패·replay·동시성·보존 테스트와 문서/정적/구조 검증.

## Required Tests

실제 실패/IP 차단 후 해제·로그인, shared IP·복수 IP, 고객·무관한 IP·이전 창 보존, legacy 연결 없음,
올바른 비밀번호 차단 연결, Audit rollback, replay 재차단 보존, 동시 시도, 24시간 bounded purge/cascade.

## Validation Commands

- `./gradlew test --tests '*MerchantCredentialAdministrationIntegrationTest' --tests '*MerchantAuthenticationIntegrationTest' --tests '*CustomerAuthenticationIntegrationTest' --tests '*CustomerCredentialSecurityTest' --tests '*MerchantCredentialAdministrationApplicationServiceTest' --tests '*AuthenticationArchUnitTest' --tests '*ModularityTests' --tests '*RuntimeOpenApiParityTest'`
- `./gradlew spotlessCheck compileKotlin compileTestKotlin`
- `./gradlew bootJar`
- `bash scripts/verify-docs.sh`
- `git diff --check`

## Observability

기존 operations credential metric과 Audit count, source retention count·failure metric을 사용한다.

## Documentation Updates

BR-35 amendment, ADR-139, ADR-093 superseded scope와 index, OpenAPI description, 이 계획의 evidence.

## Progress

- [x] 정책·ADR·계획과 단일 migration writer preflight.
- [x] 구현과 회귀 테스트.
- [x] 실제 검증과 diff 검토.

## Surprises & Discoveries

- 기존 ID/IP 집계로 연결을 복원할 수 없어 additive schema가 필요하다.
- 기존 Merchant 로그인 attempt-first와 관리자 account-first 순서를 통일해야 동시 실패와 해제가 안전하다.
- Audit summary는 JSON을 담은 text 컬럼이다. 첫 검증의 4개 실패는 검증 쿼리에서 jsonb cast를 누락한
  결과였으며 테스트 쿼리를 수정한 뒤 전체 대상 61개를 다시 실행해 통과했다.

## Decision Log

- 2026-10-01: 사용자 요청에 따라 현재 관측된 merchant IP 제한을 원자 해제한다. 상세 trade-off는 ADR-139에 기록했다.

## Outcomes & Retrospective

기존 운영 화면의 reason 입력·잠금 해제 버튼과 API의 204 형식을 유지하면서 계정·LOGIN_ID·현재 창에
연결된 MERCHANT IP 제한을 같은 transaction에서 해제한다. 두 IP의 동시 계정 해제에서 IP 제한 삭제
count 합계가 2이고 두 명령은 모두 204였다. Audit 실패는 모든 제한과 source 삭제를 rollback하며
같은 key replay는 재생성된 제한을 지우지 않는다. source insert 실패는 503이고 attempt write도 rollback한다.

### Passed

- 대상 Gradle 61 tests: Operations Application 2, Operations 통합 17, Merchant 인증 10,
  Customer 인증 17, credential security 10, Authentication ArchUnit 3, Modulith 1, Runtime OpenAPI 1.
- 실제 HTTP 30회 실패 후 IP 429 → 조기 해제 → 정상 로그인, correct-password 차단 연결과 복수 IP.
- 공유 IP·고객 actor·무관한 IP·이전 창·legacy 연결 없음·만료 창의 해제 범위.
- 동시 실패 commit 대기와 공유 IP 두 계정 동시 해제, Audit rollback, replay, source 저장 실패,
  active shared IP의 source 24시간 bounded purge와 IP 삭제 cascade.
- `spotlessCheck`, production/test Kotlin compile, `bootJar`.
- 문서 검증 unit 18 tests와 OpenAPI YAML/semantic checks, 58 정책·139 ADR·974 Markdown·116 ExecPlans.
- 최종 diff 검토와 `git diff --check`. 기존 token-refresh·telemetry·load 변경은 보존했다.

### Not run

전체 backend suite, 이 변경의 UI/Storybook 재실행, 공유 DB V94 적용과 production 배포·실계정 smoke.
UI source는 이번 변경에서 수정하지 않았다. 기존 IP에는 연결 정보가 없으므로 배포 후 새 로그인 시도가
있어야 통합 해제할 수 있다. migration-writer lease는 V94의 통합 또는 명시적 취소까지 유지한다.

## Revision Notes

- 2026-10-01: 승인된 통합 해제 동작과 검증 계획 작성.
- 2026-10-01: V94·원자 해제·감사 count와 61 tests·빌드 검증을 완료하고 completed로 이동.

- 2026-10-09: 최신 main `ec6172959dcebdd55519d44babea9e80b13d27b4`에서 인증 변경만 분리해 PR 제출을 준비한다. V94는 이 PR의 선행 migration이며 별도 로컬 V95는 포함하지 않는다. 배포·공유 DB 적용은 수행하지 않는다.


### PR 재검증 (2026-10-09)

- 최신 main의 마지막 migration은 V93이며 열린 PR 13개에는 migration file 변경이 없었다.
  이 PR은 이미 구현한 V94만 포함한다. 별도 로컬 검색 migration V95의 통합 순서는 V94 뒤다.
- Passed: 통합 해제/인증/구조/계약 61 tests와 기존 session filter·coordinator 9 tests, 총 70.
- Passed: `spotlessCheck`, `bootJar`, 문서 unit 18와 OpenAPI/정책/ADR/ExecPlan 검증.
- 프런트엔드 생성 schema의 잠금 해제 description을 OpenAPI와 동기화했다. API 형태는 유지한다.
- Not run: 전체 backend suite, 공유 DB migration 적용·배포·실계정 smoke.
- 원격 CI는 PR 생성 후 확인하며 로컬 결과로 대체하지 않는다.
