# Support Role and Permission Matrix

> **Status:** `DRAFT IMPLEMENTATION MATRIX`; Accepted actor separation and persistent-grant principles do not approve
> these exact role bundles or permission enum values.

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

Permission vocabulary includes case/search/read, basic/sensitive reveal, break-glass request, order cancel/reschedule/resolution request, point/coupon compensation request/approve/execute, purpose-specific profile request/approve, Operations investigation/approval/legal-hold/retention, and privacy/approval audit read. S10 owns the exact enum and grant migration; it must not copy all privileges into `PLATFORM_OPERATOR`.
