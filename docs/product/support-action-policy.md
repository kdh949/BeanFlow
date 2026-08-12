# Support ActionPolicy

> **Status:** Core ALLOWED/APPROVAL_REQUIRED/DENIED and exact approval binding are Accepted in ADR-084. S50 initial
> typed evaluator and advisory API are implemented; S60 request revision/approval/investigation is in progress.

## Decision

모든 privileged action은 서버가 `ALLOWED | APPROVAL_REQUIRED | DENIED` 중 하나로 결정한다. 정의되지 않은 조합은 `DENIED`다. 입력은 operator role와 persistent grant, Case/assignment, requester-subject relation, verification, action과 canonical payload, target state/version, 금액·최근 이력, policy version과 시각이다.

결과는 reason codes, required verification/permissions, approval plan, monetary/field limits, policy version과 expiry를 포함한다. UI evaluation은 안내용이며 실행은 최신 row/version과 payload로 다시 평가한다.

## Request and approval binding

SupportActionRequest revision은 action, target, canonical payload hash, verification session, policy version, aggregate version, amount, reason, evidence digest와 expiry에 묶인다. 값이 바뀌면 승인과 decision은 `STALE`이며 새 revision이 필요하다. 모든 변경 명령은 `Idempotency-Key`를 요구하고 같은 key+다른 payload는 409다.

S60 approval persistence는 raw action payload/evidence를 저장하지 않고 lowercase SHA-256 digest만 저장한다. revision
expiry는 bound action VerificationSession의 exact expiry이며 boundary는 `now >= expiresAt`이다. 현재 S50 Order matrix는
`NONE` 또는 `SUPPORT_MANAGER` route만 선택한다. `OPERATIONS`와 `SUPPORT_MANAGER_THEN_OPERATIONS`는 future typed
compensation/profile policy가 server-side로 선택할 때까지 dormant이고 client가 route를 보낼 수 없다.

승인 결과는 `READY_FOR_EXECUTION` lineage이며 실제 owner 변경 성공이 아니다. S70/S80/S90/S100 typed owner command가
동일 canonical payload digest와 최신 permission/verification/policy/target version을 다시 확인하고 approval을 consume한다.

S70 ACCEPTED direct change는 ActionPolicy approval 외에 SP-19의 exact store confirmation 또는 active
`support-order-change-policy/2026-08-12/v1` delegation을 요구한다. cancellation delegation은
10분/성공 1회, pickup reschedule은 30분/성공 3회이며 STORE 비용 책임 명시 수락이 없으면 실행하지
않는다. ActionPolicy approval은 store authorization이나 owner latest-state check를 대체하지 않는다.

범용 DB JSON/SpEL/Drools DSL은 도입하지 않고 typed Kotlin policy를 계획한다. Owner Context가 최종 불변식을 재검증하며 승인도 owner 거부를 우회하지 않는다.

## S50 initial policy

S50은 Order owner state/version을 사용하는 `ORDER_CANCELLATION`, `PICKUP_RESCHEDULE`,
`POST_ACCEPTANCE_RESOLUTION`만 활성화한다. `PENDING_PAYMENT`/`PAID`의 cancel/reschedule은 action-bound BASIC에서
`ALLOWED`, `ACCEPTED` cancel/reschedule과 `PREPARING`/`READY`/`COMPLETED` resolution은 action-bound
ENHANCED에서 `APPROVAL_REQUIRED`다. 그 밖의 조합은 `DENIED`다.

Action verification은 `SUPPORT_ACTION` scope와 `CASE_RESOLUTION` purpose를 사용한다. raw reveal의
`PERSONAL_DATA_REVEAL` scope와 서로 대체할 수 없다. Initial immutable policy identifier는
`support-action-policy/2026-08-12/v1`, evaluation TTL은 2분이다. 응답은 current target version, closed reason과
required permission/verification/approval을 포함하지만 실행 권한은 아니다.

구현은 client가 role, permission, verification level, relation, current owner state 또는 decision을 보내지 못하게 한다.
Support가 Case/current assignment/active Order link를 확인하고 Ordering public snapshot을 읽은 뒤 persistent grant와
action-bound session을 다시 잠가 평가한다. Owner query/persistence 장애는 `DENIED`로 축소하지 않고 503이며,
실행 endpoint가 생기는 S60 이후에는 같은 immutable evaluator와 최신 owner version을 다시 사용해야 한다.
