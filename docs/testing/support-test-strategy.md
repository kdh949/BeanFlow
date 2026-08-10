# Customer Support Planned Test Strategy

> **Status:** `IMPLEMENTATION_PLANNED`
> No Support/Delivery/LegalHold test class, k6 script, fixture or measured result exists. An owning Stage converts only its
> relevant section into named tests and records exact commands/results in its detailed ExecPlan.

## Coverage by layer

| Layer | Planned coverage |
|---|---|
| Domain | Case/verification/grant/action/approval/resolution/compensation/delivery/retention state invariants |
| Application | public owner Port order, transaction/Audit fail-closed, unknown/retry/partial semantics |
| PostgreSQL Testcontainers | FK/unique/check/index/locks, actor separation, rolling buckets, Inbox and worker claims |
| API contract | endpoint-specific status/error/masking/no-store/cursor/idempotency/security; target/runtime separation |
| Modulith/ArchUnit | Support cannot use owner internal Repository/entity; Controller cannot use Repository |
| Security | IDOR, role/grant/Case/verification matrix, PII leakage, approval separation and browser controls |
| Provider/resilience | timeout, ACK loss, duplicate/out-of-order, restart and same-reference reconciliation |
| Retention/restore | policy boundary, LegalHold race, partial component deletion and restore replay |
| UI/load | selected frontend boundary, reveal expiry/navigation clearing and reproducible search/timeline/action load |

## Authorization matrix to instantiate per Stage

Dimensions are role × persistent permission × Case state/assignment × requester-subject relation × verification
level/purpose × grant scope/expiry × action × target state/version × amount/history × approval state. Explicit negative
fixtures include role-only, grant-only, revoked grant, other assignee/Subject, closed Case, BASIC-for-ENHANCED,
BREAK_GLASS-as-level, forged client decision, self/two-step same reviewer, reviewer-as-executor and stale revision.
Unknown combinations expect DENIED. Repository/Audit failure expects 503/no sensitive body, not 403/empty success.

## Concurrency and idempotency scenarios to instantiate

- challenge replay, attempt lockout and grant expiry/revocation versus reveal
- permission revoke versus authorized transaction; approval step/reviewer duplication and revoke versus execute
- same key/same payload replay, same key/changed payload conflict
- last pickup slot atomic swap; ACCEPTED cancellation versus PREPARING; two agents cancel one Order
- rolling compensation bucket and duplicate terminal incident benefit
- Provider event duplicate/out-of-order, timeout versus webhook, unknown versus second Provider dispatch
- retention worker claim contention and LegalHold create versus delete

Tests use deterministic Clock/IdentifierSource and PostgreSQL locks/constraints, not only mocks.

## Retention and restore scenarios to instantiate

Verify `-1ns / at / +1ns` for financial Audit 5y, PII access 2y, Case/evidence/verification 3y, contact
90d, current location 24h and raw webhook 7d. OTP/token never persists. Cover scoped LegalHold review/expiry/release,
hold/delete race, chunk restart, component partial failure, redacted ledger, active/legal-minimum separation and isolated
backup restore with deletion replay before traffic.

## Load and UI measurement plan

No k6 script is created before endpoints exist. S140 may add exact masked search, paged timeline, one implemented action,
approval queue, Provider webhook burst and retention chunk scripts only for implemented paths. Every measurement records
environment, dataset, VU/RPS, p50/p95/p99, error rate, SQL count/plan, pool/lock/deadlock, heap/GC and relevant backlog.
Inputs/labels contain no PII; thresholds remain assumptions until measured.

## Evidence rules

- Planned coverage is neither `IMPLEMENTED` nor `VERIFIED`.
- Each Stage names exact new/current test classes and commands; generic copied validation text is insufficient.
- Performance numbers require comparable environment and baseline.
- Legal review is required before production and is not inferred from passing tests.
