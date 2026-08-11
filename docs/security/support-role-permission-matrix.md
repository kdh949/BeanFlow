# Support Role and Permission Matrix

> **Status:** `PARTIALLY IMPLEMENTED`; S20 Case는 `SUPPORT_CASE_READ`, `SUPPORT_CASE_WRITE`,
> `SUPPORT_CASE_ASSIGN`, S30 exact search는 `SUPPORT_SUBJECT_SEARCH` persistent grant를 사용한다. Role bundle과
> 나머지 Support capability는 DRAFT다.

Roles are coarse bundles; S20 Case command와 S30 exact search는 active Operations-owned persistent grant를 요구한다.
S30은 추가로 structured reason과 persistent actor rate budget을 요구하고 Vault 호출 뒤 grant를 재확인한다. Later
action은 verification과 latest ActionPolicy까지 추가로 요구한다. JWT role alone is never a fallback.

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
  `OPERATIONS_RETENTION_MANAGE`, `PRIVACY_AUDIT_READ`

기존 9개를 포함한 전체 42개 값은 같은 Operations-owned persistent grant/revoke 경계에 있다. 새 값은
`PLATFORM_OPERATOR`에 자동 부여되지 않는다. S20 Case 3개와 S30 `SUPPORT_SUBJECT_SEARCH`만 owning use case에서
활성이고 나머지는 owning use case가 구현되기 전까지 dormant하다.
