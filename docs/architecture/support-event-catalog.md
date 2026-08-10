# Support and Delivery Event Catalog

> **Status:** `DRAFT`; no event name or payload below is a canonical implemented contract.

All integration events carry `eventId`, `eventType`, `aggregateId`, `aggregateVersion`, `occurredAt`, `payloadVersion`, `correlationId`, `causationId`; consumers assume duplicates and bind business idempotency to owner source/version.

| Group | Events |
|---|---|
| Case | SupportCaseOpened/Assigned/Resolved/Closed, SupportSubjectLinked |
| Privacy | SupportVerificationRequested/Succeeded/Locked, SupportDataAccessGranted/Revealed, SupportBreakGlassUsed |
| Action | SupportActionRequested/ApprovalRequired/Approved/Rejected/Executed/Unknown |
| Order | SupportOrderCancellationRequested, SupportPickupRescheduleRequested, PostAcceptanceResolutionRequested/Completed |
| Compensation | Requested/Approved/Issued/Denied |
| Profile | SupportProfileChangeRequested/Approved/Changed |
| Operations | OperationsInvestigationRequested/Returned/Escalated |
| Delivery | DispatchRequested/ProviderAccepted, CourierAssigned/ArrivedAtPickup, OrderPickedUpForDelivery, DeliveryCompleted/CancellationRequested/Failed/Returned/SyncLost/ReconciliationRequested/IncidentReported |
| Retention | RetentionDeletionRequested/Completed/Failed, LegalHoldPlaced/Released |

Payloads exclude free-form secrets, raw PII, Provider raw messages and unused customer/store identifiers. Owner events remain immutable; replay with changed payload is conflict, not overwrite.
