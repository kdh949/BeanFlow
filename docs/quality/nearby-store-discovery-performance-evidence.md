# Nearby store query plan evidence

## Measurement condition

- Date: 2026-08-06
- Database: PostgreSQL 17.5 with PostGIS 3.5.2 (`postgis/postgis:17-3.5` Testcontainers)
- Host: Apple Silicon workstation. The image publishes a `linux/amd64` manifest only, so the
  container ran under Docker emulation. Absolute times below are therefore not representative of a
  native amd64 runtime and are not an SLA, a throughput figure or a latency target.
- Fixture: 5,000 `merchant_store` rows, each with one `merchant_store_discovery_profile`. 50 of them
  form a cluster within roughly 440 m of the query point `(127.0, 37.5)`; the remaining 4,950 sit on
  a one-degree grid far outside the radius. Every store is `accepting_orders` and `pickup_enabled`.
  `ANALYZE` ran on both tables before each capture.
- Query: the production first-page projection — raw `ST_DWithin(location, queryPoint, 1000)` range
  filter, `floor(ST_Distance(...) * 1000000)::bigint` sort expression,
  `ORDER BY distance_micrometers, store_id`, `LIMIT 101` (public maximum page 100 plus the
  next-page probe).
- Evidence: `StoreDiscoveryProfileMigrationTest` captures `EXPLAIN (ANALYZE, BUFFERS)` once after
  dropping the index and once after recreating the exact V33 index. This is a controlled
  query-plan check with one observation per condition.

## Actual result

| Condition | Plan shape | Rows removed by the range filter | Execution time | Shared buffers |
|---|---|---:|---:|---:|
| without index | `Seq Scan on merchant_store_discovery_profile` then top-N `Sort` | 4,950 | 59.799 ms | 244 hits |
| V33 index present | `Index Scan using idx_store_discovery_profile_location` with `Index Cond: location && _st_expand(...)` then top-N `Sort` | 0 | 49.639 ms | 260 hits |

Both plans returned the same 50 rows.

## What this does and does not show

- **Shown:** the V33 GiST index is actually used by the production range predicate. The index
  condition `location && _st_expand(queryPoint, 1000)` replaces a sequential scan that discarded
  4,950 rows through the spheroid `ST_DWithin` filter, and the `merchant_store` join switched from a
  repeated sequential scan to a primary-key index scan.
- **Not shown:** a performance improvement. The two execution times are single observations on an
  emulated container and are close enough that the difference is not a reliable measurement. Shared
  buffer hits were slightly higher with the index because the index pages are also read. The
  dominant cost in this fixture is the spheroid distance computation on the 50 matching rows, not
  the scan of the 4,950 non-matching rows.
- **Not measured:** warm p50/p95/p99, RPS, error rate under load, GC and allocation, Hikari pool
  behaviour, native amd64 timings, multi-page cursor traversal cost, and data sizes beyond 5,000
  profiles.

## Revisit when

Re-run the fixed fixture after changing the select list, the sort tuple, the index definition, the
radius contract, the public maximum page size, or the PostgreSQL/PostGIS version. Capture a native
amd64 baseline before making any latency or throughput claim.
