# PointAccount ledger query performance evidence

## Measurement condition

- Date: 2026-08-06
- Database: PostgreSQL 17.6 Testcontainers
- Fixture: one PointAccount, one PointLot and 5,000 `ACCRUAL/CREDIT` ledger rows; every row has a distinct source
  reference and consecutive `occurred_at` values.
- Query: the production first-page projection filtered by `point_account_id`, ordered by
  `(occurred_at DESC, id DESC)`, `LIMIT 101` (`maximum public page 100 + one next-page probe`).
- Evidence: `PointAccountQueryMigrationTest` runs `ANALYZE` and records `EXPLAIN (ANALYZE, BUFFERS)` once after
  dropping the index and once after recreating the exact V32 index. This is a controlled query-plan check, not an
  SLA or a production latency claim.

## Actual result

| Condition | Plan shape | Execution time | Shared buffers |
|---|---|---:|---:|
| without index | `Seq Scan` of 5,000 rows then top-N `Sort` | 1.092 ms | 102 hits |
| V32 index present | `Index Scan using idx_point_transaction_account_occurred_id` and `Limit` | 0.036 ms | 3 hits, 2 reads |

The before plan filtered all 5,000 fixture rows and sorted `(occurred_at DESC, id DESC)`. The V32 plan used
`idx_point_transaction_account_occurred_id` with the account predicate and stopped after 101 rows. The test does
not claim an index-only scan: the response projection still reads columns that are not index keys.

The next-page predicate is verified separately by PostgreSQL integration tests with typed timestamp and UUID
parameters; this measurement covers the first-page shape only. Re-run the fixed fixture after changing the select
list, sort tuple, index definition, PostgreSQL major version or public maximum page size.
