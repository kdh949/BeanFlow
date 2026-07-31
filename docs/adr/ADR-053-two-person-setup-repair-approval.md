# ADR-053: 누락 Refund 복구의 2인 승인

- **Status:** Accepted
- **Date:** 2026-07-31

## Context

ADR-052는 immutable recovery snapshot이 완전하고 Refund row만 누락된 경우에 한해
application-level 복구를 허용한다. 안전 guard가 있어도 이 명령은 외부 Provider
LOOKUP과 잠재적 환불 완료로 이어지는 금융 작업이다. 한 운영자의 오조작이나 계정
침해만으로 실행되면 방어층이 충분하지 않다.

## Decision

### Proposal

- 복구는 제안과 승인 두 단계로 분리한다.
- 제안자와 승인자는 모두 실행 시점에 활성 `PLATFORM_OPERATOR`여야 하고 서로 다른
  actor ID여야 한다.
- 제안 API는
  `POST /api/v1/operations/reprocessing-cases/{caseId}/repair-proposals`다.
- 제안 request는 `Idempotency-Key`와 non-blank `reason`만 받는다. 금액, Refund ID,
  source, Provider key와 복구 state를 입력받지 않는다.
- 제안 transaction은 ADR-052의 safe guard를 read validation하고 다음 immutable
  snapshot을 proposal에 저장한다.
  - case ID와 case version
  - Order ID와 cancellation aggregate version
  - Payment ID
  - recovery snapshot ID/version
  - cancellation Refund ID
  - requested amount
  - Refund source와 Provider key의 server-side fingerprint
  - proposed action `RECREATE_MISSING_CANCELLATION_REFUND`
- API 응답과 Audit summary에는 raw Provider key와 customer data를 노출하지 않는다.
- proposal 상태는 `PENDING_APPROVAL`이고 `createdAt`부터 30분 뒤 `expiresAt`에
  만료된다. 유효 구간은 `[createdAt, expiresAt)`이다.
- 같은 setup case에는 활성 proposal을 하나만 허용한다.

### Decision and execution

- 결정 API는
  `POST /api/v1/operations/reprocessing-repair-proposals/{proposalId}/decisions`다.
- request는 `decision = APPROVE | REJECT`와 non-blank `reason`,
  `Idempotency-Key`를 요구한다.
- self approval/rejection은 `409 REPROCESSING_APPROVER_MUST_DIFFER`로 거부한다.
- `REJECT`는 proposal을 `REJECTED`로 종결하고 금융·보상 상태를 바꾸지 않는다.
- `APPROVE` transaction은 `Order → Payment → proposal` 순서로 잠그고 활성 role,
  만료, proposal fingerprint와 ADR-052의 모든 safe guard를 다시 검증한다.
- 승인 시점이 `expiresAt` 이상이면 proposal을 `EXPIRED`로 종결하고
  `409 REPROCESSING_PROPOSAL_EXPIRED`를 반환한다.
- case/snapshot/source/금액/Refund 존재 여부가 제안 뒤 변했으면 `STALE`로 종결하고
  `409 REPROCESSING_PROPOSAL_STALE`을 반환한다. 새 제안으로 다시 검토해야 한다.
- 모든 검증이 통과한 승인 transaction만 Refund `RECONCILING`, next action
  `LOOKUP`, PAYMENT step `UNKNOWN`, setup case resolution, proposal `EXECUTED`와
  AuditRecord를 함께 commit한다.
- 외부 Provider LOOKUP은 승인 commit 뒤 worker가 수행한다.
- 두 결정 요청이 경쟁하면 proposal guarded transition과 IdempotencyRecord로 하나만
  terminal 결과를 만든다. 이미 terminal proposal은 같은 key/payload에는 최초 응답을
  재생하고 다른 요청에는 409를 반환한다.

### Audit and retention

- 각 상태 변경은 별도 append-only AuditRecord를 남긴다.
  - `PAYMENT_CANCELLATION_REPAIR_PROPOSED`
  - `PAYMENT_CANCELLATION_REPAIR_APPROVED_AND_EXECUTED`
  - `PAYMENT_CANCELLATION_REPAIR_REJECTED`
  - `PAYMENT_CANCELLATION_REPAIR_EXPIRED`
  - `PAYMENT_CANCELLATION_REPAIR_STALE`
