# S40 purpose-bound verification과 DataAccessGrant reveal을 구현한다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s10-retention-audit-permission.md`, `docs/exec-plans/completed/customer-support-s20-case-foundation.md`, `docs/exec-plans/completed/customer-support-s30-protected-profile-search.md`
> **Completed-At:** `2026-08-12`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

상담원은 masked search로 대상을 찾은 뒤 Case+Subject+Purpose-bound BASIC/ENHANCED verification을 수행하고,
field/time/count-bound Grant와 committed PII Audit가 있을 때만 S30 owner profile 원문을 열람한다. Emergency
break-glass는 일반 verification/grant와 다른 request/review/reveal path다.

## Current State

- latest `origin/main`과 branch base는 `d8db63089a1d61a13069ab352819bc9479e4faa2`다.
- implementation preflight에서 last Flyway는 S30의 `V41__create_protected_support_profiles_and_search_guard.sql`이었고,
  S40이 `V42__create_support_verification_and_data_access_grant.sql`을 추가했다.
- S10은 Operations PII Audit와 dormant Support permission vocabulary, S20은 Case/subject link/terminal lifecycle,
  S30은 Identity/Merchant/Delivery owner-local Vault profile과 masked search를 구현했다.
- V42가 VerificationSession/challenge/attempt/lockout, DataAccessGrant/reveal attempt, break-glass review,
  security notification과 scoped idempotency를 추가했다.
- Support는 12개 S40 operation을 target/runtime OpenAPI와 Spring MVC에 노출한다. 전체 runtime Support surface는
  S20 9개, S30 1개, S40 12개로 총 22개다.
- Identity/Merchant/Delivery public reveal API는 ciphertext 조회만 owner transaction에서 수행하고 decrypt는
  transaction 밖에서 수행한다. Support는 owner Repository/table을 직접 읽지 않는다.
- challenge Provider 중단은 expiry 뒤 recovery worker가 explicit unknown으로 종결하고, break-glass notification의
  stale `PROCESSING` claim은 5분 뒤 재회수한다.
- 사용자 소유 productization 문서 변경은 branch에 보존하고 S40 diff와 섞어 수정하지 않는다.
- migration-writer lease는 `feature/support-verification-data-access-grant`가
  `2026-08-11T23:40:02+09:00`에 base `d8db630`, last Flyway V41을 확인하고 획득했다. open PR은 0개이며
  inventory에 다른 current migration writer evidence가 없다. 이 evidence와 explicit acquisition이 함께 lease를
  구성했고 S40은 V42를 사용했다. full validation 완료와 함께 lease를 해제했다.

## Definitions

- **Verification level:** `UNVERIFIED`, `BASIC_VERIFIED`, `ENHANCED_VERIFIED`; BREAK_GLASS는 level이 아니다.
- **Opaque challenge:** secret을 Support가 생성·저장하지 않고 Provider reference/outcome만 보존하는 challenge.
- **Reveal budget:** Audit가 commit된 reveal attempt가 소비하는 Grant별 maximum count.
- **Access path:** `STANDARD_GRANT` 또는 separate `BREAK_GLASS`.

## Scope

### In Scope

- VerificationSession/challenge/attempt/lockout domain and persistence
- Challenge issue/verify ports, local/test scripted adapter, production fail-fast configuration
- DataAccessGrant request/approval/revoke/reveal and Case-close atomic revocation
- owner-local field reveal APIs for S30 Identity/Merchant/Delivery profiles
- break-glass request/pre-review/reveal/post-review and PII-free notification intent
- V42 constraints/indexes/permission/audit mappings, target/runtime OpenAPI and exact tests

### Non-goals

- 상용 KYC claim, 실제 SMS/email/in-app Provider onboarding, frontend, profile mutation, bulk export
- R4 secret reveal, Support-owned PII copy, cache/stale/local production fallback

## Business Rules and Invariants

