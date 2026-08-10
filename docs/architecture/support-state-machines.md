# Support State Machines

> **Status:** `DRAFT`; diagrams are Stage inputs, not Accepted persisted/API enum contracts.

## Case

```text
OPEN -> IN_PROGRESS -> WAITING -> IN_PROGRESS -> RESOLVED -> CLOSED
                                  RESOLVED -> IN_PROGRESS (audited reopen)
```

## Verification and grant

```text
CHALLENGE_PENDING -> VERIFIED | FAILED -> LOCKED
VERIFIED -> EXPIRED | REVOKED

REQUESTED -> APPROVAL_PENDING -> ACTIVE -> CONSUMED | EXPIRED | REVOKED
          -> DENIED
```

## Action request

```text
REQUESTED -> EVALUATED -> APPROVAL_PENDING -> APPROVED -> EXECUTING
                                                         -> SUCCEEDED
                                                         -> FAILED
                                                         -> UNKNOWN -> RECONCILING -> SUCCEEDED|FAILED|MANUAL_REVIEW
revision/version change -> STALE (approval cannot execute)
```

## Post-acceptance resolution

```text
REQUESTED -> STORE_CONFIRMATION_PENDING|APPROVAL_PENDING -> APPROVED -> EXECUTING
-> PARTIALLY_RESOLVED -> RESOLVED
-> UNKNOWN|RECONCILING|MANUAL_REVIEW|FAILED|REJECTED|EXPIRED
```

## Delivery

See [Delivery state machine](delivery-state-machine.md). Physical lifecycle and ProviderSyncStatus never collapse into one status.
