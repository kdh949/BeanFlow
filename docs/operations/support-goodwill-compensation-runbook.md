# Support Goodwill Compensation Runbook

> **Status:** S90 initial operations contract. This runbook does not authorize direct database mutation or benefit reissue.

## Purpose

Operate goodwill Point/Coupon requests separately from refund, restoration and ledger correction. Operators may review
the durable Support/Operations state and use documented APIs; they must not edit rolling consumption, terminal incident,
PointLot, Coupon issuance or Audit rows.

## Normal flow

1. The assigned agent evaluates and creates one exact request. `LOW` is ready directly, `MEDIUM` waits for a distinct
   Support Manager, and `HIGH`/`EXCEPTIONAL` waits for a distinct Operations reviewer.
2. Operations reviews only the exact immutable revision and returns approve/deny/revision/escalation. It does not change
   amount, template, responsibility or shares and cannot issue the benefit.
3. The assigned non-reviewer executes with exact request/target versions and payload digest. A successful financial
   commit creates exactly one Point or Coupon, one terminal incident row and five rolling consumption rows.
4. Notification is accepted after the financial commit. `NOTIFICATION_ACCEPTED` means a durable Notification intent;
   confirm actual delivery using `notificationState`, where only `SUCCEEDED` means provider success.

## Triage by state

| Support state | Meaning | Safe action |
|---|---|---|
| `AWAITING_APPROVAL` | exact manager/Operations decision is outstanding | route to the required distinct reviewer; never bypass |
| `READY_FOR_EXECUTION` | approval and verification binding are ready | assigned agent refreshes owner/request versions and executes once |
| `BENEFIT_ISSUED` | financial commit completed; notification handoff is in progress | poll current resource; do not reissue |
| `NOTIFICATION_ACCEPTED` | durable notification intent exists | inspect `notificationState`; provider worker owns retry/manual review |
| `NOTIFICATION_RETRY` | notification persistence was not accepted after benefit commit | call the notification-retry endpoint with a new exact key; never execute benefit again |

`409 SUPPORT_ACTION_POLICY_DENIED` after execution recheck means no financial commit occurred. Typical causes are a
terminal duplicate incident, rolling cap or unresolved cost owner. Do not delete consumption/terminal rows or substitute
PLATFORM for `UNDETERMINED`. A request created under an older immutable policy remains bound to it; changing the head does
not rewrite or invalidate that request.

## Failure checks

- `503` during financial execution: verify that owner issuance, terminal incident, consumption, action terminal state and
  execution idempotency are all absent before retrying the exact command. Audit failure is required to rollback all.
- `NOTIFICATION_RETRY`: verify terminal benefit and five consumption rows remain present. Retry only notification; the
  endpoint accepts no amount/template/share fields.
- Point: check `loyalty_goodwill_point_issuance`, its funding legs and linked PointLot/transaction as one owner result.
  SHARED must have separate PLATFORM and STORE legs summing to the request amount.
- Coupon: check the immutable template binding and issuance-specific campaign cost shares. Settlement cost is realized
  only when a future Order reserves/uses the Coupon; issuance itself creates no Settlement adjustment.
- Concurrent cap contention: one execution may win and the other receive policy denial. This is expected serialization,
  not a reason to raise a limit or modify rows.

## Prohibited recovery

- direct Point balance update or free-form Coupon creation;
- deleting terminal incident or rolling consumption to make a retry pass;
- changing a policy version/template after activation;
- executing as requester reviewer or Operations reviewer;
- treating `NOTIFICATION_ACCEPTED` as delivery success;
- logging customer, Case/order/incident/request IDs, amount, evidence digest, idempotency key or raw provider payload as
  metric dimensions or unredacted diagnostic context.

## Escalation evidence

Provide bounded state names, policy code/version, band/route, failure code and timestamps. Keep identifiers in the
authorized case tool only, not tickets, logs, metric labels or screenshots. Escalate suspected schema/Audit corruption to
manual investigation; never repair it with ad-hoc SQL.