ADR-106과 SP-18을 canonical initial policy로 사용한다. Session은 Case+active subject link+purpose+action+level에
bind하고 BASIC은 ENHANCED 작업에 사용할 수 없다. OTP/link/proof는 저장하지 않는다. Grant 없이는 standard raw
reveal이 없고 Audit commit 전 또는 Case close 뒤 raw reveal이 없다. Other Case/Subject/Purpose/actor reuse는 403/409로
fail closed한다.

## Architecture and Transaction Boundaries

Controller는 Application Service만 호출한다. Challenge Tx1 intent/claim, Provider call, Tx2 result를 분리한다.
Reveal은 TxR1 budget reservation+Audit commit, owner public API/Vault call, TxR2 result commit 뒤 response 순서다.
Support는 owner Repository/table을 읽지 않는다. Case close transaction이 Support-owned sessions/grants/break-glass를
revoke한다. Notification Provider call은 durable intent commit 뒤 worker transaction 밖에서 수행한다.

## Alternatives Considered

ADR-106의 provider-owned secret, two-phase reveal와 separate break-glass alternatives를 따른다.

## Failure Semantics

Provider timeout/malformed/ACK loss는 UNKNOWN, permission/DB/Audit/Vault failure는 503이다. Invalid proof만 failed
attempt를 소비한다. Audit가 commit된 reveal attempt는 downstream failure에도 budget을 소비한다. No fallback,
empty success, cached/stale reveal or exception swallowing.

## Data and Migration

V42는 verification session/challenge/attempt/lockout, data access grant/field/approval/reveal attempt,
break-glass request/review, security notification intent와 필요한 unique/check/FK/index를 추가한다. V1~V41은 수정하지
않는다. UUID references로 Aggregate를 연결하고 bidirectional/@ManyToMany를 만들지 않는다.

## API and Event Contracts

S40 12 operations을 endpoint-specific schema로 target/runtime OpenAPI에 승격했다. 모든 mutating endpoint는
scoped Idempotency-Key를 요구하고 sensitive success에는
`Cache-Control: no-store`를 설정한다. Raw field values는 closed field-keyed object로만 반환한다.

## Milestones

1. SP-18/ADR-106/ExecPlan과 OpenAPI contract를 확정한다.
2. Domain tests를 RED로 작성하고 VerificationSession/Grant/break-glass Aggregate를 구현한다.
3. V42와 PostgreSQL persistence/concurrency tests를 구현한다.
4. challenge provider and owner reveal/notification public boundaries를 구현한다.
5. Application Service/Controller/OpenAPI/API security tests를 구현한다.
6. Case-close revoke, audit/commit/Vault failures and full validation을 완료한다.

## Required Tests

- `VerificationSessionTest`, `DataAccessGrantTest`, `BreakGlassRequestTest`
- `SupportVerificationMigrationTest`, `SupportVerificationIntegrationTest`의 PostgreSQL/concurrency scenarios
- `SupportVerificationOpenApiContractTest`, `SupportVerificationPiiLeakTest`
- owner reveal/Audit failure, notification retry/manual-review, Case closure races
- `SupportArchitectureTest`, `ModularityTests`, `RuntimeOpenApiParityTest`

## Validation Commands

- `./gradlew test --tests '*VerificationSessionTest' --tests '*DataAccessGrantTest' --tests '*BreakGlassRequestTest'`
- `./gradlew test --tests '*SupportVerificationMigrationTest' --tests '*SupportVerificationIntegrationTest'`
- `./gradlew test --tests '*SupportVerificationOpenApiContractTest' --tests '*SupportVerificationPiiLeakTest'`
- `./gradlew test --tests '*SupportArchitectureTest' --tests '*ModularityTests' --tests '*RuntimeOpenApiParityTest'`
- `./gradlew spotlessCheck test`
- `./gradlew build`
- `./scripts/verify-docs.sh`
- `git diff --check`

## Observability

