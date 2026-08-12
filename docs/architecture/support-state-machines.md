# Support State Machines

> **Status:** `PARTIALLY IMPLEMENTED`; S20–S90 implemented state contracts are persisted/API-backed.

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

## Goodwill compensation

```text
AWAITING_APPROVAL -> READY_FOR_EXECUTION -> BENEFIT_ISSUED
                                          -> NOTIFICATION_ACCEPTED
                                          -> NOTIFICATION_RETRY -> NOTIFICATION_ACCEPTED
```

`AWAITING_APPROVAL`은 MEDIUM Support Manager 또는 HIGH/EXCEPTIONAL Operations exact revision에 묶인다.
benefit 발급과 terminal incident/rolling limit/Audit는 원자적이며 `BENEFIT_ISSUED` 전이는 하나뿐이다.
Notification 접수는 이 commit 뒤의 독립 경계라 실패해도 benefit state를 되돌리지 않는다. 응답의
`notificationState`는 Notification owner의 `PENDING|PROCESSING|SUCCEEDED|RETRY_SCHEDULED|MANUAL_REVIEW`를
별도로 보여 주며 Support의 `NOTIFICATION_ACCEPTED`는 전달 성공이 아니라 durable intent 접수만 의미한다.

## Delivery

See [Delivery state machine](delivery-state-machine.md). Physical lifecycle and ProviderSyncStatus never collapse into one status.
