# S100 R0-R4 목적별 owner profile change를 구현한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s30-protected-profile-search.md`, `docs/exec-plans/completed/customer-support-s60-approval-operations-investigation.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. 구현 중 `Progress`, `Surprises & Discoveries`, `Decision Log`,
`Outcomes & Retrospective`를 실제 결과로 갱신하는 living document다.

## Purpose / Big Picture

고객·매장·Delivery 외부 courier 프로필을 R0-R4 closed field policy로 분류하고 범용 PATCH 없이 목적별 command로
변경한다. R1/R2 direct owner command와 R3/R4 reset intent의 exact S60 dual-approval 실행을 제공하되 Support에는 raw
PII/secret을 저장하지 않는다. 변경 owner transaction과 PII-free Audit은 원자적이고, 그 뒤 old/new channel
notification은 독립적으로 retry/manual review에 수렴한다.

## Current State

- branch `feature/support-purpose-specific-profile-change`는 S90 verified head
  `20a82ec7606a3faf7e06858145f0b2e661b6bb23`에서 분기했다. parent PR은 #61이다.
- Flyway inventory는 V1-V47이며 마지막은 `V47__create_versioned_goodwill_compensation.sql`이다.
- S30은 Identity customer, Merchant store, Delivery external courier의 owner-local Vault ciphertext/masked
  derivative/exact index와 query/reveal public API를 소유한다.
- S60은 digest-only immutable revision, manager/Operations approval, required callback과 reassignment를 구현했으며
  S100 typed owner command가 execution 직전 digest/version을 재검산해야 한다.
- Accepted SP-22/ADR-087 amendment가 initial 3-owner R0-R4 mapping, new-phone-only denial, reset-intent-only R4와
  old/new notification boundary를 확정했다.
- active Analytics migration plan은 2026-08-12 Support S100 completion까지 deferred됐고 branch/number/PR을 소유하지
  않는다. Productization migration stack은 PR #57에서 동결됐다. S90 V47 lease는 release됐으며 current branch가
  2026-08-13 sole writer lease를 획득해 V48을 선택한다.

## Definitions

- **Profile field:** owner+purpose에 의해 하나의 risk class와 typed validation으로 닫힌 field.
- **Direct correction:** R1/R2에서 approval lineage 없이 current verification/permission/version을 실행 시 확인하는 command.
- **Profile change request:** R3/R4 purpose, owner subject, exact digest/version과 S60 action request를 연결하는 Support root.
- **Reset intent:** raw secret 없이 owner가 생성하는 credential reset 또는 provider re-registration instruction.
- **Notification target snapshot:** owner history의 exact change와 OLD/NEW/CURRENT target을 가리키는 PII-free reference.

## Scope

### In Scope

- SP-22 closed field/risk/purpose policy and typed domain classifier
- R1 customer/courier display and store public-profile corrections
- R2 customer legal-name typo, store operations contact and courier relay-contact corrections
- R3 customer primary phone, store representative/settlement reference and courier identity/payout reference request/execution
- R4 customer/store/courier reset/re-registration intent, never raw secret
- S60 `PROFILE_CHANGE` exact revision/dual approval/Operations/reassignment integration
- owner-local encryption/index/masked derivative/version/history/reset intent commands
- old/new/current notification snapshots, durable delivery/retry/manual-review and Support warning state
- V48 constraints, purpose-specific target/runtime OpenAPI, security/architecture/PII tests and documentation

### Non-goals

- generic JSON PATCH, dynamic rules engine or arbitrary field names
- password/OTP/MFA secret/token/key/PAN/CVC/raw bank account storage or reveal
- first-party Rider workforce, dispatch/provider webhook lifecycle or external KYC claim
- Notification/Vault fake/no-op production fallback, auto reassignment or reviewer execution

## Business Rules and Invariants

