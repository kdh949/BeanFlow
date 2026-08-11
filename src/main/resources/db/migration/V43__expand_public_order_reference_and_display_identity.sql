ALTER TABLE ordering_order
    ADD COLUMN public_reference varchar(12),
    ADD COLUMN pickup_business_date date,
    ADD COLUMN pickup_sequence bigint,
    ADD COLUMN store_name_snapshot varchar(200),
    ADD COLUMN pickup_window_start_snapshot timestamptz,
    ADD COLUMN pickup_window_end_snapshot timestamptz;

ALTER TABLE fulfillment_pickup_reservation
    ADD COLUMN slot_starts_at_snapshot timestamptz,
    ADD COLUMN slot_ends_at_snapshot timestamptz;

UPDATE fulfillment_pickup_reservation reservation
   SET slot_starts_at_snapshot = slot.starts_at,
       slot_ends_at_snapshot = slot.ends_at
  FROM fulfillment_pickup_slot slot
 WHERE slot.id = reservation.slot_id;

CREATE TABLE ordering_public_reference_registry (
    public_reference varchar(12) PRIMARY KEY,
    allocated_at timestamptz NOT NULL,
    CONSTRAINT ck_ordering_public_reference_registry_format
        CHECK (public_reference ~ '^BF-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$')
);

CREATE TABLE ordering_pickup_counter (
    store_id uuid NOT NULL,
    business_date date NOT NULL,
    last_sequence bigint NOT NULL CHECK (last_sequence > 0),
    PRIMARY KEY (store_id, business_date)
);

-- Expand/backfill deployment window:
--
-- 1. Stop at V43 and deploy dual-write application code.
-- 2. Run the bounded order-reference backfill command.
-- 3. Apply V44 only after the command reports zero remaining rows.
--
-- A legacy snapshot uses the current verified profile and slot window. It can therefore differ
-- from what the customer saw when the order was originally created; the runbook requires this
-- approximation to be recorded. Missing owner rows are never replaced with placeholders.
--
-- Initialize each valid legacy store/business-date counter to the number of existing orders so a
-- concurrent new order starts after the reserved legacy rank range. The runtime allocator also
-- recomputes this baseline when it creates a counter row, covering a repaired slot that was absent
-- when V43 ran.
INSERT INTO ordering_pickup_counter (store_id, business_date, last_sequence)
SELECT legacy.store_id, legacy.business_date, count(*)
  FROM (
        SELECT bean_order.id,
               bean_order.store_id,
               (slot.starts_at AT TIME ZONE 'Asia/Seoul')::date AS business_date
          FROM ordering_order bean_order
          JOIN fulfillment_pickup_slot slot
            ON slot.id = bean_order.pickup_slot_id
           AND slot.store_id = bean_order.store_id
       ) legacy
 GROUP BY legacy.store_id, legacy.business_date
ON CONFLICT (store_id, business_date) DO UPDATE
SET last_sequence = GREATEST(ordering_pickup_counter.last_sequence, EXCLUDED.last_sequence);
