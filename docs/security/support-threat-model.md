# Support Threat Model

> **Status:** Planning threat model. Controls tied to unimplemented endpoints/providers are release requirements, not
> current runtime evidence.

| Threat | Control | Verification |
|---|---|---|
| IDOR/mass search | object checks, masked candidates, rate/anomaly controls | negative matrix/API tests |
| forged verification/decision | server-owned state and execution re-evaluation | tampered request tests |
| insider PII harvesting | field grant, reason, Audit-before-reveal, alerting | reveal spike/out-of-hours tests |
| self/collusive approval | DB/service separation constraints, actor revocation | concurrency and role matrix |
| stale payload execution | revision/hash/version binding | stale/revision race tests |
| duplicate financial action | Idempotency-Key, owner uniqueness/locks | same/different payload concurrency |
| Provider spoof/replay/order | raw-body authentication, inbox unique, state monotonicity | provider contract tests |
| unknown outcome duplicate | durable intent and lookup reconciliation | ACK loss/timeout/restart tests |
| browser residue/XSS | no persistent PII cache/storage, clear on expiry/navigation, CSP/XSS tests | UI E2E/storage inspection |
| retention bypass | scoped expiring LegalHold, component ledger, restore replay | deletion/restore tests |

Residual risks include screenshots, authorized-user observation and legal applicability. UI cannot claim screenshot prevention or complete regulatory compliance.
