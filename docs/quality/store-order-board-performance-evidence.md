# Store order board query and write-cost evidence

## Measurement condition

- Date: 2026-08-14.
- Reproduce with `./gradlew test --tests '*StoreOrderBoardMigrationTest*'`.
- Database: PostgreSQL 17 / PostGIS 3.5 (`postgis/postgis:17-3.5`) through Testcontainers. The image is
  `linux/amd64` on an Apple Silicon Docker host, so Docker ran it under emulation.
- Flyway: V1 through V56 is applied first, and the production index definitions are asserted from `pg_indexes`.
- Read fixture: 20,000 rows in an isolated schema. Two hundred rows belong to the target store and are evenly
  divided across `PAID`, `ACCEPTED`, `PREPARING`, and `READY`; the remaining 19,800 are terminal rows for another
  store. Each measured lane requests `LIMIT 50`.
- Write fixture: two separate 1,000-row tables with the same schema and batch. One has no board indexes; the other
  has both V56-equivalent indexes. The measurement records one batch insert and one `PAID -> ACCEPTED` update.
- Method: `ANALYZE`, then `EXPLAIN (ANALYZE, BUFFERS)` with the same SQL and parameters before and after the indexes.
  Sequential scans and planner settings are not forced.

These are repeatable query-plan and write-cost observations from one emulated local run. They are not a native
runtime SLA, throughput result, p50/p95/p99, or production capacity claim.

## Captured lane plan shapes

| Query | Without index | With index | Execution time before | Execution time after |
|---|---|---|---:|---:|
| `READY`, pickup start order | `Seq Scan` over 20,000 rows + quicksort | `Index Only Scan using ix_board_fixture`, 50 heap fetches | 3.127 ms | 1.971 ms |
| `PAID`, acceptance deadline order | `Seq Scan` over 20,000 rows + quicksort | `Index Only Scan using ix_acceptance_fixture`, 50 heap fetches | 3.139 ms | 2.034 ms |

The installed production definitions are:

```sql
CREATE INDEX ix_ordering_order_store_board
    ON ordering_order (store_id, state, pickup_window_start_snapshot, id);
CREATE INDEX ix_ordering_order_store_acceptance_board
    ON ordering_order (store_id, state, acceptance_deadline_at, id)
    WHERE state = 'PAID';
```

The indexed READY capture used both `store_id` and `state` as `Index Cond`. The PAID partial capture used
`store_id`; the partial predicate fixed `state = 'PAID'`. Both avoided the 19,950-row filter and explicit sort seen
in the pre-index fixture. The full multi-lane endpoint still groups by business date and may sort the small active
result; these lane plans prove the two ordering access paths, not that every combined board shape is sort-free.

## Captured write sample

| Operation, 1,000 rows | Without indexes | With both indexes | Observed delta |
|---|---:|---:|---:|
| Batch insert | 45,569,125 ns | 59,687,542 ns | +31.0% |
| `PAID -> ACCEPTED` update | 48,367,750 ns | 63,512,375 ns | +31.3% |

The sample shows the expected write amplification from maintaining a state-bearing general index and a PAID
partial index. A single JVM/database run cannot establish the magnitude for production. No performance improvement
claim is made; the indexes are accepted because the measured fixture demonstrates the intended read paths while the
write cost remains explicit.

## Fixed-query-count and correctness evidence

`StoreOrderBoardIntegrationTest` measures the repository counter around one and fifty active orders. Both use two
list Projection statements: one store-scoped Order header query and one batched line query. The same suite fixes
cross-store exclusion, future PAID visibility, deadline ordering, business-date grouping, terminal exclusion,
conditional 304, time-boundary ETag changes, failure-closed hashing, and 403 membership revocation.

Re-run this evidence when either V56 index, the executable state set, ordering tuple, polling interval, PostgreSQL
major version, or expected active-board cardinality changes. Obtain native and concurrent mixed read/write evidence
before changing the polling transport or making latency and capacity commitments.
