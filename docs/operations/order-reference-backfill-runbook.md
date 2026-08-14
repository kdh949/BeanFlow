# Order reference and display identity backfill

This runbook applies the V50 expand migration, backfills existing orders in bounded transactions, and then permits
V51 to close the migration window. It is the only supported procedure for the Plan 10 order display identity change.

## Safety properties

- V50 is additive. New application code writes all six order fields and the two pickup-reservation grant snapshots.
- The backfill processes orders in stable `(created_at, id)` order and skips complete rows, so the same command is
  safe to restart after interruption.
- Public references are reserved in `ordering_public_reference_registry` in the same transaction as each batch.
- Missing or blank store discovery profiles, missing/mismatched pickup slots, and partially populated display
  identities fail the command. The command never writes a placeholder.
- Backfilled `store_name_snapshot` and pickup windows are current owner values, not guaranteed historical values.
  Record this approximation in the deployment evidence.
- V51 is forward-only. After V51, an old application that omits the new fields cannot write orders. Recover with a
  forward fix; do not downgrade the schema or delete registry/counter rows.

## Preconditions

1. Hold the repository migration-writer lease and confirm no other branch or deployment writes a Flyway migration.
2. Confirm Support V43 through V49 are present in the release artifact and successfully applied to the database.
   If the database is still below V49 or any migration is missing locally, stop and integrate the Support chain before
   Plan 10. Do not apply V50 first or enable Flyway out-of-order mode.
3. Confirm the database is healthy at V49 and take the environment's normal recoverable backup/snapshot.
4. Drain the old application version before applying V50. An old writer must not create orders during backfill.
5. Verify the new artifact contains Support V43~V49, Plan 10 V50/V51, the dual-write order creation path, and this
   command.
6. Prepare datasource credentials through the deployment secret mechanism. Do not put credentials in arguments,
   shell history, logs, or the deployment evidence.

Baseline checks:

```sql
SELECT version, description, success
  FROM flyway_schema_history
 ORDER BY installed_rank DESC
 LIMIT 3;

SELECT count(*) AS orders_before FROM ordering_order;
```

## Expand and dual-write

Deploy the new application with Flyway temporarily capped at V50:

```text
SPRING_FLYWAY_TARGET=50
```

Allow traffic only after the new version is healthy. Every new order must now write `public_reference`,
`pickup_business_date`, `pickup_sequence`, `store_name_snapshot`, and both pickup window snapshots. Do not restore
traffic to the old version.

Confirm V50 and the nullable backfill window:

```sql
SELECT version, success
  FROM flyway_schema_history
 WHERE version = '50';

SELECT count(*) AS remaining
  FROM ordering_order
 WHERE public_reference IS NULL;
```

## Run the bounded backfill

Use the same artifact and datasource configuration as the V50 application. The backfill CLI never runs Flyway itself:
before it writes a row, it requires successful V43 through V50 history entries and rejects an already-applied or failed
V51 entry. Keep the application deployment explicitly capped at `SPRING_FLYWAY_TARGET=50` while this command runs.
Batch size must be between 1 and 1,000; 100 is the default.

```bash
./gradlew order-reference-backfill --args='--batch-size=100'
```

A successful run prints only aggregate counts:

```text
order-reference-backfill completed processed=<count> batches=<count>
```

The command exits non-zero on invalid input, missing owners, partial rows, collision exhaustion, or a database error.
It does not log order, customer, store, or public-reference values. On failure:

1. Keep the application capped at V50.
2. Inspect the aggregate error and database constraints using the approved operational access path.
3. Repair the authoritative Merchant profile or Fulfillment slot; never fill a guessed name/time.
4. Rerun the same command. Complete rows are skipped and committed reference reservations remain reserved.

## Contract preflight

Both queries must return zero before allowing V51:

```sql
SELECT count(*) AS incomplete_orders
  FROM ordering_order
 WHERE public_reference IS NULL
    OR pickup_business_date IS NULL
    OR pickup_sequence IS NULL
    OR store_name_snapshot IS NULL
    OR pickup_window_start_snapshot IS NULL
    OR pickup_window_end_snapshot IS NULL;

SELECT count(*) AS missing_registry_rows
  FROM ordering_order bean_order
  LEFT JOIN ordering_public_reference_registry registry
    ON registry.public_reference = bean_order.public_reference
 WHERE registry.public_reference IS NULL;
```

Also verify uniqueness and counter coverage:

```sql
SELECT public_reference, count(*)
  FROM ordering_order
 GROUP BY public_reference
HAVING count(*) > 1;

SELECT store_id, pickup_business_date, pickup_sequence, count(*)
  FROM ordering_order
 GROUP BY store_id, pickup_business_date, pickup_sequence
HAVING count(*) > 1;

SELECT count(*) AS counter_regressions
  FROM (
        SELECT store_id, pickup_business_date, max(pickup_sequence) AS max_sequence
          FROM ordering_order
         GROUP BY store_id, pickup_business_date
       ) actual
  LEFT JOIN ordering_pickup_counter counter
    ON counter.store_id = actual.store_id
   AND counter.business_date = actual.pickup_business_date
 WHERE counter.last_sequence IS NULL
    OR counter.last_sequence < actual.max_sequence;
```

## Apply V51 and close the window

Remove `SPRING_FLYWAY_TARGET` and roll the same application version. V51 independently repeats the completeness
preflight, adds uniqueness/FK/check/NOT NULL constraints, synchronizes counters, and installs the immutable display
identity trigger. A V51 failure is not success: keep the deployment unavailable or on the healthy V50 pool, correct
the owner data, rerun the backfill, and retry the migration.

After V51, verify:

```sql
SELECT version, success
  FROM flyway_schema_history
 WHERE version IN ('43', '44', '45', '46', '47', '48', '49', '50', '51')
 ORDER BY version;

SELECT count(*) AS nullable_display_columns
  FROM information_schema.columns
 WHERE table_name = 'ordering_order'
   AND column_name IN (
       'public_reference', 'pickup_business_date', 'pickup_sequence',
       'store_name_snapshot', 'pickup_window_start_snapshot', 'pickup_window_end_snapshot'
   )
   AND is_nullable = 'YES';
```

The last query must return `0`. Exercise one new order and both public-reference customer/store reads before declaring
the rollout complete.

## Observability and evidence

Monitor:

- `beanflow.order.reference_backfill.processed.count`
- `beanflow.order.reference_backfill.failed.count`
- `beanflow.order.reference_backfill.duration`
- `beanflow.order.public_reference.collision.count`
- `beanflow.order.public_reference.exhausted.count`
- `beanflow.order.pickup_sequence.allocation.duration` (p95)
- `beanflow.order.public_reference.lookup.count{scope,outcome}`

Retain aggregate command output, Flyway version checks, zero-count preflight results, application health, and the noted
historical-snapshot approximation. Do not retain order identifiers or raw customer/store data in rollout evidence.
