# Support Transaction Boundaries

> **Status:** Accepted transaction/failure principles; S10–S100 boundaries below are implemented and later owner-command
> mechanics remain Stage-owned.

## Implemented S10–S70 boundaries

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
- S60 request create/revision은 Support local transaction에서 Case/request row와 persistent permissions/verification을
  잠그고 immutable revision, state, 90일 command idempotency response와 PII-free Audit를 함께 commit한다. 승인 시
  current Ordering version, requester capability, exact verification/policy/expiry를 다시 확인한다. 실패·권한 회수·stale
  target은 승인 성공으로 바꾸지 않고 `EXPIRED`/`STALE` lineage를 durable하게 남긴다. S60은 owner command를 실행하지
  않으며 `READY_FOR_EXECUTION`까지만 전이한다.
- S60 Operations investigation decision은 Operations-owned row를 잠근 뒤 required public Support callback을 같은 local
  transaction에서 호출한다. callback은 exact request/revision과 최신 policy/permission/verification/target binding을
  재확인하고 Support approval step을 저장한다. 두 owner 상태, idempotency와 Audit 중 하나라도 실패하면 전체가
  rollback되며 Operations 단독 성공은 없다. timeout·비동기 Provider 호출은 이 경계에 존재하지 않는다.
- S60 explicit reassignment는 request와 Case를 정해진 순서로 잠그고 exact revision/request/Case version, target의
  Case-write/action-execute/capability grant와 reviewer separation을 확인한다. SupportCase assignment, request executor,
  양쪽 append-only history, 두 Audit와 idempotency response가 한 transaction에서 commit한다. ready executor 권한이
  회수되면 조회가 `REASSIGNMENT_REQUIRED`를 materialize하며 자동 대체하지 않는다.
- S70 store authorization create는 Identity StoreMembership, policy version, actor separation과 STORE 책임을
  검사한 뒤 Support-owned immutable confirmation/delegation과 idempotency response/Audit를 한 local transaction에
  commit한다. Confirmation은 exact request/revision/action digest/target version/request expiry에 묶이고,
  delegation expiry/budget은 server policy로만 계산한다.
- S70 execution은 Support request/Case/authorization/permission row와 Ordering Order/Fulfillment reservation을
  owner public Application API를 통해 같은 local transaction에서 잠근다. Support가 owner repository/table을 직접
  쓰지 않으며, owner가 최신 state/version과 cancellation/refund 또는 new-slot-first swap 불변식을 최종 검증한다.
  owner change, terminal Support execution, authorization-use row와 PII-free Audits 중 하나라도 실패하면 전부
  rollback한다. `PREPARING`/`READY`/`COMPLETED` race는 Order를 바꾸지 않고 terminal `RESOLUTION_REQUIRED`만
  commit하며 authorization budget을 소비하지 않는다.
- pickup reschedule 성공은 같은 transaction에서 durable Notification `PENDING` intent만 생성한다. Provider worker는
  commit 뒤 별도 transaction/call 경계에서 `PROCESSING`, `SUCCEEDED`, `RETRY_SCHEDULED`, `MANUAL_REVIEW`를
  기록한다. Provider 실패는 slot swap을 되돌리거나 성공으로 위장하지 않는다.
- S80 plan transaction은 SupportActionRequest → SupportCase → ResolutionCase 순서로 잠그고 exact approved revision,
  assignment/separation, permission/verification/policy/expiry와 최신 Order version을 검증한다. immutable plan/steps,
  PII-free Audit와 생성 idempotency가 함께 commit하며 이 경계에서는 owner command를 호출하지 않는다.
- S80 execution은 짧은 Support transaction에서 승인 revision을 한 번 소비하고 다음 owner step claim을 commit한
  뒤 Payment/Loyalty/Promotion/Settlement/Notification public API를 transaction 밖에서 호출한다. 이어지는 짧은
  result transaction이 current claim, aggregate state와 Audit를 함께 commit한다. Audit/result commit 실패 뒤 owner
  success를 추정하지 않고 claim expiry에서 같은 source를 조회/replay한다. Payment timeout은 `UNKNOWN`, explicit
  operator reconciliation은 Payment lookup만 허용하며 다른 owner step을 blind reissue하지 않는다.
- 각 S80 owner는 owner-local transaction에서 최신 불변식과 exact source/payload를 최종 검증한다. 성공한
  Refund/restoration/Settlement adjustment는 이후 step 실패로 rollback하지 않는다. Notification intent는 financial
  terminal state 이후 독립적으로 생성되며 delivery 실패가 financial result를 되돌리지 않는다.
