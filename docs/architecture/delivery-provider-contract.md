# Delivery Provider Contract

> **Status:** `DRAFT`; Provider selection, authentication contract, DTOs, ports and Inbox states are not implemented.

Ports are separated by purpose: quote, dispatch, status lookup, cancellation, instruction update and webhook authentication. Provider DTOs/enums/errors are translated by an anti-corruption adapter and never exposed in BeanFlow public/domain contracts.

Webhook flow: authenticate raw body -> deduplicate `(providerType, providerEventId)` -> durable Inbox commit -> 2xx -> worker mapping/state validation -> owner update/event. Inbox states are RECEIVED, DUPLICATE, PROCESSING, APPLIED, IGNORED_STALE, RECONCILIATION_REQUIRED, FAILED, MANUAL_REVIEW.

Dispatch/cancel timeout preserves unknown intent and uses the same external reference for lookup. Provider success followed by DB failure reconciles the same attempt; it does not issue a new dispatch. No automatic cross-provider failover occurs until absence/cancellation is authoritative. Local/scripted adapters are test/local only; production missing/multiple adapter configuration fails startup.
