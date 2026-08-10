# Support Object-Level Authorization

Every request checks authenticated actor, active persistent permission, Case state/assignment, Subject link, requester-subject relation, owner target ownership/membership, verification purpose/expiry, grant field scope and target aggregate version.

Knowledge of order number, menu, amount, phone or delivery reference is not authentication. Search candidates are masked and do not establish ownership. A grant cannot be reused across Case, Subject, operator, field or purpose. Operations investigation remains masked unless its actor obtains a separate field grant.

Controllers enforce coarse roles and delegate to Application Services; Controllers never read repositories. Owner Context performs final object/aggregate check. Missing object versus unauthorized object follows existing endpoint-specific 404/403 conventions without leaking protected metadata.
