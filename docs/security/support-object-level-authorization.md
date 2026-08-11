# Support Object-Level Authorization

> **Status:** `PARTIALLY IMPLEMENTED`; S20 Case, S40 verification/access and S50 timeline/action-evaluation rules are exact
> runtime contracts. Approval and execution authorization remain later-stage requirements.

## S20 Case authorization

Every S20 request checks an authenticated actor and active Operations-owned persistent permission. Create requires
`SUPPORT_CASE_WRITE` and initially assigns the Case to that actor. List/detail requires `SUPPORT_CASE_READ`. Assignment
requires `SUPPORT_CASE_ASSIGN`, an active target `SUPPORT_CASE_WRITE` grant and expected Case version. State transition,
interaction, note, link and unlink require `SUPPORT_CASE_WRITE`, current assignment and an active Case; versioned commands
also compare the supplied version. S20 SubjectLink stores only a typed owner ID and does not call an owner Context, so it
does not claim requester-subject, owner membership or target-existence verification.

## S40 verification and personal-data access

Verification create requires `SUPPORT_VERIFICATION_MANAGE`, current assignment, active Case and active SubjectLink.
The session is bound to its actor, Case, link, Subject and Purpose; get/challenge/verify/revoke reject another actor or
binding. Grant request adds `SUPPORT_PII_REVEAL_REQUEST` and sufficient matching BASIC/ENHANCED verification. Sensitive
Grant approval requires `SUPPORT_PII_REVEAL_APPROVE` from a different actor. Reveal requires the requester, current Case
assignment/state, active link, exact field scope and `SUPPORT_PII_REVEAL_BASIC` or
`SUPPORT_PII_REVEAL_SENSITIVE`; owner Contexts independently require subject ID and closed owner field vocabulary.

Break-glass requires `SUPPORT_BREAK_GLASS_REQUEST`, one emergency field and distinct
`SUPPORT_PII_REVEAL_APPROVE` pre-approval. Only the requester may consume the exact field; post-review requires
`PRIVACY_BREAK_GLASS_REVIEW` from an actor different from requester and approver. `RESOLVED`/`CLOSED` Case blocks new
activation/reveal and revokes active pre-reveal access.

## S50 timeline and action-evaluation authorization

Case timeline requires authenticated actor, active `SUPPORT_CASE_READ`, current assignment and a bounded active Order-link
set. Order timeline additionally requires `SUPPORT_ORDER_READ` and an exact active `RELATED_ORDER` link. Both paths recheck
permission and object scope after owner reads before returning. Timeline facts are closed and masked; a cursor or Order ID
does not confer visibility.

Action evaluation first requires the same Case/Order read scope. It then obtains current Ordering state/version through the
owner public API and rechecks current assignment/link, `SUPPORT_ACTION_REQUEST`, the action-specific capability grant and an
exact actor+Case action-scoped VerificationSession. CUSTOMER/STORE verification must match the owner snapshot and an allowed
`REQUESTER`/affected relationship. Missing capability, stale target, inactive verification, reveal scope reuse, wrong purpose,
closed Case or unsupported state/action returns a typed `DENIED`; missing read/object scope remains 403. Evaluation is
advisory and carries no execution authority.

## Future action execution authorization

Every future privileged action execution checks authenticated actor, active persistent permission, Case state/assignment, Subject
link, requester-subject relation, owner target ownership/membership, verification purpose/expiry, grant field scope and
target aggregate version.

Knowledge of order number, menu, amount, phone or delivery reference is not authentication. Search candidates are masked and do not establish ownership. A grant cannot be reused across Case, Subject, operator, field or purpose. Operations investigation remains masked unless its actor obtains a separate field grant.

Controllers enforce authentication and delegate to Application Services; Controllers never read repositories. Future
owner Context actions perform their final object/aggregate check. Missing object versus unauthorized object follows existing
endpoint-specific 404/403 conventions without leaking protected metadata.