- 제안 Audit는 proposer, 결정 Audit는 approver, 자동 만료는 SYSTEM actor를 기록한다.
  제안·결정의 수동 reason은 각각 보존한다.
- 승인 실행 Audit와 ADR-052의 missing Refund recreation Audit는 같은 correlation과
  proposal source reference로 연결하되 action별 record를 유지한다.
- Audit 저장 실패는 해당 proposal/repair transaction 전체를 rollback한다.
- proposal은 일반 애플리케이션 API로 수정·삭제하지 않고 운영 기록 보존 정책을
  따른다. 만료는 삭제가 아니라 terminal 상태다.

## Alternatives Considered

### 한 운영자 즉시 실행

- 복구 시간이 가장 짧고 workflow가 단순하다.
- 단일 계정 침해나 입력 실수가 금융 작업으로 바로 이어진다.

### application 복구 비활성

- 제품 내 오조작 위험이 없다.
- 완전한 immutable 입력이 있는 안전 복구도 매번 직접 데이터 조치가 필요하다.

## Rationale

서로 다른 두 actor와 승인 시점 재검증을 결합하면 단순한 UI 확인이 아니라 독립적인
권한·의도·현재 상태 검증이 된다. 짧은 만료 시간과 stale fingerprint는 오래된
제안으로 현재 금융 상태를 변경하는 위험을 줄인다.

## Consequences

- Operations에 repair proposal Aggregate, 두 command endpoint와 migration이
  추가된다.
- 복구에는 두 운영자와 최대 30분 내 협업이 필요하다.
- 승인 대기 중 상태가 변하면 새 제안이 필요하다.

## Failure Scenarios

- proposer와 approver가 같으면 2인 통제가 형식적이 된다.
- 승인 때 guard를 다시 검증하지 않으면 오래된 제안이 새 Refund와 충돌한다.
- raw Provider key를 proposal API에 노출하면 민감한 금융 연계 정보가 확산된다.
- proposal을 승인하고 Refund commit이 실패하면 실행된 것으로 보일 수 있다. 같은
  transaction으로 막는다.
- 만료 proposal을 되살리면 승인 기한 통제가 무의미하다.

## Verification

- 서로 다른 활성 operator 두 명만 실행 가능
- 30분 반개구간 만료 경계
- 승인 시 safe guard와 fingerprint 재검증
- 승인·Refund·step·case·Audit 원자성
- 동시 승인/거절의 단일 terminal 결과
- Provider 호출의 승인 transaction 밖 실행

## Required Tests

- proposer/approver 동일, 비활성, 잘못된 role
- 제안 생성 same/different idempotency replay
- approve/reject 동시성
- `expiresAt - 1ns`, `expiresAt` 경계
- 제안 뒤 Refund 생성, snapshot version 변경과 source conflict의 STALE
- 승인 저장 지점별 failure injection과 전체 rollback
- Audit action·actor·reason·correlation
- DTO와 log의 raw Provider key/customer/detail 부재
- 승인 commit 시 Provider 호출 0회와 이후 LOOKUP

## Metrics

- `beanflow.operations.payment_setup.proposal.count{state}`
- `beanflow.operations.payment_setup.proposal.age.seconds{state}`
- `beanflow.operations.payment_setup.approval.count{outcome}`

Operator, Order, Payment, Refund와 Provider 식별자는 metric tag로 사용하지 않는다.

- **Not measured:** 제안부터 승인까지 실제 소요시간

## Revisit Conditions

조직의 금융 승인 정책이 별도 RBAC role, 금액 구간별 승인자 수 또는 외부 ticket
연계를 요구할 때

## Related Decisions

- BR-14, BR-25, BR-30
- [ADR-022](ADR-022-audit-record.md)
- [ADR-027](ADR-027-store-membership-authorization.md)
- [ADR-032](ADR-032-customer-cancellation-idempotency.md)
- [ADR-052](ADR-052-safe-setup-repair-scope.md)
