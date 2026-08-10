# Delivery State Machine

> **Status:** `DRAFT`; exact persisted/API state vocabulary is finalized with the Provider-independent Delivery model.

Canonical lifecycle:

```text
PLANNED -> DISPATCH_REQUESTED -> ASSIGNING -> COURIER_ASSIGNED
-> EN_ROUTE_TO_PICKUP -> AT_PICKUP -> PICKED_UP
-> EN_ROUTE_TO_DROPOFF -> AT_DROPOFF -> DELIVERED

active -> CANCELLATION_REQUESTED -> CANCELLED
active -> FAILED
picked/failed -> RETURN_REQUESTED -> RETURNING -> RETURNED
```

Provider contracts may omit intermediate states, but accepted transitions cannot regress (`DELIVERED -> EN_ROUTE_TO_DROPOFF`, `PICKED_UP -> COURIER_ASSIGNED`). A terminal conflict is reconciliation/manual review. `ProviderSyncStatus` is independently `IN_SYNC | STALE | UNKNOWN | RECONCILING | OUT_OF_SYNC | MANUAL_REVIEW`.
