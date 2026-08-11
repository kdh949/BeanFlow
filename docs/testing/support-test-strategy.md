# Customer Support Planned Test Strategy

> **Status:** `PARTIALLY IMPLEMENTED`
> S20 has named domain, PostgreSQL Testcontainers, API/integration, OpenAPI and ArchUnit tests. Delivery, LegalHold and
> later Support stages remain planned; no Support performance or k6 result is claimed.

## Coverage by layer

| Layer | Current / planned coverage |
|---|---|
| Domain | S20 Case transition matrix, terminal rejection, `OTHER` guard and content policy; later aggregates planned |
| Application | S20 persistent grant/current assignment/idempotency/Audit fail-closed; owner port/unknown semantics planned |
| PostgreSQL Testcontainers | S20 migration CHECK/UNIQUE/append-only/active-link constraints and advisory-lock winner; later locks/worker claims planned |
| API contract | S20 endpoint status/error/no-store/cursor/idempotency/security plus target/runtime parity; later masking/reveal planned |
| Modulith/ArchUnit | S20 Controller→Repository and Support→owner-internal boundary; later module rules planned |
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

S20 tests use PostgreSQL locks/constraints and runtime contracts, not only mocks. Future stages additionally use
deterministic Clock/IdentifierSource where their behavior requires it.

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

- S20 coverage is limited to its named test classes; it does not verify future Support stages.
- Each later Stage names exact new/current test classes and commands; generic copied validation text is insufficient.
- Performance numbers require comparable environment and baseline.
- Legal review is required before production and is not inferred from passing tests.
