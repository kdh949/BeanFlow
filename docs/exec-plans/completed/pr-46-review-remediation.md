# PR 46 거래 경계와 local demo 검증을 바로잡는다

> **Status:** `COMPLETED`
> **Kind:** `IMPLEMENTATION`
> **Implementation-Ready:** `true`
> **Writes-Migration:** `true`
> **Depends-On:** `docs/exec-plans/completed/nearby-store-discovery.md`, `docs/exec-plans/completed/local-demo-environment-and-smoke.md`, `docs/exec-plans/completed/payment-confirmation-and-reconciliation.md`
> **Completed-At:** `2026-08-09`

이 ExecPlan은 `.agent/PLANS.md`를 따른다.

## Purpose / Big Picture

PR 46의 catalogue·local demo 보강 뒤에도 남은 false-success와 거래 경계 누락을 제거한다. 완료 뒤에는 슬롯 시작 뒤의 유료 확정이 불가능하고, catalogue 읽기는 응답과 DB 계획 모두 상한을 가지며, demo는 실제 원장 적립과 소유한 프로세스·컨테이너만 성공으로 보고한다.

## Current State

- `PickupReservationService.reserve`만 `startsAt > now`를 확인하고 external-payment Order의 고정 5분 lease 및 `confirm`은 슬롯 시작 경계를 다시 반영하지 않는다.
- 7일 슬롯 horizon과 메뉴 bound는 존재하지만 slot row bound와 catalogue composite index/actual PostgreSQL plan이 없다.
- local demo는 transaction 없는 3,000 KRW PointAccount/Lot을 seed하고, settlement deadline 및 Docker 실패를 성공처럼 보고할 수 있다.
- bootstrap은 모든 `already` 로그를 정책 존재로 취급하고 stop은 PID 재사용 및 전역 `pkill`에 취약하다.

## Definitions

- **effective lease:** external-payment Order의 모든 예약이 유지되는 `min(createdAt + 5분, pickupSlot.startsAt)` 시각이다.
- **late approval:** effective lease 이후 확인된 Provider 승인이다. Order를 `PAID`로 복구하지 않고 ADR-013 reconciliation을 남긴다.
- **catalogue overflow:** 한 Store의 공개 슬롯 목록이 7일 window 안에서 published row bound를 넘는 상태다. 부분 목록이 아니라 503이다.
- **core demo smoke:** customer → store fulfilment → point accrual의 확인 가능한 HTTP 흐름이다. settlement batch는 별도 재현 fixture가 생길 때까지 포함하지 않는다.

## Scope

### In Scope

