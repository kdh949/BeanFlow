# Support Approval and Separation-of-Duties Controls

R3 sequence is requester -> Support Manager -> Operations -> returned-to-agent execution. Requester, two reviewers and executor constraints are checked server-side; requester differs from both reviewers, reviewers differ from each other, one actor cannot fill both steps, and reviewer cannot execute.

Approval binds request ID/revision, action/target, canonical payload hash, verification/policy/aggregate versions, amount/reason/evidence digest and expiry. Revision, role/grant revocation or target version change invalidates unused approval. Reviewers never silently modify payload; they return for a new revision.

Exceptional compensation bypasses Support Manager approval and goes to Operations investigation. Operations approves/denies/returns/escalates but does not issue or reduce the benefit; approved work returns to an eligible Support agent. Inactive original agent requires explicit audited reassignment.
