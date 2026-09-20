# Payment idempotency result_apply index evidence

## Fixed query-plan measurement

- Date: 2026-09-20.
- Reproduce with `./gradlew test --tests '*PaymentIdempotencyQueryMigrationTest*' --rerun-tasks`.
- Database: PostgreSQL 17.5 through the repository Testcontainers fixture.
- Fixture: 25,000 idempotency records with distinct payment IDs and one matching row.
- Query: the full entity projection used by `findByPaymentId(paymentId)`.
- Method: apply Flyway V1 through V92 to prove the production index exists. In a separate fixed schema, capture
  `EXPLAIN (ANALYZE, BUFFERS)` before and after adding the exact V92 index. Planner settings are not forced.

| Condition | Plan | Rows filtered | Buffers | Execution time |
| --- | --- | ---: | --- | ---: |
| without V92 | `Seq Scan` | 24,999 | shared hit 658 | 3.465 ms |
| with V92 | `Index Scan using idx_payment_idempotency_payment_id` | 0 | shared hit 1, read 2 | 1.081 ms |

This capture proves the equality predicate reaches the intended index for the fixed distribution. The measured
execution-time delta is 2.384 ms (68.8%) for one warm Testcontainers capture; it is query-plan evidence, not a
production latency or throughput claim. Planning time increased in this short-lived capture and is not interpreted.

## Staging A/B

Pending the container exporter live gate. The comparison will use the same existing staging DB, API image, Toss
driver generation, fixture, 12 workflow/s, VU 80 and three-minute duration. The report will record table/index size,
result_apply SQL calls and seconds per workflow, API/PostgreSQL/host CPU seconds per workflow, latency, Hikari,
dropped, invariants and recovery before and after applying V92-equivalent DDL.

## Limits and revisit conditions

- The fixed fixture has one record per payment ID. Re-run when the repository predicate or table distribution changes.
- The index adds write and storage cost; quantify those on the staging table and revisit if idempotency writes dominate.
- Do not claim the host CPU bottleneck is resolved unless the controlled live A/B moves CPU/workflow or the throughput
  boundary while preserving all correctness and recovery gates.
