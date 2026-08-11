# Support State Machines

> **Status:** `PARTIALLY IMPLEMENTED`; the S20 Case state contract below is Accepted and persisted/API-backed.
> Verification and Grant diagrams remain future-stage inputs.

## Case

```text
OPEN        -> IN_PROGRESS
IN_PROGRESS -> WAITING | RESOLVED
WAITING     -> IN_PROGRESS
RESOLVED    -> CLOSED
```

`CLOSED` is terminal in S20; no reopen transition or endpoint exists.

## Verification and grant

These are S40 contracts, not S20 persistence or endpoints. When Grant becomes implemented, a Case entering
`RESOLVED` or `CLOSED` must revoke its active Grants in the same Case boundary, and Grant activation/reveal for a
terminal Case must fail closed.

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
