# Delivery Reconciliation

> **Status:** `PLANNED`; this is failure-semantics input, not an executable operational procedure.

Reconciliation compares durable intent, Provider lookup and canonical fulfillment without overwriting stronger terminal facts. Triggers include request timeout, missing webhook, invalid/out-of-order mapping, provider success/DB failure and stale sync duration.

Outcomes are in-sync, confirmed absent/failed, unknown/retry scheduled, out-of-sync conflict or manual review. Each attempt records provider, opaque references, attempt count, next attempt, stable failure code and correlation without raw payload/PII. A prior Provider must be authoritative before another dispatch may be considered.

Operational intent is planning-only: [Support planned operational procedures](../operations/support-planned-operational-procedures.md).
