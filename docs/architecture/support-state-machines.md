# Support State Machines

> **Status:** `PARTIALLY IMPLEMENTED`; S20 Case and S40 verification/access state contracts are persisted/API-backed.

## Case

```text
OPEN        -> IN_PROGRESS
IN_PROGRESS -> WAITING | RESOLVED
WAITING     -> IN_PROGRESS
RESOLVED    -> CLOSED
```

`CLOSED` is terminal in S20; no reopen transition or endpoint exists.

## Verification and grant

```text
VerificationSession:
PENDING -> VERIFIED | LOCKED | EXPIRED | REVOKED
VERIFIED -> EXPIRED | REVOKED

VerificationChallenge:
ISSUE_PENDING -> DELIVERED | ISSUE_UNKNOWN | EXPIRED | REVOKED
DELIVERED -> VERIFYING -> VERIFIED | INVALID | VERIFY_UNKNOWN

DataAccessGrant:
REQUESTED -> APPROVAL_PENDING -> ACTIVE -> CONSUMED | EXPIRED | REVOKED
REQUESTED -> ACTIVE
APPROVAL_PENDING -> DENIED

BreakGlassRequest:
APPROVAL_PENDING -> ACTIVE | DENIED | REVOKED
ACTIVE -> REVIEW_PENDING | EXPIRED | REVOKED
REVIEW_PENDING -> REVIEWED
```

Invalid proof 5회는 Session을 `LOCKED`로 만들고 Case+Subject lockout을 별도 row에 보존한다. Case가
`RESOLVED` 또는 `CLOSED`가 되는 transaction은 active verification/access를 revoke하고 이후 activation/reveal을
거부한다. reveal을 이미 수행한 `REVIEW_PENDING` break-glass는 mandatory review 전까지 유지된다.

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