- S90 create는 current immutable policy head/version, Case/customer/order/verification/cost binding을 재평가하고
  `SupportCompensationRequest`, 필요한 S60 exact revision/Operations investigation, idempotency와 PII-free Audit를
  한 transaction에 commit한다. Head가 바뀐 뒤 새 요청은 새 version을 사용하지만 기존 요청은 저장된 immutable
  version으로 재평가하므로 정책 변경이 소급되지 않는다.
- S90 execute는 request/Case/approval을 잠근 뒤 CUSTOMER→ORDER→INCIDENT→ACTOR→STORE의 canonical 순서로
  scope lock을 획득하고 `issuedAt >= now-window` consumption 합을 검사한다. Loyalty/Promotion public owner API는
  `MANDATORY`로 같은 local transaction에 참여한다. PointLot/transaction 또는 Coupon issuance, terminal incident,
  다섯 consumption, S60 one-time consume, Support state, command idempotency와 financial Audit 중 하나라도 실패하면
  모두 rollback한다. Support는 owner Repository/table을 직접 쓰지 않는다.
- S90 Notification intent는 benefit commit 뒤 `REQUIRES_NEW` owner transaction에서 생성한다. 실패하면 terminal
  benefit/limit은 유지하고 Support를 `NOTIFICATION_RETRY`로 기록한다. Retry는 같은 logical source만 재사용하며
  Point/Coupon을 다시 발급하지 않는다.
- S100 preflight transaction은 Case/active subject link, bound VerificationSession, persistent purpose permission과 current
  owner version을 검사한다. R3/R4는 exact S60 revision/approval/assigned executor도 검사한다. Vault encrypt/HMAC 준비는
  이 transaction 밖에서 수행되며, idempotency replay는 owner version 조회나 crypto 전에 terminal Support 결과를
  반환해 이미 성공한 명령이 이후 target 변경 때문에 stale로 오판되지 않게 한다.
- S100 final transaction은 authorization/version/digest를 다시 검사하고 Identity/Merchant/Delivery public owner API를
  `MANDATORY`로 호출한다. owner current/history/index 또는 reset intent, Support result, S60 one-time consumption,
  idempotency와 PII-free Audit가 모두 commit되기 전에는 성공이 없다. Support는 owner Repository/table을 직접 쓰지
  않으며 Audit 실패는 owner change도 rollback한다.
- S100 Notification target/intents는 owner change commit 뒤 `REQUIRES_NEW` transaction에서 만든다. contact change는
  owner-local OLD/NEW snapshot을, 그 외 purpose는 CURRENT snapshot을 사용한다. persistence/Provider 실패는 terminal
  profile change를 되돌리지 않고 `NOTIFICATION_RETRY`/`MANUAL_REVIEW`로 남는다. retry는 같은 owner history/reset
  reference만 재사용하고 owner write 또는 approval consumption을 반복하지 않는다.

## Local atomic candidates

- SupportCase state + append-only history (S20 implemented)
- verification outcome + append-only attempt/lock update (S40 implemented)
- grant activation/reveal authorization + pre-reveal Audit (S40 implemented)
- ActionRequest revision + policy snapshot; ApprovalStep + request state (S60 implemented)
- owner-local pickup slot swap (S70 implemented)
- Resolution plan/claim/result + PII-free Audit (S80 implemented)
- owner-local point/coupon issuance + compensation execution result (S90 implemented)
- owner-local profile/history/reset write + Support/S60/Audit result (S100 implemented)
- provider webhook Inbox insert
- deletion component transition + deletion ledger result

## Cross-context orchestration

Support transaction stores intent and immutable references, then calls owner public Application API. No Support transaction updates owner tables. S30 masked owner APIs, S40 owner-local reveal APIs, S50 bounded timeline/snapshot APIs, S70 Ordering/Fulfillment owner commands and S100 typed Identity/Merchant/Delivery profile commands expose public contracts only; Support never imports their repositories/entities. S70 and S100 use the existing shared database transaction so owner change and Support/Audit durability commit together. A future separately deployed owner requires an Accepted durability ADR before implementation.

External OTP/email/PG/Delivery/notification/object-storage calls occur outside long DB transactions. Intent/claim is committed before call and result is committed afterward. Timeout or ACK loss produces `UNKNOWN`/`RECONCILING`, never guessed success/failure. Notification failure after confirmed change leaves the change intact and schedules retry/manual review.
