# Customer order list query plan evidence

## Measurement condition

- Date: 2026-08-14.
- Reproduce with `./gradlew test --tests '*CustomerOrderQueryMigrationTest*'`.
- Database: PostgreSQL 17.5 / PostGIS 3.5 (`postgis/postgis:17-3.5`) through Testcontainers.
  The image is `linux/amd64` on an Apple Silicon Docker host, so Docker ran it under emulation.
  These timings are query-plan evidence, not a native-runtime SLA, throughput, or capacity result.
- Fixture: one customer and 10,000 terminal orders in an isolated schema. `created_at` differs by
  one second per row, the query covers all fixture rows, sorts by `(created_at DESC, id DESC)`, and
  requests `LIMIT 101` to represent the public maximum page plus its scan-boundary row.
- Method: Flyway first applies V1 through V55 to prove the production migration installs the named
  index. The measurement schema is then analyzed without the index, measured with
  `EXPLAIN (ANALYZE, BUFFERS)`, given the exact V55 index, analyzed again, and measured with the
  same SQL and parameters. Sequential scans and planner settings are not forced.

## Captured plan shapes

| Condition | Plan shape | Buffers | Execution time |
|---|---|---|---:|
| Without V55 | `Seq Scan` over 10,000 matching rows, then top-N `Sort` | shared hit 100 | 3.924 ms |
| With V55 | `Index Scan using ix_ordering_order_customer_recent`, 101 rows read | shared hit 1, read 2 | 1.235 ms |

The installed index is:

```sql
CREATE INDEX ix_ordering_order_customer_recent
    ON ordering_order (customer_id, created_at DESC, id DESC);
```

The indexed capture used `customer_id` and the half-open `created_at` range as `Index Cond`; its
order matches the public keyset tuple, so no explicit sort appeared. The pre-index capture sorted
all matching rows with a 32 kB top-N heapsort. Planning time was 10.543 ms before and 10.961 ms
after in this emulated, short-lived fixture; it is recorded rather than interpreted as an
application latency result.

## Fixed-query-count evidence

`CustomerOrderQueryIntegrationTest` measures the endpoint counter around one order and 101 orders.
Both use exactly three list SQL statements: candidate scan, fixed-candidate header projection, and
one batched line projection. Expiry materialization is intentionally additional command work only
when returned candidates are due; it is bounded by the requested page and never used to fill the
next window.

## Regression contract and limits

`CustomerOrderQueryMigrationTest` fails if V55 is absent, if its key order changes, if the
pre-index fixture no longer exposes the comparison baseline, or if the indexed query stops using
the named index. Customer-order integration tests separately fix signed keyset continuity,
customer/filter/date binding, 20-candidate expiry followed by an empty page with a cursor, and
three-query projection behavior.

No production order distribution, write amplification, concurrent load, cache-hit distribution,
native ARM timing, p50/p95/p99, or long historical range was measured. Re-run this evidence when
the select/order tuple, date predicate, page maximum, V55 definition, or PostgreSQL major version
changes. Obtain production-like native and concurrent-load evidence before making an SLA or cost
claim.
