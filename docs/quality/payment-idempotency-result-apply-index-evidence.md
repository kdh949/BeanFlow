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

### Controlled conditions

- Date: 2026-09-21 KST.
- Environment: existing perf staging database `beanflow_perf_prr_gcbase2_r12_b1` on the four-core app host.
- API image: `sha256:9b53ce64f27f3752e7c6776ef83b075e5d3e3d1c1a4058d461d12c43b6b87178`
  (`org.opencontainers.image.revision=3c566948429c73c9bf7c89f18c1cc85a9a82eac8`) for both arms.
- Toss driver: `beanflow-perf-toss-driver-gcaudit-r12-b1`; canonical dataset digest
  `f46a92b22dcfaedd0a0d69ba2665b63f05c7f1db5a16b91cf9015b52cf12d599` for both arms.
- Load: lifecycle journey, 12 workflow/s, VU 80, three minutes, identical source hashes, notification result batch 1.
- Before: `payidx-b0-r12-20260921T0010Z`, 2,161 completed workflows.
- After: `payidx-b1-r12-20260921T0024Z`, 2,160 completed workflows.
- Single variable: V92-equivalent `payment_id` index. The application image, configuration, database and driver
  generation stayed fixed. The existing DB had 26,932 rows and a 22 MiB table immediately before DDL.
- Observability gate: `beanflow_container_collection_success == 1`; API and PostgreSQL container CPU series were
  present throughout both measured windows.

The perf database currently has Flyway V90 plus separately staged migrations. Starting the new application image
would have mixed V91 and unrelated code into this A/B, so the byte-equivalent V92 DDL was applied manually with a
five-second lock timeout and a 60-second statement timeout. The index was valid immediately after creation, occupied
848 KiB before the after arm, and grew to 1,480 KiB at 29,814 rows. It is intentionally not inserted into
`flyway_schema_history`; V92 uses `IF NOT EXISTS`, so the normal release can later record V92 without recreating it.
The API stayed healthy with zero restarts and no OOM before and after DDL.

### Result

Prometheus `increase()` values for the individual query are scrape-boundary interpolations, so call counts are
approximate. They are used with the exact same collector and time-window method in both arms.

| Metric | Before | After | Absolute change | Improvement |
| --- | ---: | ---: | ---: | ---: |
| target lookup calls | 2,199.24 | 2,169.83 | -29.41 | 1.34% |
| target lookup total execution | 25.849 s | 0.0492 s | -25.800 s | **99.81%** |
| target lookup mean execution | 11.754 ms/call | 0.0227 ms/call | -11.731 ms | **99.81%** |
| idempotency SQL execution/workflow | 0.017259 s | 0.004518 s | -0.012741 s | **73.82%** |
| idempotency SQL calls/workflow | 36.463 | 36.187 | -0.276 | 0.76% |
| PostgreSQL CPU/workflow | 0.094487 s | 0.081170 s | -0.013318 s | **14.09%** |
| API CPU/workflow | 0.118090 s | 0.105556 s | -0.012534 s | **10.61%** |
| host busy CPU/workflow | 0.269325 s | 0.234288 s | -0.035038 s | **13.01%** |
| transaction wall/workflow | 0.710845 s | 0.483637 s | -0.227208 s | **31.96%** |
| Hikari acquire p99 | 57.91 ms | 32.96 ms | -24.95 ms | **43.09%** |
| pending/max peak (collector value) | 3.6 | 0 | -3.6 | **100%** |
| workflow p95 | 3.424 s | 2.961 s | -0.463 s | **13.53%** |
| workflow p99 | 4.716 s | 4.707 s | -0.009 s | 0.19% |
| HTTP p95 | 216.42 ms | 230.81 ms | +14.38 ms | **-6.65%** |
| HTTP p99 | 447.95 ms | 444.73 ms | -3.22 ms | 0.72% |
| cluster WAL/workflow | 150,533 B | 157,232 B | +6,698 B | **-4.45%** |
| allocation/workflow | 9,383,709 B | 9,386,443 B | +2,734 B | **-0.03%** |
| notification recovery after load | 71.36 s | 69.52 s | -1.84 s | 2.58% |

Both arms had dropped 0, workflow/HTTP failures 0, all 14 database invariant violations 0, active recovery 0,
and complete downstream outcomes for every cohort order. The after arm retained the same number of logical
idempotency calls; the improvement is the access path rather than skipped business work. An actual staging
`EXPLAIN (ANALYZE, BUFFERS)` after deployment used `idx_payment_idempotency_payment_id` and returned the matching row
with a named `Index Scan`.

Verdict: **Confirmed improvement** for this bounded cause. The single index reduced the target query's execution
cost by 99.81% and moved PostgreSQL, API and host CPU/workflow plus Hikari tail in the same direction while preserving
all correctness and recovery gates. This confirms that the unindexed payment lookup was a material contributor to
CPU and pool pressure. It does not prove the full four-core saturation is resolved: this experiment was 12/s, not a
15/s repeat or the 18/s capacity boundary. The 6.65% HTTP p95 regression also needs a repeat before being classified;
its absolute value stayed below the one-second guardrail and HTTP p99 did not regress.

### Grafana evidence

The dashboard was opened directly with each test ID and exact manifest time range. Server panels describe all traffic
in that time range; the k6 panels are test-ID scoped.

| Capture | Scope | SHA-256 |
| --- | --- | --- |
| `grafana-before-causal-panels-final.jpg` | CPU/workflow, transaction/workflow, SQL calls | `b0f8d9bae7f90e175a469c19f7b462c2054e5b066d2fe9191d12e73aec3f6a80` |
| `grafana-after-causal-panels-final.jpg` | same panel positions and selected after test ID | `9acf8e838203ae6ccd450052e7a17ef87b9f77070414abdbb708be58dd7d9585` |
| `grafana-before-sql-resource-panels-final.jpg` | SQL seconds, WAL, allocation, GC and throwable paths | `ed2b8363ccdefb50e16983618185e5153347e3392c998e603fd1e291d62d02e7` |
| `grafana-after-sql-resource-panels-final.jpg` | same resource panels after the index | `d3f65424ce8579ddfae683525c48e34fa1fb3d24dabf1b4e1b7106fb5ddbd918` |

The files are preserved in the ignored local evidence directory
`.ci-artifacts/payment-idempotency-index-2026-09-21/`; only their bounded names and hashes are committed.

## Limits and revisit conditions

- The fixed fixture has one record per payment ID. Re-run when the repository predicate or table distribution changes.
- The index adds write and storage cost; quantify those on the staging table and revisit if idempotency writes dominate.
- Do not claim the host CPU bottleneck is resolved unless the controlled live A/B moves CPU/workflow or the throughput
  boundary while preserving all correctness and recovery gates.
- This A/B confirms a CPU contributor at 12/s. The parent capacity plan still owns the repeated 15/s check and the
  18/s boundary test; no capacity-boundary movement is claimed here.
