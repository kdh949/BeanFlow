# Support Threat Model

> **Status:** S30 exact-search rows marked implemented have runtime evidence. Controls tied to later reveal/change and
> delivery-provider endpoints remain release requirements, not current runtime evidence.

| Threat | Control | Verification |
|---|---|---|
| exact-search harvesting (S30 implemented) | persistent permission, structured reason, masked candidates, actor 30/5-minute rate guard, bounded count/result | authorization/rate/concurrency/API/PII-log tests |
| blind-index equality/frequency disclosure (S30 residual) | distinct Vault HMAC key, DB access boundary, versioned owner index, no digest log/metric/Audit | Vault contract, schema and PII capture tests |
| ciphertext substitution (S30 implemented) | owner/subject/field/version-bound AEAD associated data and ciphertext metadata checks | AAD/metadata/rewrap tests |
| forged verification/decision | server-owned state and execution re-evaluation | tampered request tests |
| insider PII harvesting | field grant, reason, Audit-before-reveal, alerting | reveal spike/out-of-hours tests |
| self/collusive approval | DB/service separation constraints, actor revocation | concurrency and role matrix |
| stale payload execution | revision/hash/version binding | stale/revision race tests |
| duplicate financial action | Idempotency-Key, owner uniqueness/locks | same/different payload concurrency |
| Provider spoof/replay/order | raw-body authentication, inbox unique, state monotonicity | provider contract tests |
| unknown outcome duplicate | durable intent and lookup reconciliation | ACK loss/timeout/restart tests |
| browser residue/XSS | no persistent PII cache/storage, clear on expiry/navigation, CSP/XSS tests | UI E2E/storage inspection |
| retention bypass | scoped expiring LegalHold, component ledger, restore replay | deletion/restore tests |

Residual risks include authorized exact-match frequency observation within the rate budget, screenshots, authorized-user
observation and legal applicability. UI cannot claim screenshot prevention or complete regulatory compliance.
