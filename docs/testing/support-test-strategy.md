# Customer Support Planned Test Strategy

> **Status:** `PARTIALLY IMPLEMENTED`
> S20–S40 have named domain, PostgreSQL Testcontainers, API/integration, OpenAPI and ArchUnit tests. Delivery, LegalHold and
> later Support stages remain planned; no Support performance or k6 result is claimed.

## Coverage by layer

| Layer | Current / planned coverage |
|---|---|
| Domain | S20 Case matrix/content guard; S40 BASIC/ENHANCED channel rules, expiry/lockout, field/risk budget, actor-separated break-glass; later aggregates planned |
| Application | S20 Case authorization/idempotency/Audit; S30 masked search; S40 provider UNKNOWN/stale recovery, Audit-before-reveal, Case-terminal revoke, owner failure and notification retry/reclaim |
| PostgreSQL Testcontainers | S20–S40 CHECK/UNIQUE/append-only constraints, Case-first row-lock winners, reveal budget, Case+Subject lockout and durable notification claim/reclaim |
| API contract | S20–S40 status/error/no-store/idempotency schemas, raw/proof exclusion and target/runtime Spring MVC parity |
| Modulith/ArchUnit | Controller→Repository and Support→owner-internal boundary; S40 uses Identity/Merchant/Delivery/Notification public APIs only |
| Security | IDOR, persistent permission/Case/verification/field matrix, proof/raw diagnostic redaction, Audit failure, actor-separated approval/review, post-decrypt permission recheck |
| Provider/resilience | S40 challenge and security notification timeout/UNKNOWN, duplicate verification winner and transaction-outside-provider checks; later PG/Delivery paths planned |
| Retention/restore | policy boundary, LegalHold race, partial component deletion and restore replay |
| UI/load | selected frontend boundary, reveal expiry/navigation clearing and reproducible search/timeline/action load |

## Authorization matrix to instantiate per Stage

Dimensions are role × persistent permission × Case state/assignment × requester-subject relation × verification
level/purpose × grant scope/expiry × action × target state/version × amount/history × approval state. Explicit negative
fixtures include role-only, grant-only, revoked grant, other assignee/Subject, closed Case, BASIC-for-ENHANCED,
BREAK_GLASS-as-level, forged client decision, self/two-step same reviewer, reviewer-as-executor and stale revision.
Unknown combinations expect DENIED. Repository/Audit failure expects 503/no sensitive body, not 403/empty success.

## Concurrency and idempotency scenarios to instantiate

- challenge replay, attempt lockout including subject relink, stale Provider work recovery and grant expiry/revocation versus reveal
- permission revoke versus authorized transaction; approval step/reviewer duplication and revoke versus execute
- same key/same payload replay, same key/changed payload conflict, cross-actor/cross-operation key reuse and free-text
  field-boundary collision conflict
- last pickup slot atomic swap; ACCEPTED cancellation versus PREPARING; two agents cancel one Order
- rolling compensation bucket and duplicate terminal incident benefit
- Provider event duplicate/out-of-order, timeout versus webhook, unknown versus second Provider dispatch
- retention worker claim contention and LegalHold create versus delete

S20–S40 tests use PostgreSQL locks/constraints and runtime contracts, not only mocks. Future stages additionally use
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

- S20–S40 coverage is limited to their named test classes; it does not verify future Support stages.
- Each later Stage names exact new/current test classes and commands; generic copied validation text is insufficient.
- Performance numbers require comparable environment and baseline.
- Legal review is required before production and is not inferred from passing tests.
