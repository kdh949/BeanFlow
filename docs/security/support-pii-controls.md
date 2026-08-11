# Support PII Reveal Controls

> **Status:** Masking, grant scope and Audit-before-reveal are Accepted in ADR-082. Vault Transit search crypto/index
> mechanics are Accepted in ADR-083.

Default responses are masked. Exact PII search criteria is POST-body only. Raw value never enters URL, log, metric,
trace, cursor, Audit, exception or Support long-term storage. Owner Contexts store Vault Transit AEAD ciphertext and a
separate versioned HMAC-SHA-256 blind index. Search computes configured versions outside a DB transaction, queries only
owner public APIs and returns owner-produced masked DTOs. Vault failure is 503; no plaintext scan, local key, stale cache,
empty-result or no-op fallback is permitted.

`POST /support/searches` rejects every query parameter before rate/Vault access. Request DTO `toString()` values are
redacted, Spring MVC/Security request logging is pinned to INFO, and startup rejects effective DEBUG for the categories
that render body values or complete query URIs. JDBC bound-parameter TRACE/ALL remains startup-forbidden.

Reveal requires active Case/assignment, object permission, sufficient verification, field/reason/expiry/count-bound grant, server re-evaluation and committed pre-reveal Audit. Audit failure returns no raw data. Reveal response includes only granted fields and `Cache-Control: no-store`.

PAN, CVC, password, OTP/link secret, MFA secret, access/refresh/PG raw token, encryption key and resident-registration number are never held or displayed. Payment displays provider, brand/card issuer, last4, status and opaque internal ID only.
