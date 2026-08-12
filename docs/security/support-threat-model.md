# Support Threat Model

> **Status:** S30 exact-search, S40 verification/reveal and S100 purpose-specific profile-change rows marked implemented
> have runtime evidence. Controls tied to later delivery-provider endpoints remain release requirements.

| Threat | Control | Verification |
|---|---|---|
| exact-search harvesting (S30 implemented) | persistent permission, structured reason, masked candidates, actor 30/5-minute rate guard, bounded count/result | authorization/rate/concurrency/API/PII-log tests |
| blind-index equality/frequency disclosure (S30 residual) | distinct Vault HMAC key, DB access boundary, versioned owner index, no digest log/metric/Audit | Vault contract, schema and PII capture tests |
| ciphertext substitution (S30 implemented) | owner/subject/field/version-bound AEAD associated data and ciphertext metadata checks | AAD/metadata/rewrap tests |
| forged verification/decision (S40 implemented) | provider-owned challenge result, Case+Subject+Purpose binding, execution-time revalidation | mismatch/replay/provider transaction tests |
| brute-force/replay (S40 implemented) | one-shot challenge, five-attempt/30-minute persistent lock, idempotent concurrent single winner | lockout/expiry/concurrency tests |
| insider PII harvesting (S40 partial) | closed field grant, reason, short TTL/budget, Audit-before-reveal, no-store response | scope/Audit failure/budget/PII redaction tests; out-of-hours analytics remains later scope |
| self/collusive approval (S40 implemented for privacy access) | requester/approver/reviewer separation and persistent permission recheck | normal Grant and break-glass role matrix |
| raw response persistence (S40 implemented) | no response-body idempotency storage; replay requires manual review | DB canary and same-key replay tests |
| notification outage or acknowledgement gap | durable PII-free intent, immutable source identity, leased PROCESSING recovery and RETRY_SCHEDULED/MANUAL_REVIEW; no no-op fallback | delivery-commit/accept-gap replay, logical-source dedupe and provider-outside-transaction tests |
| account takeover through a new contact channel (S100 implemented) | customer phone execution requires a current registered-channel-bound ENHANCED session; verification using only the proposed phone is denied | new-phone-only denial and channel-binding tests |
| generic or misclassified profile mutation (S100 implemented) | closed owner/purpose R0-R4 policy and typed endpoints; no generic PATCH; R0 has no command and R4 accepts no secret | risk matrix, strict request and OpenAPI contract tests |
| stale/collusive profile payload execution (S100 implemented) | digest/owner-version/exact revision binding, Support Manager then Operations separation, assigned-agent execution, and final requester permission plus active link/full verification binding recheck | stale version, self/dual reviewer, reassignment, post-approval revoke/unlink/challenge invalidation and concurrent execution tests |
| raw profile leakage from Support (S100 implemented) | raw values are transient and redacted from request/command rendering; Support persists only digest/masked/opaque references; owner encrypts locally | database canary, rendering, owner ciphertext and masked DTO tests |
| profile notification outage (S100 implemented) | changed contacts create OLD and NEW owner-local targets after commit; other changes use CURRENT; retry reuses terminal owner result and cannot repeat the write | old/new target, notification failure and idempotent retry tests |
| stale payload execution | revision/hash/version binding | stale/revision race tests |
| duplicate financial action | Idempotency-Key, owner uniqueness/locks | same/different payload concurrency |
| Provider spoof/replay/order | raw-body authentication, inbox unique, state monotonicity | provider contract tests |
| unknown outcome duplicate | durable intent and lookup reconciliation | ACK loss/timeout/restart tests |
| browser residue/XSS | no persistent PII cache/storage, clear on expiry/navigation, CSP/XSS tests | UI E2E/storage inspection |
| retention bypass | scoped expiring LegalHold, component ledger, restore replay | deletion/restore tests |

Residual risks include authorized exact-match frequency observation within the rate budget, screenshots, authorized-user
observation and legal applicability. UI cannot claim screenshot prevention or complete regulatory compliance.
