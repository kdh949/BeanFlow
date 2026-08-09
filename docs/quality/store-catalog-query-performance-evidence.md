# Store catalogue query plan evidence

## Measurement condition

- Date: 2026-08-09
- Reproduce with `./gradlew test --tests '*StoreCatalogQueryMigrationTest*'`.
- Database: PostgreSQL 17.5 / PostGIS 3.5 (`postgis/postgis:17-3.5`) through Testcontainers.
  The image is `linux/amd64` on an Apple Silicon Docker host, so it runs under emulation. These
  timings are plan evidence only, not a native-runtime SLA or capacity result.
- Fixture: 51 stores, 102,051 pickup slots (past and future), 6,000 menus, and 30,000 options.
  The target store has 1,000 menus and 5 options per menu; the query uses a fixed 2030-01-01 UTC
  clock, then `VACUUM (ANALYZE)` before each capture.
- Method: Flyway applies V1–V35. The test records `EXPLAIN (ANALYZE, BUFFERS)` after dropping the
  four V35 indexes, recreates the exact V35 definitions, analyzes again, and records the same
  query. It does not disable sequential scans, force planner settings, or use a cached result.

## Captured plan shapes

| Read | Without V35 | With V35 | Captured execution time |
|---|---|---|---:|
| Future pickup slots, `LIMIT 1001` | `Seq Scan` of 102,051 rows → `Sort`; 101,051 rows removed by filter | `Index Only Scan` on `idx_pickup_slot_store_starts_id`; 0 heap fetches | 7.110 ms → 2.011 ms |
| Menus, `LIMIT 1001` | `Seq Scan` of 6,000 rows → `Sort`; 5,000 rows removed by filter | `Index Only Scan` on `idx_merchant_menu_store_name_id`; 0 heap fetches | 2.659 ms → 1.891 ms |
| Options, `LIMIT 5001` | target-menu walk followed by repeated option-table scan/sort | `Nested Loop`: `idx_merchant_menu_store_id` then `idx_merchant_menu_option_menu_name_id`, both index-only; no `Seq Scan` or `Hash Join` | 1,149.615 ms → 8.294 ms |

The post-V35 option plan contains an **Incremental Sort**, not a global option sort: its presorted
key is `merchant_menu.id`; 143 completed groups used quicksort with 29 kB average/peak memory in
this capture. The outer and inner lateral ranges remain bounded by the public menu/option limits.
This is deliberately recorded rather than described as a sort-free plan.

## Index storage observed in the fixture

| Index | Size |
|---|---:|
| `idx_pickup_slot_store_starts_id` | 9,691,136 bytes |
| `idx_merchant_menu_store_name_id` | 532,480 bytes |
| `idx_merchant_menu_store_id` | 319,488 bytes |
| `idx_merchant_menu_option_menu_name_id` | 2,605,056 bytes |

The `INCLUDE` columns enable the captured index-only paths after vacuuming. They also increase index
storage and add insert/update/vacuum work. No production write rate, native timing, concurrent
load, cache-hit distribution, or exact production catalogue distribution was measured here.

## Regression contract

`StoreCatalogQueryMigrationTest` fails if the V35 definitions disappear, if slot/menu reads no
longer use their named indexes, or if the option query returns to a `Seq Scan`/`Hash Join` rather
than an indexed nested-loop plan. `DiscoveryStoreCatalogIntegrationTest` separately proves the
1,000 slot/menu and 5,000 option overflow contract: one extra row returns 503 rather than a
partial 200 response.

Re-run this fixture when the select lists, ordering tuple, published bounds, V35 definitions, or
PostgreSQL major version change. Capture native and concurrent-load evidence before making an SLA,
throughput, or cost claim.
