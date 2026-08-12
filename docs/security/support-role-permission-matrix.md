# Support Role and Permission Matrix

> **Status:** `PARTIALLY IMPLEMENTED`; S20 Case는 `SUPPORT_CASE_READ`, `SUPPORT_CASE_WRITE`,
> `SUPPORT_CASE_ASSIGN`, S30 exact search는 `SUPPORT_SUBJECT_SEARCH`, S40 verification/reveal/break-glass와 S50
> timeline/action evaluation, S60 request/approval/investigation/reassignment, S70 order change와 S80 resolution execution은 아래
> 전용 persistent grant를 사용한다.
> Role bundle과 이후 Support capability는 DRAFT다.

Roles are coarse bundles; S20–S80 use case는 active Operations-owned persistent grant를 요구한다.
S30은 추가로 structured reason과 persistent actor rate budget을 요구하고 Vault 호출 뒤 grant를 재확인한다. S50
action evaluation은 action-bound verification과 latest ActionPolicy까지 추가로 요구한다. JWT role alone is never a
fallback.

| Capability | Agent | Supervisor | Specialist | Manager | Operations | Privacy Auditor |
|---|---:|---:|---:|---:|---:|---:|
| Case read/write/assign | R/W | R/W/A | R/W | R/W/A | scoped read | audit read |
| masked customer/store/order/delivery search | yes | yes | yes | yes | scoped | audit only |
| basic/sensitive PII reveal | basic grant | basic grant | sensitive candidate | sensitive approval | separate grant | review only |
| cancellation/reschedule | policy | policy/approve | policy | approve | investigate | no |
| low/medium compensation | low | medium approve | request | oversight | high investigate | no |
| R2/R3 profile | no/R2 request | limited | request | R3 first approval | R3 second approval | review |
| break glass | request | approve candidate | request | approve | security operation | mandatory review |
| retention/LegalHold | no | no | no | read candidate | manage | audit |

S10이 구현한 33개 새 permission은 다음과 같다.

- Case/query: `SUPPORT_CASE_READ`, `SUPPORT_CASE_WRITE`, `SUPPORT_CASE_ASSIGN`, `SUPPORT_SUBJECT_SEARCH`
- Verification/PII: `SUPPORT_VERIFICATION_MANAGE`, `SUPPORT_PII_REVEAL_REQUEST`,
  `SUPPORT_PII_REVEAL_APPROVE`, `SUPPORT_PII_REVEAL_BASIC`, `SUPPORT_PII_REVEAL_SENSITIVE`,
  `SUPPORT_BREAK_GLASS_REQUEST`
- Action/order/resolution: `SUPPORT_ACTION_REQUEST`, `SUPPORT_ACTION_APPROVE`, `SUPPORT_ACTION_EXECUTE`,
  `SUPPORT_ORDER_READ`, `SUPPORT_ORDER_CANCEL`, `SUPPORT_PICKUP_RESCHEDULE`, `SUPPORT_RESOLUTION_REQUEST`,
  `SUPPORT_RESOLUTION_APPROVE`, `SUPPORT_RESOLUTION_EXECUTE`
- Compensation/profile: `SUPPORT_COMPENSATION_REQUEST`, `SUPPORT_COMPENSATION_APPROVE`,
  `SUPPORT_COMPENSATION_EXECUTE`, `SUPPORT_PROFILE_R1_CHANGE`, `SUPPORT_PROFILE_R2_CHANGE`,
  `SUPPORT_PROFILE_R3_REQUEST`, `SUPPORT_PROFILE_R3_APPROVE`
- Delivery/Operations/Privacy: `SUPPORT_DELIVERY_READ`, `SUPPORT_DELIVERY_INCIDENT_WRITE`,
  `SUPPORT_DELIVERY_CHANGE`, `OPERATIONS_SUPPORT_INVESTIGATION`, `OPERATIONS_LEGAL_HOLD_MANAGE`,
  `OPERATIONS_RETENTION_MANAGE`, `PRIVACY_AUDIT_READ`, `PRIVACY_BREAK_GLASS_REVIEW`

