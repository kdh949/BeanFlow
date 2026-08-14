# Store order board query and write-cost evidence

## Measurement condition

- Date: 2026-08-14.
- Reproduce the plan capture with:

  ```bash
  ./gradlew --no-daemon test --rerun-tasks \
    --tests 'io.github.kdh949.beanflow.ordering.internal.StoreOrderBoardMigrationTest' --stacktrace
  ```

- Database: PostgreSQL 17.5 / PostGIS 3.5 (`postgis/postgis:17-3.5`) through Testcontainers. The image is
  `linux/amd64` on an Apple Silicon Docker host, so Docker ran it under emulation.
- Flyway: V1 through V56 is applied first, and the production index definitions are asserted from `pg_indexes`.
- Read fixture: 20,000 rows in an isolated schema. The target Store has 200 rows in each of `PAID`,
  `ACCEPTED`, `PREPARING`, and `READY` (800 active rows); the other 19,200 rows are terminal orders for another
  Store.
- Production SQL source: `StoreOrderBoardQuerySql` is shared by the repository and this fixture. The primary
  snapshot is one `UNION ALL` of one `store_id + state + ORDER BY + LIMIT 51` range per requested lane. The
  fixture exercises all four lanes; it also exercises the production grouped overflow count and PAID keyset
  overflow page SQL. Production runs the count only for lanes where the 51st candidate exists.
- Write fixture: two separate 1,000-row tables with the same schema and batch. One has no board indexes; the
  other has both V56-equivalent indexes. The measurement records one batch insert and one `PAID -> ACCEPTED`
  update.
- Method: `ANALYZE`, then `EXPLAIN (ANALYZE, BUFFERS)` with the identical builder SQL and parameters before and
  after the indexes. Sequential scans and planner settings are not forced.

These are query-plan and write-cost observations from same-day emulated local runs. They are not a native runtime
SLA, throughput result, p50/p95/p99, or production capacity claim.

## Captured production-query plan shapes

| Query | Without index | With index | Execution time before | Execution time after |
|---|---|---|---:|---:|
| Four-lane primary snapshot (`4 × LIMIT 51`) | four `Seq Scan` + top-N sort, 20,000 rows examined per lane | `ix_acceptance_fixture` Index Scan for PAID and `ix_board_fixture` Index Scan for the other three lanes | 17.451 ms | 9.148 ms |
| Overflow count across the four overflowing lanes | `Seq Scan` + GroupAggregate | `ix_board_fixture` Index Only Scan, with 800 heap fetches | 15.006 ms | 14.751 ms |
| PAID keyset overflow page (`LIMIT 51`) | `Seq Scan` + top-N sort | `ix_acceptance_fixture` Index Scan | 6.637 ms | 2.391 ms |

The primary and overflow-page captures avoid the terminal rows and use the intended ordered access paths. The
overflow-count capture is explicitly **not** a speedup claim: the final run was nearly neutral, while the preceding
same-condition emulated run measured 7.758 ms without the index and 18.139 ms with it. The count is paid only for a
lane that actually overflowed; it must be remeasured on a native, representative distribution before any latency or
capacity commitment.

The installed production definitions are:

```sql
CREATE INDEX ix_ordering_order_store_board
    ON ordering_order (store_id, state, pickup_window_start_snapshot, id);
CREATE INDEX ix_ordering_order_store_acceptance_board
    ON ordering_order (store_id, state, acceptance_deadline_at, id)
    WHERE state = 'PAID';
```

The selected projection is wider than either index, so the primary/page evidence correctly records `Index Scan`,
not an unsupported Index Only Scan claim. The `UNION ALL` query returns at most 51 candidates per lane; repository
trimming makes the polling response at most 50 cards per lane and its line batch at most 200 Order IDs.

## Captured write sample

| Operation, 1,000 rows | Without indexes | With both indexes | Observed delta |
|---|---:|---:|---:|
| Batch insert | 173,154,458 ns | 562,845,084 ns | +225.1% |
| `PAID -> ACCEPTED` update | 117,153,250 ns | 404,546,250 ns | +245.3% |

The sample makes the expected write amplification of the state-bearing general index and PAID partial index
visible. The preceding same-condition emulated sample was +90.4%/+82.1%, showing why neither sample establishes the
magnitude for production. The indexes remain accepted for their bounded production-query access paths; native
concurrent mixed read/write measurements are still required before asserting a performance benefit or SLA.

## Fixed-query-count, bounded-response, and cursor evidence

`StoreOrderBoardIntegrationTest.board bounds every lane and exposes exact older work through a signed queue` runs
against PostgreSQL Testcontainers with 51 rows in each executable lane (204 total). Its final 2026-08-14 regression
run recorded:

```text
STORE_ORDER_BOARD_BOUNDED_RESPONSE cards=200 bytes=76889 etag_nanos=103913041
```

The test fixes these properties:

- snapshot has 50 cards in every lane, four exact `overflowCount=1` entries, three list SQL statements
  (primary header, overflow-only count, line batch), and no more than 200 visible Order IDs;
- each on-demand queue is header + line batch only, and all visible plus queued public references equal the
  original 204 references with no duplicate or gap;
- lane/store scope mismatch and expiry return `400 INVALID_REQUEST` before the repository query;
- unchanged board returns the same weak ETag and 304 body is empty; cursor issuance/expiry is intentionally not
  part of that semantic validator;
- `StoreOrderBoardEtagTest` fixes weak tag generation, cursor-renewal invariance, and weak comparison of a
  client-supplied opaque tag;
- `StoreOrderBoard.test.tsx` verifies the browser does not query overflow during 3-second polling and, after
  cursor `400`, reads one unconditional new board snapshot without automatically retrying the queue.

The preceding same-day run recorded 76,890 bytes and 22,268,250 ns, so the ETag duration is also not a stable
latency claim. Response bytes and ETag duration are test-host observations, not a payload budget or latency target.
They are recorded because bounded rows alone do not bound line count or serialized bytes as tightly as a production
traffic measurement would.

## Concurrent load status

**Not run:** this repository has no Plan 60 executable k6 scenario and this workspace has no authenticated
multi-Store deployment target. Consequently, no claim is made about several Stores polling every 3 seconds, DB
connection-pool saturation, CPU, or p95/p99 under concurrent load. Before changing the 3-second cadence or claiming
capacity, run a reproducible authenticated scenario that includes four-lane backlog, conditional 304 reads,
overflow clicks, and concurrent state transitions while collecting HikariCP active/pending, database CPU,
query rows, response bytes, and p95/p99.

Re-run this evidence when V56, `StoreOrderBoardQuerySql`, the executable state set, ordering tuple, lane bound,
cursor TTL, polling interval, PostgreSQL major version, or expected active-board cardinality changes.