1. R0 fields have no change command. Unknown owner/purpose/field combination is default deny.
2. R1 requires BASIC/current assignment/active subject link/R1 permission; R2 requires ENHANCED and specialist R2 permission.
3. R3/R4 request requires ENHANCED and R3 request permission; route is always `SUPPORT_MANAGER_THEN_OPERATIONS`.
4. R3/R4 raw payload is transient. Support persists only canonical digest, masked summaries and opaque owner references.
5. Execution recomputes exact digest, rechecks current owner version/session/permission/assignment and consumes approval once.
6. Requester differs from both reviewers, reviewers differ, reviewer cannot execute; inactive executor requires explicit reassignment.
7. Customer primary-phone requires current registered-channel-bound ENHANCED verification; new phone alone is insufficient.
8. Owner public Application API alone writes profile/history/reset tables and maintains ciphertext/index/masked tuples atomically.
9. Audit commit failure rolls back owner and Support state. Notification starts only after confirmed profile commit.
10. Channel changes create OLD and NEW target intents; notification failure is visible and never rolls back the profile.

## Architecture and Transaction Boundaries

Controller calls only `SupportProfileChangeApplicationService`. A preflight transaction locks Case, active subject link,
VerificationSession and persistent permission and obtains the current owner version through a public owner API. Vault encrypt/HMAC
preparation occurs outside a DB transaction. A final Support transaction repeats every authorization/version/digest check, invokes
the owner public write API with `MANDATORY` propagation, commits owner current row/history/index, Support result/S60 consumption,
idempotency and PII-free Audit atomically.

R3/R4 creation does not call Vault or persist raw payload. It creates `ProfileChangeRequest`, S60 action request/revision and
Operations handoff as one local transaction. S60 manager/Operations decisions resolve latest owner version through the typed S100
resolver and cannot treat lookup failure as stale/success.

After profile commit, a `REQUIRES_NEW` Notification owner transaction creates exact target intent(s). Worker claim/result DB
transactions surround owner snapshot decrypt and Provider call, both outside DB transactions. Failed or unknown delivery remains
retryable/manual-review and no fallback target is selected.

## Failure Semantics

- malformed/unknown field, raw secret, invalid normalization: 400 without echo;
- missing/revoked permission, Case/subject/assignment mismatch: 403;
- missing profile/request: 404;
- stale owner/revision/digest/session, self/dual-role, new-phone-only, duplicate/concurrent winner: 409 closed code;
- Vault, owner DB, Audit, required callback or notification persistence dependency failure: 503;
- notification Provider failure after profile commit: profile success with explicit warning/retry state, never rollback/fake success.

## Data and Migration

V48 adds missing owner fields and append-only profile history/reset intents; Support profile request/direct execution/idempotency and
notification status; S60 `PROFILE_CHANGE` action/target/terminal binding; Notification profile target metadata/template values and
constraints. Existing V1-V47 remain immutable.

### Migration-writer lease evidence

- **Acquired:** 2026-08-13 by `feature/support-purpose-specific-profile-change` immediately after branching from clean S90 head.
- **Base:** local/remote parent S90 head `20a82ec7606a3faf7e06858145f0b2e661b6bb23`; parent PR #61 targets S80.
- **Inventory:** current migration inventory ends at V47. Completed S90 released V47. Active Analytics explicitly owns no
  branch/number/PR until S100 completion; Productization is frozen; no other current worktree records an acquired lease.
- **Selection:** sole S100 writer selects `V48__create_purpose_specific_profile_change.sql` without reservation, checksum repair,
  renumbering or modification of applied migrations.
- **Release:** only after focused/full/build/docs validation and atomic completed-plan/successor handoff.

## API Contracts

Purpose-specific R1/R2 correction paths accept one closed typed payload, expected profile version, verification session and reason.
Purpose-specific R3/R4 request paths accept the same typed payload transiently plus evidence digest and create a digest-only resource.
Matching execution paths require exact request/revision/profile versions and the same typed payload. Existing S60 manager,
Operations and reassignment paths remain the only approval/reassignment APIs. All writes require `Idempotency-Key`, reject unknown
properties and return `Cache-Control: no-store` without raw old/new PII or secret.

## Milestones

