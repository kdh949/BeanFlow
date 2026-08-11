# Support Transaction Boundaries

> **Status:** Accepted transaction/failure principles with `PROPOSED` co-location and persistence mechanics pending each
> owning Stage.

## Implemented S10–S30 boundaries

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
- Verification/DataAccessGrant/reveal와 owner command transaction은 아직 구현되지 않았다. S40은 terminal Case Grant
  revocation과 terminal activation/reveal denial을 같은 Case boundary에 포함해야 한다.

## Local atomic candidates

- SupportCase state + append-only history (S20 implemented)
- verification outcome + attempt/lock update
- grant activation/reveal authorization + pre-reveal Audit when co-located
- ActionRequest revision + policy snapshot; ApprovalStep + request state
- owner-local pickup slot swap
- owner-local point/coupon issuance + compensation execution result
- provider webhook Inbox insert
- deletion component transition + deletion ledger result

## Cross-context orchestration

Support transaction stores intent and immutable references, then calls owner public Application API. No Support transaction updates owner tables. S30 owner APIs are read-only JDBC projections participating in Tx2; Support imports their public contracts and never their repositories/entities. If owner change and Audit share the same database/local transaction, high-risk change and target Audit commit together; otherwise an Accepted durability ADR is required before implementation.

External OTP/email/PG/Delivery/notification/object-storage calls occur outside long DB transactions. Intent/claim is committed before call and result is committed afterward. Timeout or ACK loss produces `UNKNOWN`/`RECONCILING`, never guessed success/failure. Notification failure after confirmed change leaves the change intact and schedules retry/manual review.
