# ADR-069: Operator permission grant와 감사형 정책 조회

- **Status:** Accepted
- **Date:** 2026-08-01
- **Implementation owners:** [Plan 10](../exec-plans/active/customer-order-cancellation-10-partial-refund-allocation-foundation.md), [Point adjustment plan](../exec-plans/active/loyalty-point-adjustment-foundation.md)

## Context

Authorization Matrix와 ADR-066은 Platform Operator role만으로는 만료 혜택 정책을 조회·변경하거나
포인트 조정을 실행할 수 없고, 명시적 permission과 reason을 요구한다. 현재 JWT converter는
`roles` claim만 권한으로 바꾸며 Operations policy controller도 role만 검사한다. 정책 GET은
reason을 전달하거나 access audit을 남길 공개 계약이 없다.

role을 permission으로 자동 대체하면 Accepted 보안 규칙이 무의미해진다. 반대로 권한 grant
조회 장애를 role 또는 JWT의 임의 claim으로 대체하면 revoke와 장애를 성공으로 위장한다.

## Decision

explicit operator permission의 source of truth는 Operations가 소유하는 DB-backed
`OperatorPermissionGrant`다. JWT의 `sub`와 `roles`는 인증과 coarse role gate에만 사용하며,
`permissions` claim은 권한 source 또는 fallback으로 사용하지 않는다.

### Grant model

- `OperatorPermissionGrant`는 `actor_id`, closed `permission`, `ACTIVE|REVOKED` state,
  `granted_at`, `revoked_at`, version과 Audit source를 저장한다.
- `(actor_id, permission)`는 unique다. 활성 grant가 없으면 permission은 없다. Platform Operator
  role 또는 다른 grant에서 기본 permission을 seed하거나 추론하지 않는다.
- MVP permission vocabulary는 `EXPIRED_BENEFIT_POLICY_READ`,
  `EXPIRED_BENEFIT_POLICY_WRITE`, `POINT_ADJUSTMENT`다. 새 privileged operation은 별도
  ADR 또는 vocabulary amendment 없이 이 권한을 재사용하지 않는다.
- JWT `sub`는 UUID actor ID여야 하고 `roles`에는 `PLATFORM_OPERATOR`가 있어야 한다.
  이 둘 중 하나가 없으면 permission lookup 전에 403이다. Authentication signature, issuer,
  audience, expiry는 resource-server의 기존 JWT validation으로 계속 검증한다.

### Enforcement and revocation boundary

- Controller/Method Security는 role을 빠른 진입 gate로 사용할 수 있지만, privileged use case는
  반드시 Application Service에서 `OperatorPermissionAuthorization.requireActive(actorId, permission)`을
  호출한다. Controller가 Repository를 직접 조회하지 않는다.
- require call은 해당 command 또는 audited read의 local transaction에 참여하고 활성 grant row를
  잠근다. revoke와 command/read 중 하나가 먼저 commit되어 선형화된다. revoke commit 뒤 새
  privileged transaction은 허용되지 않는다.
- grant query, lock 또는 audit persistence가 실패하면 403으로 번역하거나 role/claim으로
  fallback하지 않고 `503 DEPENDENCY_UNAVAILABLE`을 반환한다. active grant 부재와 role mismatch는
  `403 ACCESS_DENIED`다.
- 권한 결과를 process cache, in-memory map 또는 long-lived JWT claim으로 cache하지 않는다.
  따라서 grant revoke의 적용 지연은 DB commit 경합 이외에는 없다. grant 관리 API/UI는 이
  decision의 구현 범위 밖이지만, 직접 DB 수정은 허용된 grant management workflow와 Audit 없이
  수행하지 않는다.

### Expired-benefit policy API reason contract

- `GET /operations/policies/expired-benefit-restoration`는
  `EXPIRED_BENEFIT_POLICY_READ` grant와 required `X-Access-Reason` header를 요구한다.
- header는 trim 뒤 1..200자이며 control character를 금지한다. 원문 header를 application log,
  metric tag 또는 event payload에 복제하지 않는다.
- Operations Application Service는 grant 검증, current five policy-head query와
  `EXPIRED_BENEFIT_POLICY_READ` AuditRecord를 같은 local transaction에서 저장한다. Audit target은
  `EXPIRED_BENEFIT_POLICY_HEAD_SET`의 안정적 singleton ID다. Audit save가 실패하면 policy body를
  반환하지 않는다.
