# Support Query Model

> **Status:** `PROPOSED IMPLEMENTATION MODEL`
> **Canonical API status:** endpoint-specific filter/sort/cursor schemas are not accepted or present in OpenAPI.

`SupportSearchQueryService`, `SupportSubjectSummary`, `SupportOrderTimeline`, `SupportDeliveryView` and `SupportActionAvailabilityView` compose owner DTOs without adding JPA relationships to write models.

Exact phone/email search accepts raw values only in a POST body and returns masked results. Raw criteria never appears
in URI, access log, metric, cursor, Audit or exception. Storage, normalization, blind-index/key ownership and rotation
remain blocked by Proposed ADR-083 and the owner model selected in S30. The product scope is exact bounded search;
Elasticsearch requires measured need and a new Accepted decision.

Each timeline Stage must define its endpoint-specific item type, stable ordering tuple, canonical filters and page bounds
from the implemented owner DTOs before adopting ADR-070. No shared Support tuple is accepted in S00. Items expose only
the source, public state, masked summary and correlation/causation reference that their typed contract allows. Dependency
failure is non-success, never an empty 200. A materialized projection is allowed only when lag, freshness and rebuild
failure semantics are explicit.