1. Record SP-22/ADR-087/API contract, active plan and V48 lease; pass docs validation.
2. RED-GREEN risk classifier/ProfileChange aggregate and V48 constraints.
3. RED-GREEN owner preparation/write/history/reset public APIs and stale/concurrency tests.
4. RED-GREEN Support direct/R3/S60 orchestration, Audit rollback and reassignment.
5. RED-GREEN old/new notification targets/retry and purpose-specific Controllers/OpenAPI.
6. Full validation, security/diff review, completed move, V48 release and S110 readiness handoff.

## Required Tests

- risk-class/purpose matrix and R0/unknown/R4 raw-secret denial
- R1 BASIC/R2 ENHANCED permission and Case/subject mismatch
- new-phone-only denial and current-channel ENHANCED acceptance
- self/same-reviewer/reviewer-execution denial, concurrent approval and explicit reassignment
- exact payload/revision/profile-version stale and concurrent execution single winner
- owner encryption/index/history atomicity and Audit failure rollback
- old/new notification target creation, post-commit failure, retry/manual review and no target fallback
- V48 PostgreSQL checks/unique/FK/append-only, strict no-store OpenAPI, runtime parity, Modulith/ArchUnit and PII canary

## Validation Commands

- `./scripts/verify-docs.sh`
- `./gradlew test --tests '*SupportProfileChange*' --tests '*ProfileFieldRisk*'`
- `./gradlew test --tests '*OwnerProfileChange*' --tests '*ProfileChangeNotification*'`
- `./gradlew test --tests '*ProfileChangeMigrationTest' --tests '*ProfileChangeOpenApiContractTest'`
- `./gradlew test --tests '*SupportArchitectureTest' --tests '*ModularityTests' --tests '*RuntimeOpenApiParityTest'`
- `./gradlew --no-daemon spotlessCheck test`
- `./gradlew --no-daemon build`
- `./scripts/verify-docs.sh`
- `git diff --check`
- `git status --short`

## Observability

Only owner type, risk class, purpose, closed state/outcome/failure class and duration may be metric labels. Actor/Case/subject/request
IDs, reason, digest, masked/raw value, ciphertext, account/provider reference or notification destination are forbidden in logs,
metric labels, Audit summaries and snapshots.

## Documentation Updates

SP-22, ADR-087, profile policy/classification, approval controls, threat model, transaction boundaries, API surface, error contract,
traceability, orchestration and this living plan follow actual implementation. Completion atomically moves this plan to completed,
updates direct successor S110 dependency/readiness and releases V48.

## Progress

- [x] mandatory docs/current branch/schema/PR/worktree inspected
- [x] user selected the recommended complete 3-owner field mapping
- [x] no Accepted ADR conflict; DRAFT field/model gap resolved by SP-22/ADR-087 amendment
- [x] S100 stacked branch and sole V48 migration-writer lease acquired
- [ ] domain/V48 RED-GREEN slice
- [ ] owner write/history/reset slice
- [ ] Support/S60 orchestration and Audit slice
- [ ] Notification/API/OpenAPI/security slice
- [ ] focused/full/build/docs validation
- [ ] completion move, V48 release and S110 readiness handoff

## Surprises & Discoveries

S30 already provides all three owner-local protected profile roots, including Delivery external courier, so S100 does not need to
invent a first-party Rider Aggregate. The missing legal/payout/history/reset fields are explicit owner-model extensions. S60's
completed contract intentionally stores only action payload digests and names S100 as the typed owner that recomputes them, so raw
payload re-submission at execution is the existing architecture rather than a new fallback.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-13 | Accepted by user | complete 3-owner initial R0-R4 mapping | fulfills S100 without hiding R2/courier/R4 gaps | SP-22, ADR-087 |
| 2026-08-13 | Security boundary | digest-only R3 request and typed raw re-submission at execution | owner-local PII and exact S60 approval | ADR-084/087, this plan |
| 2026-08-13 | Owner scope | external courier is the Rider support subject | preserves SP-15/ADR-088 non-goal | SP-22, ADR-087 |
| 2026-08-13 | Migration lease | current branch owns sole V48 lane | V47 released and other writers deferred/frozen | this plan |

## Outcomes & Retrospective

Implementation and validation are in progress. No runtime endpoint, migration validation, production provisioning, performance,
legal compliance or delivery success is claimed yet.