PII-free counters for challenge issue/verify outcome, lockout, Grant decision/reveal outcome, break-glass state and Audit
latency. ID, reason, proof, raw/ciphertext/digest/provider reference labels are forbidden.

## Documentation Updates

Business Policy SP-18, ADR-106/index, Support verification/API/aggregate/transaction/security/test/traceability documents,
OpenAPI and orchestration actual outcome/readiness.

## Progress

- [x] latest main/source and mandatory documents inspected
- [x] user selected strict initial S40 policy
- [x] S40 branch and sole migration-writer lease acquired; V42 selected
- [x] decision and living plan documentation validated
- [x] domain RED/GREEN slices
- [x] V42 persistence/concurrency slices
- [x] API/provider/owner reveal/break-glass slices
- [x] focused/full/document validation
- [x] completed move, direct successor/readiness atomic update and lease release

## Surprises & Discoveries

Current local main was one merge behind origin/main; S30 had already supplied the encrypted owner profile/Vault boundary
required for real raw reveal. User-owned ADR-092~105 files reserve those numbers, so S40 uses ADR-106.

Fresh review exposed five boundary details that the initial design needed to make explicit: Case-first lock ordering,
Case+Subject lockout independent of subject-link replacement, session ownership after reassignment, current
Case/subject/permission recheck after decrypt, and abandoned Provider/notification work recovery. The implementation,
ADR and tests now encode all five. Modulith verification also required moving the owner decrypt boundary from
`shared.internal` to the shared public API.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-11 | Accepted initial policy | strict 15m/5m/5-attempt/30m lock, bounded Grant and 2m break-glass | user selected recommended security envelope | SP-18, ADR-106 |
| 2026-08-11 | Architecture | provider-owned opaque secret and two-phase audit-gated reveal | no raw OTP storage and no long external-call transaction | ADR-106 |
| 2026-08-11 | Migration lease | branch owns V42 from latest origin/main V41 | ADR-072 execution-time evidence | this plan |
| 2026-08-12 | Completion | release V42 lease and hand completed S40 output to S50 readiness calculation | focused security, PostgreSQL, API, Modulith and 760-test full build passed | this plan, orchestration |

## Outcomes & Retrospective

S40 now provides purpose-bound BASIC/ENHANCED verification, field/time/count-bound standard Grant reveal and a distinct
break-glass request/approval/reveal/post-review path. Audit reservation commits before owner decrypt, result commit
rechecks current authorization, and no raw value leaves the controller before the successful result commit. Terminal
Case transitions revoke reusable authorization state atomically.

Validation evidence:

- focused domain/integration/migration/security suites passed after adding replay, relink lockout, reassignment,
  concurrency, expiry, audit failure, field scope, break-glass, stale work recovery and no-store coverage;
- `./gradlew compileTestKotlin --rerun-tasks` passed;
- `./gradlew spotlessCheck` passed after formatting;
- `./gradlew build` passed in 11m 27s with 760 tests, 0 failures, 0 errors and 1 skipped test;
- an initial full build found the `shared.internal` owner-decrypt Modulith dependency and stale V41 migration assertion;
  both were corrected and the exact failed tests plus the full build passed;
- `./scripts/verify-docs.sh` is validated against the committed S40 tree because unrelated concurrent productization
  documents in the shared working tree are intentionally excluded from this Stage;
- `git diff origin/main...HEAD --check` is the final branch whitespace gate.

The V42 migration-writer lease is released. S50 receives the completed S40 model/API outcome, but remains not ready:
its endpoint-specific signed cursor contract is still unresolved and no S50 implementation plan is authored in this
Stage.

## Revision Notes

- 2026-08-11: authored from latest origin/main after user accepted strict S40 security policy; acquired V42 lease.
- 2026-08-12: completed V42/domain/provider/reveal/break-glass implementation, recorded review remediations and full
  validation, released the V42 lease and recalculated only the direct S50 readiness input.
