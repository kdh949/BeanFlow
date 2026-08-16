# Customer store discovery query plan evidence

## Measurement condition

- Date: 2026-08-16
- Reproduce:

  ~~~bash
  ./gradlew test \
    --tests 'io.github.kdh949.beanflow.discovery.internal.StoreSearchQueryPlanTest' \
    --tests 'io.github.kdh949.beanflow.ordering.internal.CustomerRecentStoreQueryMigrationTest'
  ~~~

- Database: PostgreSQL 17.5 Testcontainers. The tests start from a clean database, apply V1 through
  V64, and first assert the public migration index definition. Each before/after capture then uses
  an isolated schema with the same index definition and runs ANALYZE immediately before the capture.
- No planner toggle or scan preference is set: the tests do not change enable_seqscan,
  random_page_cost, or an equivalent planner setting. The term test sets the transaction-local
  pg_trgm.similarity_threshold to 0.3, matching the production repository contract; it is not a
  planner override.
- These are single EXPLAIN (ANALYZE, BUFFERS) captures, not repeated latency samples. A fresh index
  can show read buffers, so execution time and buffer values are access-plan evidence, not an SLA or
  an end-to-end latency comparison.

## Fixed fixtures and production predicates

| Query | Fixed data | Predicate and order captured | Migration definition verified |
|---|---|---|---|
| Search term | 100,000 rows: 200 harborview-coffee-roastery-&lt;n&gt; terms and 99,800 unrelated terms; typo token harborvew-coffee-roastery; threshold 0.3; limit 20 | The production core term predicate: LIKE pattern ESCAPE '\' OR (% value AND similarity(...) &gt;= 0.3), then similarity DESC, store_id ASC, LIMIT 20 | [V59](../../src/main/resources/db/migration/V59__create_store_search_term_index.sql): USING gin (term_normalized gin_trgm_ops) |
| Favorite stores | 20,000 rows: one customer owns 500 rows and 19,500 rows belong to other customers; limit 20 | WHERE customer_id = ? ORDER BY created_at DESC, store_id ASC LIMIT 20 | [V57](../../src/main/resources/db/migration/V57__create_store_search_vocabulary_and_favorite.sql): (customer_id, created_at DESC, store_id) |
| Recent stores | 20,000 orders: target customer has 500 BR-40-eligible orders over 50 stores and 19,500 unrelated/expired orders; limit 20 | customer_id plus the five eligible states, GROUP BY store_id, max(created_at) DESC, store_id ASC, LIMIT 20 | [V63](../../src/main/resources/db/migration/V63__add_customer_recent_store_query_index.sql): (customer_id, state, created_at DESC, store_id) |

The search-term capture deliberately isolates the term access predicate used by
StoreSearchCandidateRepository; it does not claim to measure the later token CTE, profile join,
pickup-availability batch, or HTTP response mapping. The favorite and recent captures use their
production query shape directly.

## Actual plan captures

| Query | Without the index | With the exact migration index | What the capture establishes |
|---|---|---|---|
| V59 search term GIN | Seq Scan; 99,800 rows removed; shared hit=1143; execution 256.375 ms | BitmapOr → two Bitmap Index Scan on ix_search_term_trgm → Bitmap Heap Scan; 200 rows; shared hit=113; execution 5.731 ms | Both substring and % branches are indexable. In this typo fixture the substring branch returned 0 and the % branch returned 200 candidates; the planner selected the GIN path without a forced setting. |
| V57 favorite ordering | Seq Scan plus top-N sort; 19,500 rows removed; shared hit=173; execution 2.703 ms | Index Only Scan using ix_discovery_favorite_customer_created; 20 returned rows, Heap Fetches: 20; shared hit=1 read=3; execution 1.127 ms | Customer filter and newest-first/tie-break order reach the composite index. The fresh table still fetched heap tuples, so this is not a claim of a fully all-visible index-only workload. |
| V63 recent grouping | Seq Scan; 19,500 rows removed; shared hit=234; execution 4.284 ms | Bitmap Index Scan on ix_ordering_order_customer_recent_store → Bitmap Heap Scan; 500 eligible rows; shared hit=12 read=8; execution 5.025 ms | The customer/state predicate is narrowed by the V63 index before grouping. The group and top-N sorts remain because the aggregate order is not the raw index order. |

The raw plan text is emitted by
[StoreSearchQueryPlanTest](../../src/test/kotlin/io/github/kdh949/beanflow/discovery/internal/StoreSearchQueryPlanTest.kt)
and
[CustomerRecentStoreQueryMigrationTest](../../src/test/kotlin/io/github/kdh949/beanflow/ordering/internal/CustomerRecentStoreQueryMigrationTest.kt).
Both tests assert a sequential plan before their test-schema index exists and the named migration
index after it exists.

## What this does and does not show

- **Shown:** V59, V57, and V63 are usable by their relevant PostgreSQL predicates under a
  deterministic fixture; the selected after-plan names the intended migration index.
- **Not an improvement claim:** the V63 single after-plan includes reads of newly created index
  pages, and EXPLAIN ANALYZE instruments every node. Its slower one-off capture is retained
  rather than hidden. The captures do not establish p50/p95/p99, throughput, cache-warm
  behaviour, or a universal latency improvement.
- **Not measured:** full multi-token candidate CTE/profile/pickup query latency, real Korean
  corpus selectivity, concurrent traffic, index size and write amplification, connection-pool or
  GC behaviour, cursor traversal, and larger production-scale distributions.

## Revisit when

Re-run these fixtures after changing a captured predicate, its sort/group tuple, an index
definition, pg_trgm threshold semantics, PostgreSQL version, or the public page limit. Before
making an SLO, capacity, or external-search-engine decision, add repeated warm/cold measurements
and concurrent realistic-corpus load rather than extrapolating from these plan captures.
