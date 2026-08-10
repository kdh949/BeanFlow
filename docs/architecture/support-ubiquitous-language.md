# Support Ubiquitous Language

> **Status:** Planning vocabulary. Accepted meanings are anchored by ADR-081/082/084-089; exact type names remain DRAFT.

| Term | Definition | Owner |
|---|---|---|
| SupportCase | one inquiry/incident and its service lifecycle | Support |
| Requester | person/system asking for assistance | Support reference |
| Subject | customer/store/member/rider/order/delivery whose data or state is involved | Owner Context |
| VerificationSession | Case+Subject+Purpose-bound registered-channel control result | Support |
| DataAccessGrant | operator+case+subject+field+reason+expiry-bound raw-data permission | Support |
| ActionDecision | ALLOWED, APPROVAL_REQUIRED or DENIED server result | Support |
| ActionRequest revision | immutable canonical payload and owner version proposed for execution | Support |
| ApprovalStep | one role/organization decision over one exact revision | Support/Operations |
| OperationsInvestigationCase | exceptional compensation/fraud/privacy/settlement investigation | Operations |
| PostAcceptanceResolutionCase | resolution without rolling Order lifecycle backward | Support |
| Goodwill Compensation | inconvenience benefit distinct from refund/restoration/correction | Support request; owner ledger |
| Break Glass | emergency access path, not a verification level | Support/Security |
| RetentionClass | immutable purpose/sensitivity/legal-basis retention rule | Operations/owner |
| LegalHold | scoped, reviewed, expiring deletion suspension | Operations |
| DeliveryFulfillment | provider-independent canonical delivery lifecycle | Delivery |