- effective lease의 픽업 예약·재고·쿠폰·포인트·Order deadline 전파와 payment/late-approval 경계 테스트
- slot `bound+1 → 503`, V35 composite indexes, multiple-store PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)` evidence
- PointAccount zero-balance demo seed, source-bound `ACCRUAL` transaction 및 balance delta smoke assertion
- exact bootstrap result, owned process-group shutdown, Docker failure propagation, settlement probe 제거
- OpenAPI/KDoc semantic contract, NUL safety scan, JDBC TRACE startup guard

### Non-goals

- settlement batch 생성 조건이나 public settlement API의 신규 설계
- 기존 V1~V34 migration 수정, historical point ledger backfill, production provider 변경
- slot cursor pagination 또는 매장별 lead-time 정책 추가

## Business Rules and Invariants

- external-payment Order는 effective lease 이후 만료 우선이며, provider approval/lookup이 늦으면 예약을 확정하지 않는다.
- slot list는 `startsAt > now`, `< now + 7일`, 최대 1,000 rows다. 1,001번째 row가 있으면 503이고 partial 200은 없다.
- menu and option lists retain their 1,000/5,000 overflow 503 policy; queries must use owner-scoped indexes.
- PointAccount available balance, PointLot balance, and PointTransaction must not begin the demo in disagreement.
- demo deadline/daemon failure is non-zero; reset deletes runtime key material only after compose down and container absence are verified.

## Architecture and Transaction Boundaries

- Fulfillment locks the slot and returns the effective expiry together with the reservation. Ordering uses that value for all resource commands and persists it on the pending Order in one local transaction.
- Provider approval remains outside the DB transaction. `PaymentResultTransaction` sees the same persisted deadline, expires resources atomically, then records late approval/reconciliation rather than rolling provider success back.
- Catalogue repositories stay DTO queries. Flyway V35 owns only supporting indexes; no Aggregate association is added for read convenience.
- Scripts persist an owned PID/PGID/nonce record and only signal the verified session. Docker error classification is explicit.

## Alternatives Considered

- Recheck `startsAt` only in confirm: rejected because a Provider can already have approved when the DB transaction fails, creating an unrepresented external payment.
- Cursor pagination for slots: rejected for this remediation because it changes response schema/clients; explicit `bound+1 → 503` matches the existing menu failure model without silent truncation.
- Treat `POLICY_ALREADY_INITIALIZED` as successful: rejected because this script cannot verify that persistent policy fields equal demo policy. It must direct the operator to reset.
- Keep settlement warning in core smoke: rejected because a deadline warning followed by exit 0 is false success.

## Failure Semantics

- slot deadline, catalogue overflow, point accrual deadline, bootstrap mismatch, process ownership mismatch and Docker daemon failure are explicit errors, not empty data/warnings/success.
- stale PID records are removed without signaling a process when their PGID, cwd, nonce, or command ownership check fails.
- a late Provider approval is `RECONCILING` with void/refund work; it never produces `PAID` or confirmed resources.

## Data and Migration

Migration-writer lane preflight on 2026-08-09 found no other live schema-writing worktree/agent. `origin/main` ends at V33 and this PR already has forward V34, so this plan uses V35 only. It adds:

- `fulfillment_pickup_slot(store_id, starts_at, id)`
- `merchant_menu(store_id, name, id)`
- `merchant_menu(store_id, id)` and `merchant_menu_option(menu_id, name, id)` to drive one-store option reads without a global option sort.

No existing migration is edited and no historical data is changed.

## API and Event Contracts

- OpenAPI describes menu/option overflow 503, `startsAt > server now`, seven-day horizon, slot overflow 503, and the existing-store/disabled-pickup empty 200 semantics.
- `GET /stores/{storeId}/pickup-slots` remains an array response; its new 503 condition is explicit.
- core smoke calls only declared runtime OpenAPI operations and proves the `OrderCompleted` accrual by source reference and balance delta.

## Milestones

1. Add RED tests for lease deadline, plans/indexes, script failure paths, ledger seed and OpenAPI wording.
2. Implement effective lease, catalogue bounds/indexes, and local-demo safety changes.
3. Run focused Testcontainers/script/OpenAPI tests and collect actual explain plans.
4. Update policy, ADR, runbook, quality evidence and this plan with actual outcomes.
5. Run required build/document/diff validation, review the diff, then commit and push the PR branch.

## Required Tests

- provider approval crossing slot start; UNKNOWN → late approval crossing it; expiration/approval competition
- 1,001 slot overflow; multiple-store menu/option/slot index plans with `EXPLAIN (ANALYZE, BUFFERS)`
- ledger-consistent demo seed and accrual source/balance smoke deadline
- stale PID pointing at an unrelated `sleep`; compose error on ordinary stop and reset; exact bootstrap terminal result
- NUL tracked-file rejection, smoke OpenAPI coverage, logging TRACE startup rejection, OpenAPI semantic text

## Validation Commands

- `./gradlew test --tests '*PaymentConfirmationIntegrationTest' --tests '*Pickup*' --tests '*StoreCatalog*'`
- `./gradlew test --tests '*LocalDemo*' --tests '*NearbyCoordinatePrivacyIntegrationTest'`
- `bash scripts/verify-docs.sh`
- `./gradlew clean build`
- `git diff --check`

## Observability

No customer coordinate, source reference, PID or key material is added as a metric tag. Existing payment reconciliation state and closed catalogue outcome metrics remain the operational signal. Script failure prints a bounded diagnostic without claiming completion.

## Documentation Updates

- BR-03/BR-05, ADR-013 and ADR-076 record the effective lease and slot overflow decision.
- local-demo runbook and completed demo plan distinguish core smoke from unimplemented settlement probe.
- OpenAPI and semantic contract tests describe public catalogue behavior.
- performance evidence records actual PostgreSQL plan shapes rather than an unmeasured claim.

## Progress

- [x] Review correctness, migration-lane inventory and existing contracts inspected
- [x] RED tests
- [x] Implementation and V35 migration
- [x] Focused validation, actual demo smoke and explain evidence
- [x] Full build, final diff review and completion move

## Surprises & Discoveries

- 2026-08-09: ADR-076 explicitly chose time-only slot bounding; the review correctly identifies that this does not bound rows inside the window. This plan amends it with the same explicit overflow behavior already accepted for menu reads.
- 2026-08-09: the active Analytics plan declares `Writes-Migration=true`, but no Analytics worktree or live agent holds a migration writer lane. This task is the sole writer while it runs.
- 2026-08-09: the original option join selected an all-option sequential scan and hash join in the
  measured fixture. A one-query lateral menu walk produces named index-only scans and an
  incremental per-menu sort bounded by `LIMIT 5001`; the raw before/after plans are recorded rather
  than describing it as sort-free.
- 2026-08-09: the old repository-safety Kotlin source contained a literal NUL, so Git showed its
  fix as a binary diff. `.gitattributes` now forces Kotlin text diffs and the scanner allows NUL
  only in the tracked Gradle wrapper jar.

## Decision Log

| Date | Status | Decision | Rationale | Record |
|---|---|---|---|---|
| 2026-08-09 | Chosen for implementation | effective lease is min of 5 minutes and slot start | preserves external-payment reconciliation and prevents paid confirmation after pickup start | BR-03, BR-05, ADR-013, ADR-076 |
| 2026-08-09 | Chosen for implementation | 1,000-slot `bound+1 → 503` | avoids both unbounded work and silent truncation without a public schema migration | ADR-076, OpenAPI |
| 2026-08-09 | Chosen for implementation | core smoke excludes settlement | no deterministic batch trigger exists; warning+exit 0 cannot represent a passed E2E assertion | local-demo runbook |
| 2026-08-09 | Chosen for implementation | confirm rechecks slot start as well as persisted effective lease | legacy/manual reservation state must not bypass the pickup-start invariant | PickupReservationService, repository test |

## Outcomes & Retrospective

All 10 Important review findings and the four Suggestions were resolved or narrowed into an explicit
non-goal. No settlement assertion remains hidden behind a successful core smoke result.

- Pickup reservation returns the effective lease; Order and all resource reservations persist the
  same deadline. Payment approval/unknown lookup at or after slot start expires rather than confirms,
  and direct confirmation rechecks both the stored deadline and slot start.
- V35 adds owner-scoped catalogue indexes. Slot overflow is `LIMIT 1001 → 503`; menu/option bounds
  remain explicit. The 51-store Testcontainers fixture records raw V35 plan evidence and storage
  sizes in `docs/quality/store-catalog-query-performance-evidence.md`.
- Demo starts with a 0 KRW account and no unaudited point lot/transaction. Its actual HTTP smoke
  requires the completion `ACCRUAL` source/type/amount and point-account delta. Bootstrap, process
  ownership and Docker errors cannot be converted to success by fuzzy matching, global `pkill`, or
  ignored exit codes.
- OpenAPI, KDoc, policy, ADR, runbooks, NUL scanning and JDBC parameter TRACE/ALL startup protection
  now describe and enforce the implemented contract.

**Validation actually run (2026-08-09):**

- focused payment/catalogue/reservation/demo/privacy/OpenAPI tests, including the V35 plan test;
- `bash scripts/demo/start.sh`, `seed.sh` (24 rows), `smoke.sh` (20 HTTP assertions, 0 → 50 KRW
  `ACCRUAL`), second `seed.sh` (0 rows), then non-reset `stop.sh`;
- `./gradlew clean build` → 523 tests, 0 failures, 1 skipped; after a test-only warning cleanup,
  `./gradlew spotlessCheck test --tests '*PickupReservationRepositoryTest*'` also passed;
- `bash scripts/verify-docs.sh` and `git diff --check`.

The full build reports pre-existing Testcontainers/Kotlin deprecation warnings; no new runtime
dependency was added. Native production timing, concurrent load, production write rate, deterministic
settlement batch creation, and a migration/backfill for already-running historical orders are
explicitly outside this remediation.

## Revision Notes

- 2026-08-09: Created from PR 46 open review findings after source, OpenAPI, policy and migration-lane inspection.
- 2026-08-09: Completed after actual core demo smoke, V35 PostgreSQL plan capture, clean build and
  documentation verification. Moved to `completed/` with final validation evidence.
