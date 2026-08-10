# Support Aggregate Responsibilities and Invariants

> **Status:** `DRAFT IMPLEMENTATION MODEL`; only invariants explicitly anchored to Accepted ADR-081/082/084-089 are
> Accepted. Aggregate names, fields and constraints are finalized by the owning Stage.

## Implemented S10 foundation

Operations는 Audit category/class/immutable policy version과 persistent `OperatorPermissionGrant` vocabulary를
소유한다. financial Audit 5년과 PII access Audit 2년은 검증됐지만 SupportCase와 reveal use case는 아직 없다.
따라서 dormant Support permission은 아래 Aggregate나 endpoint가 구현됐다는 의미가 아니다.

## SupportCase

Protects active/closed lifecycle, assignment, subject links and state history. Closed cases reject ordinary work; privileged action requires current assignment.

## VerificationSession and DataAccessGrant

Session binds Case+Subject+Purpose+action scope and never upgrades itself from BASIC to ENHANCED. Grant binds operator+case+subject+field+reason+expiry/reveal budget. Audit must commit before reveal; expiry, revocation, case closure and scope mismatch fail closed.

## SupportActionRequest and ApprovalStep

Revision snapshots canonical payload hash, target/policy/verification/aggregate versions and expiry. Any material change makes prior approvals stale. Requester, Support approver and Operations approver are distinct; reviewer cannot occupy two steps or execute. Exact key/revision uniqueness and actor separation require DB constraints plus service checks.

## CompensationRequest and ResolutionCase

One compensation request issues one benefit type and snapshots immutable policy/cost responsibility. Duplicate terminal benefit and rolling limit are transactionally prevented. Resolution preserves trigger Order facts; partial effects remain partially resolved; unknown responsibility never receives a default cost owner.

## DeliveryFulfillment

Canonical state never regresses, Provider sync is a separate axis, provider inbox receipt is unique, missing webhook fields do not erase known values, timeout stays unknown/reconciling and automatic cross-provider failover is forbidden.

## LegalHold and deletion

Hold must be scoped, separately approved, reviewed and expiring. Deletion records component outcomes and cannot report success while DB/Object/Index/Projection work is incomplete.
