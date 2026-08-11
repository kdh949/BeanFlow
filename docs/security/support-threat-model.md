# Support Threat Model

> **Status:** S30 exact-search and S40 verification/reveal rows marked implemented have runtime evidence. Controls tied
> to later profile change and delivery-provider endpoints remain release requirements, not current runtime evidence.

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
| notification outage (S40 implemented) | durable PII-free intent and RETRY_SCHEDULED/MANUAL_REVIEW, no no-op fallback | UNKNOWN/retry and provider-outside-transaction tests |
| stale payload execution | revision/hash/version binding | stale/revision race tests |
| duplicate financial action | Idempotency-Key, owner uniqueness/locks | same/different payload concurrency |
| Provider spoof/replay/order | raw-body authentication, inbox unique, state monotonicity | provider contract tests |
| unknown outcome duplicate | durable intent and lookup reconciliation | ACK loss/timeout/restart tests |
| browser residue/XSS | no persistent PII cache/storage, clear on expiry/navigation, CSP/XSS tests | UI E2E/storage inspection |
| retention bypass | scoped expiring LegalHold, component ledger, restore replay | deletion/restore tests |

Residual risks include authorized exact-match frequency observation within the rate budget, screenshots, authorized-user
observation and legal applicability. UI cannot claim screenshot prevention or complete regulatory compliance.
