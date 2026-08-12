# PR #63 S100 review remediation을 완료한다

> **Status:** `ACTIVE`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/customer-support-s100-purpose-specific-profile-change.md`
> **Completed-At:** `—`

이 ExecPlan은 `.agent/PLANS.md`를 따른다. PR #63의 6개 unresolved review finding을 회귀 테스트로 재현하고,
S100의 보안·장애 복구·API 계약을 보강한 뒤에만 다시 completion을 선언한다.

## Purpose / Big Picture

승인된 R3/R4 실행 직전에 requester와 verification의 전체 authorization binding을 다시 검증하고, profile 변경 뒤
Notification delivery commit과 Support acknowledgement 사이의 장애를 durable claim/lease reconciliation으로 복구한다.
동시에 owner 404, nullable composite request 400, canonical digest 충돌을 닫는다.

## Scope and Invariants

- final owner-write transaction은 active Case subject link, requester permission, session actor/case/subject/link/purpose/scope/
  ENHANCED, primary-phone registered-channel challenge를 모두 다시 확인한다.
- profile owner write는 이미 성공한 idempotency replay/reconciliation에서 반복하지 않는다.
- notification line은 immutable source timestamp와 stable initial correlation을 보존한다.
- expired claim은 다시 획득할 수 있고, Notification logical-source uniqueness로 기존 delivery ID에 재결합한다.
- request-scoped retry correlation은 동일 logical source의 semantic equality 조건이 아니다.
- owner row 부재만 404이며 다른 DB 장애는 503으로 유지한다.
- nullable composite payload는 하나 이상의 nonblank field가 있어야 한다.
- digest framing은 null/present/type/length를 구분한다.

## Architecture and Transaction Boundaries

Support final execution transaction이 permission, Case/link, VerificationSession/challenge, approval revision, owner version,
payload digest를 재검증하고 owner `MANDATORY` write, S60 consume, Audit을 원자적으로 commit한다. Notification dispatch는
별도 짧은 transaction에서 line을 claim하고, 외부 Notification owner 호출 뒤 별도 transaction에서 accept/fail한다.
lease 만료 후 reconciliation은 같은 logical source로 기존 delivery를 조회·재결합한다.

## Data and Migration

`V49__harden_support_profile_notification_recovery.sql`은 기존 V48 line에 immutable source timestamp, stable initial
correlation, claim owner/expiry를 추가하고 상태 constraint를 `PROCESSING`까지 확장한다. V1~V48은 수정하지 않는다.

### Migration-writer lease evidence

- **Acquired:** 2026-08-13 by local remediation branch `fix/s100-review-remediation` for PR #63.
- **Base/head:** `origin/feature/support-purpose-specific-profile-change` at `a6c6f8fd915e17fd6bcbe59edc5859327f5e8037`.
- **Inventory:** current branch and current main-derived PR end at V48. Active Analytics explicitly defers acquisition and owns
  no branch, number or PR; Support orchestration has `Writes-Migration=false`; no other active plan records an acquired lease.
- **Selection:** sole writer selects V49. No reservation manifest, checksum repair, renumbering or applied migration edit.
- **Release:** only after focused/full/build/docs validation and push of the reviewed remediation.

## Failure Semantics

Revoked permission/link/challenge or mismatched verification fails closed before owner write. Lost/expired notification claims remain
recoverable and visible; provider outcome is never inferred. Invalid client payload is 400, missing owner profile is 404, and actual
database dependency failure remains 503. No raw PII, verification proof, notification destination or secret is logged/audited.

## Test and Validation Plan

- RED: post-approval link unlink, requester permission revoke and registered-channel challenge invalidation.
- RED: delivery commit then Support accept failure, expired claim recovery, stable payload with new HTTP correlation, no duplicate
  owner/provider effect, and concurrent retry state monotonicity.
- RED: customer/store/courier missing owner 404; composite all-null/blank 400; nullable digest collision.
- PostgreSQL V49 constraint/backfill tests, focused workflow/owner/API tests, Modulith/ArchUnit, full spotless/test/build, docs,
  OpenAPI/runtime parity and diff checks.

## Progress

- [x] PR head, six authoritative review threads, mandatory docs and current schema inspected
- [x] all six findings accepted as valid; no Accepted ADR/policy conflict found
- [x] sole V49 migration-writer lease acquired
- [x] RED regression tests
- [x] security/API/digest fixes
- [x] notification claim/lease recovery and reconciliation
- [x] focused/full/build/docs validation
- [ ] push, thread replies/resolution, completion move and lease release

## Decision Log

| Date | Status | Decision | Rationale |
|---|---|---|---|
| 2026-08-13 | Accepted review remediation | fix all six PR #63 findings before merge | each finding affects a documented security, durability, idempotency or API invariant |
| 2026-08-13 | Migration lease | add one forward-only V49 | durable claim/lease cannot be represented safely by mutable aggregate timestamps alone |

## Outcomes & Retrospective

Final execution now locks and repeats the requester permission, active subject link, full ENHANCED session binding and registered
channel challenge checks. Nullable digests use typed binary framing, composite API payloads reject null/blank requests with 400, and
owner absence alone maps to 404. V49 adds immutable notification source identity plus a two-minute exclusive claim lease; scheduled
recovery and terminal command replay rejoin an already committed Notification delivery without repeating the owner write.

Validation evidence before push:

- focused profile/owner/migration/Notification/OpenAPI regression — `BUILD SUCCESSFUL` in 54s;
- Support Architecture, Modulith and runtime OpenAPI parity — `BUILD SUCCESSFUL` in 17s;
- first full `spotlessCheck test` — 941 tests, only two expected-latest-migration assertions failed after V49;
- both inventory tests after update — `BUILD SUCCESSFUL` in 31s;
- final full `spotlessCheck test` — `BUILD SUCCESSFUL` in 11m 48s; 941 tests, 0 failures, 0 errors, 1 skipped;
- final `build` — `BUILD SUCCESSFUL` in 8s;
- docs/OpenAPI — 107 paths/111 operations/244 schemas; 33 policies, 92 ADRs, 239 Markdown files and 45 ExecPlans.

## Revision Notes

- 2026-08-13: opened from PR #63 unresolved review findings and acquired the sole V49 writer lease.
- 2026-08-13: implemented all six remediations and passed focused, architecture, full regression, build and documentation gates.
