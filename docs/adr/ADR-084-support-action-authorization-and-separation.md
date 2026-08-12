# ADR-084: risk-based Support action, exact approval binding과 Operations handoff

- **Status:** Accepted
- **Date:** 2026-08-10

## Context

역할 하나로 cancellation, compensation, PII와 profile change를 허용할 수 없고 고위험 승인도 payload/version과 분리되면 stale 실행·자기승인이 가능하다.

## Decision

서버 ActionPolicy는 role+persistent grant+Case/relationship+verification+target state/version+amount/history를 평가해 ALLOWED/APPROVAL_REQUIRED/DENIED를 반환하며 unknown은 DENIED다. Approval은 exact revision/payload hash/verification/policy/aggregate version/amount/evidence/expiry에 bind한다. R3는 distinct requester→Support Manager→Operations→distinct eligible agent execution이다. Exceptional compensation은 Support Manager가 아니라 Operations investigation으로 이관되고 decision은 payload 수정 없이 agent에게 반환된다.

S50 initial typed policy는 `ORDER_CANCELLATION`, `PICKUP_RESCHEDULE`, `POST_ACCEPTANCE_RESOLUTION`만 활성화한다.
Order action verification은 S40의 raw reveal proof와 구분된 `SUPPORT_ACTION` scope, `CASE_RESOLUTION` purpose,
exact Case/Subject/actor에 bind한다. `PERSONAL_DATA_REVEAL` verification은 action authorization으로 재사용할 수
없고 반대도 같다. Evaluation은 target owner의 현재 state/version과 Order-Customer/Store relationship을 서버에서
조회하며 client-supplied role, verification level, history 또는 policy decision은 입력으로 받지 않는다.

- `PENDING_PAYMENT`/`PAID` cancellation 또는 pickup reschedule은 BASIC 이상에서 `ALLOWED`다.
- `ACCEPTED` cancellation 또는 pickup reschedule은 ENHANCED에서 `APPROVAL_REQUIRED`다.
- `PREPARING`/`READY`/`COMPLETED` post-acceptance resolution은 ENHANCED에서 `APPROVAL_REQUIRED`다.
- state/action 조합, scope, purpose, relation, permission, verification 또는 target version이 위 조건을 충족하지
  않으면 `DENIED`다.

S50 policy identifier `support-action-policy/2026-08-12/v1`은 immutable code contract다. 후속 변경은 기존
identifier의 의미를 바꾸지 않고 새 version/evaluator를 추가한다. 평가 결과는 current target version과 2분 expiry를
포함하고 UI가 보관한 결과는 실행 권한이 아니다. S60 이후 실행은 최신 row와 exact payload로 같은 typed evaluator를
다시 호출해야 한다.

### S60 approval lineage amendment (2026-08-12)

`SupportActionRequest`는 Case/action/target과 execution 후보를 소유하고 revision은 action/target, SHA-256 action
payload digest, VerificationSession, policy/target version, amount, reason, evidence digest와 expiry를 immutable하게
snapshot한다. raw action payload와 raw evidence는 approval persistence에 복제하지 않는다. 후속 typed owner command는
실행 시 자기 canonical payload digest를 다시 계산해 revision digest와 비교한다.

Revision expiry는 별도 TTL을 만들지 않고 bound `SUPPORT_ACTION` VerificationSession의 exact `expiresAt`이다.
`now >= expiresAt`이면 승인할 수 없다. material binding이 바뀌면 새 revision이고 이전 unused step은 `STALE`다.
Approval route는 `NONE | SUPPORT_MANAGER | OPERATIONS | SUPPORT_MANAGER_THEN_OPERATIONS` closed enum이다. S50의 현재
Order action matrix는 `NONE` 또는 `SUPPORT_MANAGER`만 활성화하며 Operations route는 S90/S100 typed policy가 선택할 때까지
dormant다. Client는 route나 decision을 선택하지 않는다.

Support와 Operations는 자기 table만 직접 쓴다. Support는 Operations public open port로 조사를 생성하고 Operations
decision은 Operations API가 선언한 required callback을 Support implementation이 처리한다. 같은 DB transaction에서
각 owner state와 Audit을 commit해 반환 lineage가 어긋나지 않게 하며 callback/Audit 실패는 전체 rollback이다.
Operations는 `APPROVE | DENY | RETURN_FOR_REVISION | ESCALATE`만 결정하고 payload는 수정하지 않는다.

S60은 approved revision을 `READY_FOR_EXECUTION`으로 반환하는 데서 끝난다. 실제 owner effect와 approval consumption은
S70/S80/S90/S100의 typed command가 최신 permission/verification/policy/target version과 digest를 다시 검증한 뒤 소유한다.
원 executor가 execute/capability grant를 잃으면 자동 대체하지 않고 `REASSIGNMENT_REQUIRED`로 남기며, 권한 있는 actor가
적격 상담원으로 Case와 request를 함께 감사 가능한 방식으로 재배정한다. Support/Operations approver는 executor가 될 수 없다.

## Alternatives Considered

- UI/role boolean: every-request/object authorization을 만족하지 못해 기각.
- 범용 rules engine: 초기 정책 검증/운영 비용이 과도해 기각.
- 승인자가 payload 수정/실행: audit lineage와 separation을 깨서 기각.

## Rationale

정책 설명 가능성, stale 방지와 조직 간 견제를 한 immutable request lineage로 묶는다.

## Consequences

승인 시간이 늘고 revision/reassignment 상태가 필요하다. UI decision은 안내일 뿐 실행 시 재평가한다.
S50은 generic rule engine이나 policy JSON table을 만들지 않으며, owner query 실패를 `DENIED`나 빈 결과로 바꾸지
않는다. S40 verification table의 closed action-scope vocabulary는 `SUPPORT_ACTION`을 추가해야 한다.

## Verification

Role matrix, same actor/step/execute DB+service checks, stale/revoke/version race, changed payload 409, Operations return flow.

## Metrics

Decision 분포, approval duration/return/stale/separation denial, investigation outcome.

## Revisit Conditions

실제 승인 bottleneck, fraud pattern 또는 policy change frequency가 typed policy 한계를 증명할 때.

## Related Decisions

ADR-053, ADR-064, ADR-069.
