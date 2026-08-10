# Retention and Deletion Architecture

> **Status:** Accepted retention/LegalHold/deletion principles from ADR-089 with `PROPOSED` S10/S120 schema and worker
> mechanics. No table, worker or restore procedure below is implemented by S00.

`RetentionPolicyVersion` maps category and purpose to immutable RetentionClass. `retentionExpiresAt` is evidence, not the only policy source. Existing records are not silently reclassified when policy changes.

```text
candidate keyset chunk -> LegalHold check -> owner precondition
-> DB/crypto/object/index/projection operation
-> component result -> DeletionLedger -> metric/Audit
```

Worker instances claim bounded chunks with skip-locked/lease semantics. Empty result and DB failure are distinct. Component failures retain retry count, stable code and `RETRY_SCHEDULED | FAILED | MANUAL_REVIEW`; success requires all required components. Ledger uses opaque/hash references only.

Restore occurs in isolation. Backup watermark plus deletion decisions and current retention policy are reapplied before traffic, then sampled/automated checks prove removed PII did not reappear. Key retention and backup lifetime are part of policy. Legal minimum transaction records remain physically/logically separated from active PII.