기존 값과 V42의 `PRIVACY_BREAK_GLASS_REVIEW`는 같은 Operations-owned persistent grant/revoke 경계에 있다.
어떤 값도 `PLATFORM_OPERATOR`에 자동 부여되지 않는다. S40은 `SUPPORT_VERIFICATION_MANAGE`,
`SUPPORT_PII_REVEAL_REQUEST`, `SUPPORT_PII_REVEAL_APPROVE`, `SUPPORT_PII_REVEAL_BASIC`,
`SUPPORT_PII_REVEAL_SENSITIVE`, `SUPPORT_BREAK_GLASS_REQUEST`, `PRIVACY_BREAK_GLASS_REVIEW`를 활성화한다.
S50 timeline은 `SUPPORT_CASE_READ`와 `SUPPORT_ORDER_READ`, action evaluation은 `SUPPORT_ACTION_REQUEST`를 활성화한다.
Timeline query와 action evaluation은 owner read 전후에 persistent grant, Case relation과 assignment를 재확인한다.
`PERSONAL_DATA_REVEAL` verification은 원문 열람에만, `SUPPORT_ACTION` verification은 action evaluation에만 사용할 수
있으며 서로 대체하지 않는다.

S60 create/revision은 `SUPPORT_ACTION_REQUEST`와 action별 `SUPPORT_ORDER_CANCEL`,
`SUPPORT_PICKUP_RESCHEDULE`, `SUPPORT_RESOLUTION_REQUEST` 중 하나를 함께 요구한다. manager decision은
`SUPPORT_ACTION_APPROVE`, Operations decision은 platform role과 `OPERATIONS_SUPPORT_INVESTIGATION`을 요구한다.
requester/Support reviewer/Operations reviewer는 모두 달라야 하고 reviewer는 executor가 될 수 없다. 명시적
reassignment actor는 `SUPPORT_CASE_ASSIGN`, 새 executor는 `SUPPORT_CASE_WRITE`, `SUPPORT_ACTION_EXECUTE`와 action별
grant를 모두 가져야 한다. 권한 회수는 자동 fallback 상담원을 선택하지 않고 request를 `REASSIGNMENT_REQUIRED`로
남긴다.

S70 execution은 현재 executor와 Case assignee가 같은 actor인지 확인하고 `SUPPORT_ACTION_EXECUTE`, action별
`SUPPORT_ORDER_CANCEL` 또는 `SUPPORT_PICKUP_RESCHEDULE`, `SUPPORT_CASE_READ`, `SUPPORT_ORDER_READ`를 모두
재검사한다. 원 requester의 request/capability 권한도 실행 시점까지 active여야 하며, requester·Support reviewer·
Operations reviewer 중 누구도 executor가 될 수 없다. 실행 전에 exact request revision, policy, action-bound
verification, canonical payload digest와 Ordering version을 다시 비교하고 owner Context가 잠금 상태에서 최종
불변식을 검증한다.

ACCEPTED direct change의 store confirmation/delegation은 Support permission이 아니다. Store OWNER/STAFF active
membership을 별도로 확인하고 store authorizer는 requester·reviewer·executor와 달라야 한다. authorization은
STORE 비용 책임의 명시 수락만 허용하며 UNKNOWN/PLATFORM 책임을 자동 귀속하지 않는다. 그 밖의 future
permission은 owning use case 전까지 dormant다.

S80 plan/execute/reconcile은 current executor와 Case assignee 일치, `SUPPORT_ACTION_EXECUTE`,
`SUPPORT_RESOLUTION_EXECUTE`를 재검사하고 원 requester의 `SUPPORT_ACTION_REQUEST`,
`SUPPORT_RESOLUTION_REQUEST`도 plan 생성 시 active여야 한다. 실행자는 requester·Support reviewer·Operations
reviewer와 달라야 한다. 별도 `SUPPORT_RESOLUTION_APPROVE` 경로는 사용하지 않으며 S60 exact revision만 승인
source다. 조회는 `SUPPORT_CASE_READ`와 request visibility를 요구한다. 권한/assignment 회수, 책임 미확정 또는
owner 장애는 local executor, cost owner, fake/no-op으로 fallback하지 않는다.
