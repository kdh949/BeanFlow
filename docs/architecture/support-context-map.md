# Support Context Map

> **Status:** `PARTIALLY IMPLEMENTED`; S20 owns the Case records shown below. Owner DTO/command integration and the
> remaining aggregate names stay DRAFT under ADR-081/088.

```text
Identity/Merchant/Delivery ── masked subject/profile DTOs ──┐
Ordering/Payment/Loyalty/Promotion/Settlement               ├─> Support Query Model
Fulfillment/Notification/Operations/Eventing ── facts ──────┘

Support ── typed command + actor/case/verification/policy snapshot ──> Owner Context
Owner Context ── accepted/denied/unknown durable result ─────────────> Support
Support ── investigation request ID ─────────────────────────────────> Operations
Operations ── decision ID; no payload mutation ──────────────────────> Support
```

Support currently owns `SupportCase`, assignment/state history, bounded interaction/note records and `SubjectLink`.
Verification orchestration, DataAccessGrant, ActionRequest, approval UX, CompensationRequest and ResolutionCase remain
future aggregates. Operations owns persistent operator grants, cross-functional investigation, reconciliation and
LegalHold operations. Identity owns customer authentication/contact and StoreMembership. Merchant owns store
public/legal/payout profiles. Delivery owns canonical fulfillment/provider state.

Support never calls another Context's Repository or updates its table. Cross-context writes use public Application APIs and identifiers. Integrated reads use DTO projections/query services; write aggregates do not gain navigation relationships for console convenience.