- PATCH는 `EXPIRED_BENEFIT_POLICY_WRITE` grant와 기존 request body의 non-blank `reason`을
  요구한다. read header를 PATCH의 새/중복 reason으로 사용하지 않는다.
- `POST /operations/point-accounts/{accountId}/adjustments`는 `POINT_ADJUSTMENT` grant와 기존
  request body reason/evidence를 요구한다. grant 검증은 PointAccount lock 뒤 같은 command
  transaction에 참여하며 Account/Lot/ledger/Audit/outbox/201과 분리되지 않는다.

Plan 10은 grant schema, Operations public authorization API, policy GET header/audit contract와
policy PATCH enforcement을 단독 구현한다. Point adjustment plan은 Plan 10 outcome을 소비하고
`POINT_ADJUSTMENT` enforcement을 구현한다. 두 계획은 같은 grant migration을 만들지 않는다.

## Alternatives Considered

### JWT `permissions` claim만 사용

구현이 작지만 grant revoke는 token expiry까지 지연되고, 현재 claim이 없을 때 role fallback으로
흐르기 쉽다. 즉시 revoke가 필요한 금전성 운영 명령에는 부족하다.

### Short-lived claim과 server grant를 조합

claim과 DB version을 모두 검증하면 defense-in-depth가 가능하다. 그러나 MVP에 필요한 grant
source와 revocation semantics만으로도 충분한데 token issuance/version mismatch라는 추가 장애
경로를 만든다.

### 정책 GET에 reason을 요구하지 않음

기존 API는 유지되지만 Authorization Matrix의 조회·변경 reason 요구를 조용히 약화시키고
민감 운영 정책 접근의 목적을 감사할 수 없다.

## Rationale

Operations-owned grant는 역할과 세분 권한을 분리하고 revoke를 persistence transaction으로
정확하게 표현한다. Grant, reason과 audit을 같은 Application Service boundary에 두면 current
role-only controller가 보안 source of truth를 우회하지 못한다. 조회도 감사 실패를 숨기지 않기
위해 body 반환의 commit gate로 둔다.

## Consequences

- role만 가진 Platform Operator는 명시 grant가 생기기 전 policy 또는 point adjustment를
  실행·조회할 수 없다.
- Plan 10은 Operations schema/API scope가 늘며 point-adjustment plan의 선행조건이 된다.
- policy GET은 새로운 required header와 400 validation contract를 가지며, existing clients는
  header를 보내도록 변경해야 한다.
- Operations DB 장애는 privileged API를 503으로 실패시킨다. 읽기 성공 또는 role-only access로
  대체하지 않는다.

## Verification

- role만 있음, grant만 있음, role+active grant, revoked grant와 malformed UUID subject를 구분한다.
- grant revoke와 policy PATCH/point adjustment의 동시 실행에서 commit 순서에 맞는 하나의 결과만
  나온다.
- permission repository/Audit failure가 403·200·201으로 위장되지 않고 503 및 rollback을 남긴다.
- GET의 missing/blank/control-character `X-Access-Reason`은 400이며 Audit이 없다.
- 정상 GET은 five heads와 exactly one access Audit을 같이 commit한다.
- policy PATCH 및 point adjustment가 각각 올바른 explicit permission과 body reason/evidence를
  요구하고, 다른 permission으로 통과하지 않는다.

## Metrics

- `beanflow.operations.permission.check.count{permission,outcome}`
- `beanflow.operations.policy.read.count{outcome}`
- `beanflow.operations.permission.grant.revoke.count{permission,outcome}`

actor ID, access reason, evidence, Idempotency-Key와 policy content는 metric tag나 log field에
넣지 않는다.

## Revisit Conditions

Identity provider가 authoritative permission claim과 individual revocation version을 제공하거나,
break-glass access, two-person approval 또는 external authorization service가 필요해질 때
claim/server-grant 조합을 별도 ADR로 재검토한다.

## Related Decisions

- BR-10, BR-25, BR-26, BR-30
- [ADR-022](ADR-022-audit-record.md)
- [ADR-064](ADR-064-risk-based-idempotency-model-selection.md)
- [ADR-066](ADR-066-audited-loyalty-point-adjustment.md)
