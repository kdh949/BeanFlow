# Support Query Model

> **Status:** `PARTIALLY IMPLEMENTED`; S20 Case list is implemented. Search, timeline and owner-composed views remain
> proposed implementation models.
> **Canonical API status:** S20 has one endpoint-specific Case-list filter/sort/cursor schema; later query schemas are
> not accepted or present in OpenAPI.

## S20 Case list

`GET /support/cases` uses a JDBC summary projection ordered by `(openedAt DESC, caseId DESC)`. Its signed 15-minute
cursor binds optional state and optional assignee ID; interactions and notes are neither selected nor JPA collections.
The endpoint does not compose owner data or validate owner IDs in S20.

### List-index measurement (2026-08-11)

PostgreSQL Testcontainers에서 실제 projection SQL과 `LIMIT 50`을 사용해 20,000 synthetic Case(상태 50:50,
rare assignee 10%)의 `EXPLAIN (ANALYZE, BUFFERS)`를 비교했다. 기존 `(state, current_assignee_id, opened_at DESC,
id DESC)`만 있을 때 unfiltered/state-only/assignee-only은 각각 sequential scan + top-N sort였고 실행 시간은
8.417ms/7.185ms/4.208ms, buffer는 모두 488이었다. state+rare-assignee는 기존 composite index scan(1.515ms,
15 buffers)이었다.

`(opened_at DESC, id DESC)`와 `(current_assignee_id, opened_at DESC, id DESC)`를 추가한 뒤에는 unfiltered가
opened index scan(1.325ms, 4 buffers), state-only가 같은 opened index filter scan(1.469ms, 5 buffers),
assignee-only가 assignee index scan(2.060ms, 14 buffers), state+assignee가 assignee index filter scan(1.171ms,
14 buffers)을 선택했다. 따라서 state-only index는 추가하지 않고, 두 filter가 더 선택적인 분포를 위한 기존
state+assignee composite index는 유지한다. 이 fixture 결과는 production SLO나 일반 성능 수치가 아니며, data
distribution이나 query projection이 달라지면 동일 조건으로 재측정한다.

## Future support query model

`SupportSearchQueryService`, `SupportSubjectSummary`, `SupportOrderTimeline`, `SupportDeliveryView` and `SupportActionAvailabilityView` compose owner DTOs without adding JPA relationships to write models.

Exact phone/email search accepts raw values only in a POST body and returns masked results. Raw criteria never appears
in URI, access log, metric, cursor, Audit or exception. Storage, normalization, blind-index/key ownership and rotation
remain blocked by Proposed ADR-083 and the owner model selected in S30. The product scope is exact bounded search;
Elasticsearch requires measured need and a new Accepted decision.

Each timeline Stage must define its endpoint-specific item type, stable ordering tuple, canonical filters and page bounds
from the implemented owner DTOs before adopting ADR-070. The S20 Case-list tuple is not a shared Support tuple. Items
expose only the source, public state, masked summary and correlation/causation reference that their typed contract
allows. Dependency failure is non-success, never an empty 200. A materialized projection is allowed only when lag,
freshness and rebuild failure semantics are explicit.
