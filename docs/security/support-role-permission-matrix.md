# Support Role and Permission Matrix

> **Status:** `IMPLEMENTED PERMISSION FOUNDATION / DRAFT ROLE BUNDLES`; S10은 exact permission enum과 persistent
> grant DB vocabulary를 구현했지만 이 표의 role bundle이나 Support capability release를 승인하지 않는다.

Roles are coarse bundles; an active Operations-owned persistent grant, Case/object relationship, verification and latest ActionPolicy are all required. JWT role alone is never a fallback.

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
`PLATFORM_OPERATOR`에 자동 부여되지 않고, owning use case가 구현되기 전에는 dormant하다.
