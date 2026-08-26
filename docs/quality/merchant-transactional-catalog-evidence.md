# Merchant transactional catalogue evidence

## Measurement condition

- Date: 2026-08-27
- Reproduce:

  ~~~bash
  ./gradlew test --tests 'io.github.kdh949.beanflow.merchant.internal.MerchantCatalogLockPerformanceEvidenceTest'
  ./gradlew test --tests 'io.github.kdh949.beanflow.discovery.internal.StoreSearchQueryPlanTest'
  ./gradlew test --tests 'io.github.kdh949.beanflow.ordering.internal.CreateOrderConcurrencyTest'
  ~~~

- Database: PostgreSQL 17 / PostGIS 3.5 (`postgis/postgis:17-3.5`) through Testcontainers. The
  Apple Silicon workstation runs the amd64 image under Docker emulation. These captures are local
  plan and lock-semantics evidence, not native-runtime capacity or an SLA.
- Flyway applies V1 through V70 to a clean isolated database. The lock fixture uses one Store and
  20 warm-up plus 200 measured transactions per read shape. The baseline and `FOR SHARE` samples
  run sequentially on the same connection and row.
- The exclusive-wait fixture holds `FOR SHARE`, starts `FOR UPDATE` on another backend, confirms
  `pg_stat_activity.wait_event_type = 'Lock'` and a non-empty `pg_blocking_pids`, keeps the holder
  for a controlled 250 ms, and then commits it. The compatible case attempts a second `FOR SHARE`
  while the first is still held.
- The search fixture uses 100,000 terms, 200 typo matches, threshold 0.3 and limit 20. It runs
  `ANALYZE` and the production core LIKE/trigram predicate without planner toggles before and after
  recreating the exact V59 GIN index.

## Actual local captures

| Evidence | Capture | Meaning |
|---|---:|---|
| Plain Store read, 200 transactions | 71 ms; 2,785 operations/s | Same-row local baseline for measurement context only. |
| Store `FOR SHARE`, 200 transactions | 70 ms; 2,834 operations/s | A single ordered sample; the lower time is noise/cache order, not an improvement claim. |
| Second `FOR SHARE` while first held | 15 ms acquisition | Shared Store locks are compatible in the fixed fixture. |
| `FOR UPDATE` behind held `FOR SHARE` | 284 ms total with controlled 250 ms hold | PostgreSQL reported `Lock` wait and a blocking PID before the holder committed. |
| Search term without V59 | Seq Scan; 99,800 rows removed; shared hit 1,143; 269.263 ms | The unindexed predicate scans the fixed term table. |
| Search term with V59 | BitmapOr and two `ix_search_term_trgm` Bitmap Index Scans; shared hit 113; 5.996 ms | Both LIKE and trigram branches remain indexable on the V70 combined schema. |

The raw values are emitted by `MerchantCatalogLockPerformanceEvidenceTest` and
`StoreSearchQueryPlanTest`. The search execution time is a single instrumented
`EXPLAIN (ANALYZE, BUFFERS)` capture. It shows selected plan shape and buffers, not end-to-end
customer search latency.

## Transaction and failure regression

`CreateOrderConcurrencyTest` uses the production quote/order and Merchant writer paths rather than
direct lock SQL. It verifies both interleavings for Store ordering policy and Menu trade changes:

- writer-first makes the final Order wait for commit and return `409 ORDER_QUOTE_STALE`;
- Order-first holds the Store shared lock, makes the exclusive writer wait at least 300 ms, then
  commits the immutable Order before the writer proceeds;
- two same-Store Order snapshots both acquire compatible shared locks before either is released;
- A→B→A Menu state still invalidates the earlier quote through `trade_version`.

`MenuCatalogEndpointIntegrationTest` separately proves Menu owner rows, search terms, Audit and the
command ledger commit or rollback together. A forced search write failure returns 503 and leaves
none of those writes behind.

## What this does and does not establish

- **Shown:** the final Order and Merchant writers use compatible/conflicting PostgreSQL lock modes
  as designed; the conflict is observable as a database lock wait; the V59 term index still serves
  the production predicate after V70; both writer/order interleavings preserve a coherent result.
- **Not an improvement claim:** the 200-operation samples are ordered, single-process local
  measurements with no confidence interval. The apparent `FOR SHARE` throughput advantage is not
  statistically meaningful.
- **Not measured:** production p50/p95/p99, connection-pool saturation, realistic concurrent
  arrival rates, native Apple Silicon PostgreSQL, cache-warm/cold distributions, WAL and index
  write amplification, deployment migration duration, Provider sandbox or production traffic.

Re-run this evidence after changing the Store/Menu lock order or mode, quote fingerprint material,
search predicate/index, transaction manager, PostgreSQL major version or catalogue bounds. Add a
repeated concurrent workload before making an SLO, capacity or throughput claim.
