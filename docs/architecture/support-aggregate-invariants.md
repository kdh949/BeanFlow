# Support Aggregate Responsibilities and Invariants

> **Status:** `PARTIALLY IMPLEMENTED`; S20 SupportCase, S40 Verification/DataAccessGrant/BreakGlass, S60
> SupportActionRequest approval, S70 order-change execution/authorization and S80 post-acceptance resolution rules are persisted and tested.
> Future Aggregate names and constraints remain Stage-owned planning inputs unless anchored by an Accepted ADR/policy.

## Implemented S10 foundation

Operations는 Audit category/class/immutable policy version과 persistent `OperatorPermissionGrant` vocabulary를
소유한다. S40 reveal은 Operations public API로 `PII_ACCESS` Audit를 먼저 commit하며 Support는 Operations
table을 직접 쓰지 않는다. 아직 owning use case가 없는 permission은 계속 dormant다.

## SupportCase

Protects the S20 active/closed lifecycle, assignment, subject links and state history. `CLOSED` rejects every ordinary
Case mutation; Case transitions use the Accepted no-reopen matrix. Assignment and state histories append in the same
transaction as Case version changes. Interactions, notes and links remain separate rows, never a Case collection.
The database additionally rejects a closed timestamp later than `last_changed_at`, so a persisted row always satisfies
Aggregate reconstitution time ordering.

Every S20 mutating command is replay-scoped by `(actorId, operation, Idempotency-Key)`. Its named, typed,
length-prefixed payload representation prevents free-text field-boundary collisions; only the 90-day terminal replay
record is retained. The Support-owned cleanup selects due rows in `(retention_expires_at, id)` keyset order, locks a
bounded chunk with `SKIP LOCKED`, and propagates failures for a later scheduled retry.

## VerificationSession and DataAccessGrant

`VerificationSession`은 actor+Case+active SubjectLink+Subject+Purpose+`PERSONAL_DATA_REVEAL` action scope와
requested level에 고정된다. 15분 Session과 5분 Challenge는 경계 시각부터 만료되고 invalid proof 5회는 같은
Case+Subject에 30분 lockout을 만든다. BASIC은 등록 채널 하나, ENHANCED는 서로 다른 두 채널의 성공을 요구한다.
Provider reference/outcome만 저장하며 OTP, raw link와 proof는 저장하지 않는다.

`DataAccessGrant`는 requester+Case+SubjectLink+Subject+Purpose+closed field set+structured reason+expiry+reveal
budget에 고정된다. BASIC field는 10분/3회, SENSITIVE field는 ENHANCED verification과 distinct approver 뒤
5분/1회다. reveal은 exact subset만 허용하고 Audit와 attempt reservation이 commit된 뒤 owner public API에서만
decrypt한다. downstream 실패도 이미 예약된 budget을 되돌리지 않는다. raw 성공 body는 idempotency row에
저장하지 않아 같은 key replay는 `IDEMPOTENCY_MANUAL_REVIEW_REQUIRED`다.

`BreakGlassRequest`는 normal level/Grant와 별개이며 one emergency field, distinct pre-approver, 2분/1회,
distinct mandatory post-review와 durable PII-free notification intent를 보호한다. Case가 `RESOLVED` 또는
`CLOSED`로 전이하면 active Session/Challenge/Grant와 pre-reveal break-glass를 같은 Support transaction에서
revoke한다. reveal 뒤 `REVIEW_PENDING`은 감사 의무 보존을 위해 자동 해제하지 않는다.

## SupportActionRequest and ApprovalStep

S60 implements this boundary. A revision snapshots action/target, action and evidence SHA-256 digests,
VerificationSession, immutable policy version, target version, amount, bounded reason, creator and exact expiry. Any material
change creates the next revision and leaves prior steps immutable/stale. Request state is closed over manager/Operations wait,
ready, reassignment required, revision required, denied, expired, stale and manual review.

Requester, Support approver and Operations approver are distinct; one reviewer cannot occupy two steps and no approver may
execute. Approval is one-time and exact-revision-bound. The Operations investigation is a separate Operations Aggregate and
can return only through its required Support owner callback. Executor permission loss materializes
`REASSIGNMENT_REQUIRED`; explicit reassignment atomically updates the SupportCase assignment and request executor after exact
request/Case versions and target eligibility are checked. DB uniqueness/check constraints plus pessimistic/advisory locks protect
revision lineage, one terminal step, investigation replay and command idempotency. S60 never executes Ordering or another owner.

## Support order-change execution and authorization

S70 extends only `ORDER_CANCELLATION` and `PICKUP_RESCHEDULE` ready revisions. A request can acquire one terminal
execution ID and moves to `EXECUTED` only after the owner command commits, or to `RESOLUTION_REQUIRED` when latest
Ordering state is `PREPARING`, `READY` or `COMPLETED`. Exact actor+operation+idempotency replay returns the same terminal
resource; another payload cannot consume or replace it. The execution stores closed before/after state, version, slot and
payment-recovery summaries, never raw cancellation text or personal data.

`SupportOrderChangeAuthorization` is an immutable store+action+policy binding. Confirmation additionally binds exact
request/revision/action digest/target version/request expiry; delegation has server policy TTL and successful-use budget.
Only an owner change committed in the same transaction inserts one unique authorization-use row and increments the
budget. Replay, validation failure, slot conflict, rollback and `RESOLUTION_REQUIRED` do not consume a use. Expired,
exhausted, revoked or scope-mismatched authorization never falls back to local approval. Store authorizer identity and
STORE responsibility are retained as opaque identifiers/closed values and separation from all Support actors is enforced.

## PostAcceptanceResolutionCase

S80 consumes one exact approved `POST_ACCEPTANCE_RESOLUTION` revision and binds it to the SupportCase, immutable
revision/action digest and PREPARING/READY/COMPLETED Order fact. The aggregate owns five typed steps, claim leases,
attempt count and explicit partial/unknown/reconciling/manual states. It never changes the Order fact. Requester,
reviewers and executor separation is inherited from and rechecked against the consumed S60 request.

Payment, Point, Coupon, Settlement and Notification effects use immutable owner source/payload bindings. Support records
an owner result only with its current claim and PII-free Audit. Owner success remains durable if a later step fails;
expired Support claims become `UNKNOWN` and are reconciled against the same owner source instead of reissued.
`UNDETERMINED` permits approved customer-value steps but fixes Settlement to `BLOCKED`; only STORE/SHARED can append
an exact negative Settlement adjustment. Notification is independent and cannot regress the financial state.

## CompensationRequest

One compensation request issues one benefit type and snapshots immutable policy/cost responsibility. Duplicate terminal benefit and rolling limit are transactionally prevented.

## DeliveryFulfillment

Canonical state never regresses, Provider sync is a separate axis, provider inbox receipt is unique, missing webhook fields do not erase known values, timeout stays unknown/reconciling and automatic cross-provider failover is forbidden.

## LegalHold and deletion

Hold must be scoped, separately approved, reviewed and expiring. Deletion records component outcomes and cannot report success while DB/Object/Index/Projection work is incomplete.
