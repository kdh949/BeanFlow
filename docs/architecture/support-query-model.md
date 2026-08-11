# Support Query Model

> **Status:** `PARTIALLY IMPLEMENTED`; S20 Case list, S30 protected exact search and S50 Case/Order timeline are implemented.
> Later Delivery and additional cross-context views remain proposed implementation models.
> **Canonical API status:** S20 Case-list, S30 exact-search and S50 timeline/action-evaluation schemas are
> accepted/runtime-backed.

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

## S50 integrated timeline contract

S50 composes Case and Order timelines from owner public query DTOs. Ordering, Payment, Loyalty, Promotion, Fulfillment,
Settlement, Notification and Operations each execute at most one bounded query for the supplied Order ID set; Support
does not loop owner calls per Case link. Local Case history, interaction, note metadata and subject-link lifecycle remain
Support-owned projections. The global tuple is `(occurredAt DESC, sourceRank ASC, itemId DESC)` and the signed endpoint/
filter contract is recorded in ADR-070.

The response uses closed source/type/state vocabulary and a server-created masked summary. It never returns note content,
interaction free text, payment/provider reference, notification payload, Audit before/after summary, recipient identity or
raw profile data. A required owner query failure is a non-success response, not an empty partial timeline. V43 adds the two
missing Order-history indexes and the owning Stage records an identical-fixture EXPLAIN baseline/re-measure before claiming
an index effect.

### S50 timeline-index evidence (2026-08-12)

PostgreSQL 17.5 Testcontainers에서 각 table 20,000행, target Order 1행, `LIMIT 20`의 동일 fixture를 사용했다.
`payment_refund`는 V43 index 제거 시 19,999행을 제거하는 sequential scan(604 shared buffers)이었고 index 재생성
후 `(order_id, updated_at DESC, id DESC)` index scan(3 shared buffers)을 선택했다. `notification_delivery`도 같은
조건에서 sequential scan(19,999행 제거, 840 buffers)에서 V43 index scan(3 buffers)으로 전환됐다.
`SupportTimelineQueryPlanTest`가 두 baseline/re-measure 계획을 모두 출력하고 scan type을 검증한다. 이 결과는
해당 synthetic fixture의 plan evidence이며 production latency나 일반 성능 향상 주장이 아니다.

## Later support query model

`SupportSearchQueryService`, `SupportSubjectSummary`, `SupportOrderTimeline`, `SupportDeliveryView` and `SupportActionAvailabilityView` compose owner DTOs without adding JPA relationships to write models.

Exact phone/email search accepts raw values only in a POST body, rejects query parameters and returns masked results.
Because upstream infrastructure can see a client-created query before rejection, deployment access logs must record
path only or redact query strings. BeanFlow does not place raw criteria in its application metric, cursor, Audit or
exception. ADR-083 fixes Vault Transit AEAD ciphertext and a separate versioned HMAC-SHA-256 blind index. S30 selects
the minimal owner profile tables and masked DTOs; Support stores neither raw criteria nor long-lived owner profile
copies. The product scope is exact bounded search; Elasticsearch requires measured need and a new Accepted decision.

Each later timeline Stage must define its endpoint-specific item type, stable ordering tuple, canonical filters and page bounds
from the implemented owner DTOs before adopting ADR-070. The S20 Case-list tuple is not a shared Support tuple. Items
expose only the source, public state, masked summary and correlation/causation reference that their typed contract
allows. Dependency failure is non-success, never an empty 200. A materialized projection is allowed only when lag,
freshness and rebuild failure semantics are explicit.
