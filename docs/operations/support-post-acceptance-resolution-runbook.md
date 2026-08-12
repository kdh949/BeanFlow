# Support Post-Acceptance Resolution Runbook

> **Status:** S80 initial operations contract. This runbook does not authorize direct database mutation.

Use this runbook when a PREPARING, READY or COMPLETED Order resolution remains `RECONCILING`,
`PARTIALLY_RESOLVED` or `MANUAL_REVIEW`. Never change the historical Order to `CANCELLED`, overwrite a confirmed
Settlement item, assign an unknown cost owner or issue a replacement Point/Coupon through S80.

## Triage

1. Read `GET /api/v1/support/post-acceptance-resolutions/{resolutionId}` as an actor with resolution visibility.
   The response is `no-store`; do not paste it into tickets or logs.
2. Record only closed fields: Resolution state, step type/state/failure code/attempt count and timestamps. Do not copy
   customer identifiers, evidence, Provider payloads or result references into free-text systems.
3. Confirm the Order is still the response's `triggerOrderState` and `triggerOrderVersion`. A version mismatch needs a
   newly approved S60 revision; do not bypass the stale response.
4. Distinguish the condition:

   - Payment `UNKNOWN`/`RECONCILING`: wait until `nextAttemptAt`, then execute the exact resolution again. The owner
     performs Provider lookup and does not issue another Refund.
   - Payment `MANUAL_REVIEW`: after Provider-side evidence is available, use the explicit reconciliation operation below.
   - Point/Coupon `SKIPPED_EXPIRED`: terminal owner result. S80 must not extend expiry or issue goodwill; use S90 when
     an independently approved goodwill benefit is appropriate.
   - Settlement `BLOCKED` with `UNDETERMINED`: obtain a new responsibility decision/revision. Do not choose STORE or
     PLATFORM as a fallback.
   - Notification owner state pending/retry/manual review: financial resolution remains valid. Follow the Notification
     delivery workflow without reversing Refund/restoration/adjustment facts.

## Safe Payment reconciliation

Only the assigned eligible executor may submit:

```http
POST /api/v1/support/post-acceptance-resolutions/{resolutionId}/reconciliations
Idempotency-Key: <stable-operator-key>
Content-Type: application/json

{
  "stepType": "PAYMENT_REFUND",
  "expectedResolutionVersion": 7,
  "expectedOrderVersion": 12
}
```

Reuse the same key only with the exact same payload. A 409 stale/version response requires a fresh read and, when the
approved plan changed, a new S60 revision. `REPROCESSING_NOT_SAFE` means the selected step is not eligible for safe
operator lookup; do not retry another owner type or call owner tables directly.

## Failure and escalation

- A 503 before owner work leaves the plan/claim uncommitted. Retry after dependency recovery with the same key.
- A 503 after an owner accepted work can leave the Support step `PROCESSING`. Do not reissue. After the claim lease
  expires, exact execution recovers it as `UNKNOWN` and reconciles the immutable owner source.
- Repeated Provider `UNKNOWN`, missing immutable owner source, conflicting payload hash or exhausted delivery goes to
  `MANUAL_REVIEW`. Preserve all successful sibling steps and escalate with opaque Resolution ID only.
- Audit persistence failure must never be treated as successful Support recording even if an owner effect is visible.

## Prohibited actions

- updating Resolution/step/Refund/restoration/Settlement rows by SQL;
- creating a second Refund with a different source while one is unresolved;
- rewriting PREPARING/READY/COMPLETED Order state;
- creating a replacement PointLot/Coupon in the S80 path;
- logging evidence digest, Provider references, customer data or API response bodies.

## Verification after recovery

Read the resolution again and confirm the owner step has a terminal state, financial state is explicit, the Order state
and version were not rewritten, and Notification remains independently visible. `PARTIALLY_RESOLVED` or
`MANUAL_REVIEW` is not completion and must stay open until its blocked/manual condition receives an approved follow-up.
