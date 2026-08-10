# Support Transaction Boundaries

> **Status:** Accepted transaction/failure principles with `PROPOSED` co-location and persistence mechanics pending each
> owning Stage.

## Local atomic candidates

- SupportCase state + append-only history
- verification outcome + attempt/lock update
- grant activation/reveal authorization + pre-reveal Audit when co-located
- ActionRequest revision + policy snapshot; ApprovalStep + request state
- owner-local pickup slot swap
- owner-local point/coupon issuance + compensation execution result
- provider webhook Inbox insert
- deletion component transition + deletion ledger result

## Cross-context orchestration

Support transaction stores intent and immutable references, then calls owner public Application API. No Support transaction updates owner tables. If owner and Audit share the same database/local transaction, high-risk change and target Audit commit together; otherwise an Accepted durability ADR is required before implementation.

External OTP/email/PG/Delivery/notification/object-storage calls occur outside long DB transactions. Intent/claim is committed before call and result is committed afterward. Timeout or ACK loss produces `UNKNOWN`/`RECONCILING`, never guessed success/failure. Notification failure after confirmed change leaves the change intact and schedules retry/manual review.
