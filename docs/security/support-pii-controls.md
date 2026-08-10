# Support PII Reveal Controls

> **Status:** Masking, grant scope and Audit-before-reveal are Accepted in ADR-082. Search crypto/index mechanics remain
> Proposed under ADR-083.

Default responses are masked. Exact PII search criteria is POST-body only. Raw value never enters URL, log, metric,
trace, cursor, Audit, exception or Support long-term storage. Normalization, keyed index, encryption and rotation are not
fixed until ADR-083 is Accepted.

Reveal requires active Case/assignment, object permission, sufficient verification, field/reason/expiry/count-bound grant, server re-evaluation and committed pre-reveal Audit. Audit failure returns no raw data. Reveal response includes only granted fields and `Cache-Control: no-store`.

PAN, CVC, password, OTP/link secret, MFA secret, access/refresh/PG raw token, encryption key and resident-registration number are never held or displayed. Payment displays provider, brand/card issuer, last4, status and opaque internal ID only.
