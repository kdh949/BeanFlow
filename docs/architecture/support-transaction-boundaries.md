# Support Transaction Boundaries

> **Status:** Accepted transaction/failure principles; S10–S50 boundaries below are implemented and later owner-command
> mechanics remain Stage-owned.

## Implemented S10–S50 boundaries

- privileged use case가 Audit를 쓰면 caller-local transaction에서 current retention policy head를 잠그고
  immutable policy version/category/class/expiry를 snapshot한다. policy/Audit 실패는 caller를 rollback한다.
- operator authorization은 caller-local transaction에서 persistent actor/permission row를 잠근다. revoke와
  authorization은 같은 row에서 직렬화되며 role/JWT claim은 grant 조회 실패의 fallback이 아니다.
- S20 Case create/assignment/transition/interaction/note/link/unlink은 Support local transaction으로 구현됐다.
  Operations persistent permission과 retention policy head를 잠그고, SupportCase advisory/row lock, immutable history,
  idempotency response와 PII-free lifecycle Audit를 함께 commit한다. idempotency advisory lock/replay lookup은
  `(actorId, operation, Idempotency-Key)` scope로 직렬화하고 terminal response에는 `created_at + 90일` expiry를
  함께 저장한다. 권한·정책·Audit·DB 실패는 모두 rollback한다.
  read permission도 persistent grant row를 잠그므로 S20 list/detail은 read-only transaction을 사용하지 않는다.
- current-assignee가 아닌 actor의 transition은 Aggregate까지 내리지 않고 object authorization 단계에서 403으로
  종료한다. input validation은 400, stale version/허용되지 않은 transition은 409로 분리하며 어느 실패도
  history, idempotency 또는 Audit를 남기지 않는다.
- Support-owned retention worker는 별도 짧은 transaction에서 due terminal idempotency row를 최대 100개씩
  `(retention_expires_at, id)` 순서로 `FOR UPDATE SKIP LOCKED` 삭제한다. 오류를 0건 성공으로 바꾸지 않아 다음
  scheduled tick이 재시도한다.
- S30 exact search Tx1은 active `SUPPORT_SUBJECT_SEARCH` grant를 확인하고 actor/5-minute rate row를 PostgreSQL
  upsert로 원자 증가시킨다. 제한된 요청도 Vault 전에 429로 종료하며, 허용된 시도는 이후 실패하더라도 소비된다.
  Vault Transit HMAC 호출은 DB transaction 밖에서 모든 configured search version에 대해 수행된다. Tx2는 grant를
  다시 확인하고 owner public APIs의 masked projections만 bounded 조회한 뒤 PII-free `PII_ACCESS` Audit와 함께
  commit한다. Audit/owner DB failure는 Tx2를 rollback하고 503을 반환한다. genuine no-match만 audited empty 200이다.
- S40 challenge issue/verify는 Tx1 intent/claim, Identity Provider call, Tx2 result로 나뉜다. Provider timeout,
  malformed result와 ACK loss는 `UNKNOWN`이며 local/test provider가 production fallback이 되지 않는다. Process
  중단으로 Tx2가 실행되지 않은 expired `PENDING_ISSUE`/`VERIFYING`은 recovery worker가 Case→Session→Challenge
  순서로 잠그고 explicit unknown outcome, attempt, idempotency receipt와 Audit를 같은 transaction에 기록한다.
- normal/break-glass reveal은 TxR1에서 current Case assignment/state, active SubjectLink, persistent permission,
  verification 또는 emergency binding, exact field scope, expiry/budget을 다시 검사한다. `RevealAttempt` reservation과
  PII-free Audit가 함께 commit된 뒤 owner public API가 owner ciphertext를 짧게 읽고 Vault decrypt를 transaction
  밖에서 수행한다. TxR2가 `REVEALED`/`FAILED`를 commit하고 성공 commit 뒤에만 Controller가 raw DTO를 반환한다.
  TxR2는 owner 호출 뒤 Case assignment/state, active SubjectLink와 persistent permission을 다시 잠금·검사한다.
  TxR1 이후 owner/TxR2 실패나 두 transaction 사이의 revoke/reassignment는 raw response가 없고 budget은 소비된다.
- Case `RESOLVED`/`CLOSED` transition은 같은 Support transaction에서 active Session/Challenge/Grant와 아직
  reveal되지 않은 break-glass를 revoke한다. Provider/security notification 호출은 이 transaction에 포함하지 않는다.
- break-glass request/approval/reveal은 각각 durable PII-free security notification intent를 commit한다. Worker는
  `SKIP LOCKED`로 claim하고 Provider를 transaction 밖에서 호출하며 `RETRY_SCHEDULED`, `MANUAL_REVIEW`, `SENT`를
  명시한다. 중단된 `PROCESSING` claim은 5분 뒤 재회수한다. Provider가 없으면 fake/no-op 성공으로 바꾸지 않는다.
- S50 timeline은 Tx1에서 persistent read permission, current Case assignment와 active Order links를 잠금·확인한 뒤
  transaction 밖에서 source당 최대 한 번 owner public query를 호출한다. bounded fact를 global tuple로 merge한 뒤
  Tx2에서 permission/assignment/link set을 다시 확인하고 같을 때만 no-store DTO를 반환한다. 중간 revoke/relink는
  403이며 owner 실패는 partial/empty 200이 아니라 503이다.
- S50 Action evaluation은 TxA1에서 Case/Order read scope를 확인하고 transaction 밖에서 Ordering public
  state/version snapshot을 읽는다. TxA2는 Case/assignment/link, generic/capability persistent grants와 exact
  VerificationSession을 다시 잠근다. immutable typed evaluator는 그 snapshot만으로 advisory decision을 만들며
  어떤 owner write도 수행하지 않는다. UI response는 2분 expiry/current owner version을 포함하고 실행 권한이 아니다.

## Local atomic candidates

- SupportCase state + append-only history (S20 implemented)
- verification outcome + append-only attempt/lock update (S40 implemented)
- grant activation/reveal authorization + pre-reveal Audit (S40 implemented)
- ActionRequest revision + policy snapshot; ApprovalStep + request state
- owner-local pickup slot swap
- owner-local point/coupon issuance + compensation execution result
- provider webhook Inbox insert
- deletion component transition + deletion ledger result

## Cross-context orchestration

Support transaction stores intent and immutable references, then calls owner public Application API. No Support transaction updates owner tables. S30 masked owner APIs, S40 owner-local reveal APIs and S50 bounded timeline/snapshot APIs expose public contracts only; Support never imports their repositories/entities. If a future owner change and Audit share the same database/local transaction, high-risk change and target Audit commit together; otherwise an Accepted durability ADR is required before implementation.

External OTP/email/PG/Delivery/notification/object-storage calls occur outside long DB transactions. Intent/claim is committed before call and result is committed afterward. Timeout or ACK loss produces `UNKNOWN`/`RECONCILING`, never guessed success/failure. Notification failure after confirmed change leaves the change intact and schedules retry/manual review.
