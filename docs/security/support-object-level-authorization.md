# Support Object-Level Authorization

> **Status:** `PARTIALLY IMPLEMENTED`; the first section is the exact S20 Case rule. The later full matrix remains a
> future-stage requirement.

## S20 Case authorization

Every S20 request checks an authenticated actor and active Operations-owned persistent permission. Create requires
`SUPPORT_CASE_WRITE` and initially assigns the Case to that actor. List/detail requires `SUPPORT_CASE_READ`. Assignment
requires `SUPPORT_CASE_ASSIGN`, an active target `SUPPORT_CASE_WRITE` grant and expected Case version. State transition,
interaction, note, link and unlink require `SUPPORT_CASE_WRITE`, current assignment and an active Case; versioned commands
also compare the supplied version. S20 SubjectLink stores only a typed owner ID and does not call an owner Context, so it
does not claim requester-subject, owner membership or target-existence verification.

## Future action authorization

Every future privileged action checks authenticated actor, active persistent permission, Case state/assignment, Subject
link, requester-subject relation, owner target ownership/membership, verification purpose/expiry, grant field scope and
target aggregate version.

Knowledge of order number, menu, amount, phone or delivery reference is not authentication. Search candidates are masked and do not establish ownership. A grant cannot be reused across Case, Subject, operator, field or purpose. Operations investigation remains masked unless its actor obtains a separate field grant.

Controllers enforce authentication and delegate to Application Services; Controllers never read repositories. Future
owner Context actions perform their final object/aggregate check. Missing object versus unauthorized object follows existing
endpoint-specific 404/403 conventions without leaking protected metadata.
