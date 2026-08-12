# Support Approval and Separation-of-Duties Controls

R3 sequence is requester -> Support Manager -> Operations -> returned-to-agent execution. Requester, two reviewers and executor constraints are checked server-side; requester differs from both reviewers, reviewers differ from each other, one actor cannot fill both steps, and reviewer cannot execute.

Approval binds request ID/revision, action/target, canonical payload hash, verification/policy/aggregate versions, amount/reason/evidence digest and expiry. Revision, role/grant revocation or target version change invalidates unused approval. Reviewers never silently modify payload; they return for a new revision.

Exceptional compensation bypasses Support Manager approval and goes to Operations investigation. Operations approves/denies/returns/escalates but does not issue or reduce the benefit; approved work returns to an eligible Support agent. Inactive original agent requires explicit audited reassignment.

S60 stores only an action payload SHA-256 digest and opaque evidence digest, never raw action payload/evidence. Revision expiry
is the bound action VerificationSession expiry. Support Manager and Operations decisions recheck active persistent grants,
current revision/policy/target version and verification; client-supplied route, role or decision state is not authoritative.

Support owns request/revision/step/reassignment rows and Operations owns investigation rows. Cross-context handoff uses public
ports and a required callback in one database transaction. Missing callback, persistence or Audit failure rolls both owner
changes back. Approved lineage is not an execution success and no local/fake/no-op executor is substituted.
