DO $$
DECLARE
    incomplete_count bigint;
    missing_registry_count bigint;
BEGIN
    SELECT count(*) INTO incomplete_count
      FROM ordering_order
     WHERE public_reference IS NULL
        OR pickup_business_date IS NULL
        OR pickup_sequence IS NULL
        OR store_name_snapshot IS NULL
        OR pickup_window_start_snapshot IS NULL
        OR pickup_window_end_snapshot IS NULL;

    IF incomplete_count > 0 THEN
        RAISE EXCEPTION
            'public order reference backfill is incomplete: % ordering_order row(s)',
            incomplete_count;
    END IF;

    SELECT count(*) INTO missing_registry_count
      FROM ordering_order bean_order
      LEFT JOIN ordering_public_reference_registry registry
        ON registry.public_reference = bean_order.public_reference
     WHERE registry.public_reference IS NULL;

    IF missing_registry_count > 0 THEN
        RAISE EXCEPTION
            'public order reference backfill registry is incomplete: % ordering_order row(s)',
            missing_registry_count;
    END IF;
END
$$;

DO $$
DECLARE
    incomplete_grant_count bigint;
BEGIN
    SELECT count(*) INTO incomplete_grant_count
      FROM fulfillment_pickup_reservation
     WHERE slot_starts_at_snapshot IS NULL
        OR slot_ends_at_snapshot IS NULL
        OR slot_ends_at_snapshot <= slot_starts_at_snapshot;

    IF incomplete_grant_count > 0 THEN
        RAISE EXCEPTION
            'pickup reservation grant snapshot backfill is incomplete: % row(s)',
            incomplete_grant_count;
    END IF;
END
$$;

ALTER TABLE fulfillment_pickup_reservation
    ALTER COLUMN slot_starts_at_snapshot SET NOT NULL,
    ALTER COLUMN slot_ends_at_snapshot SET NOT NULL,
    ADD CONSTRAINT ck_fulfillment_pickup_reservation_slot_window_snapshot
        CHECK (slot_ends_at_snapshot > slot_starts_at_snapshot);

CREATE UNIQUE INDEX ux_ordering_order_public_reference
    ON ordering_order (public_reference);

CREATE UNIQUE INDEX ux_ordering_order_pickup_sequence
    ON ordering_order (store_id, pickup_business_date, pickup_sequence);

ALTER TABLE ordering_order
    ADD CONSTRAINT fk_ordering_order_public_reference_registry
        FOREIGN KEY (public_reference)
        REFERENCES ordering_public_reference_registry(public_reference),
    ADD CONSTRAINT ck_ordering_order_public_reference_format
        CHECK (public_reference ~ '^BF-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$'),
    ADD CONSTRAINT ck_ordering_order_pickup_sequence_positive
        CHECK (pickup_sequence > 0),
    ADD CONSTRAINT ck_ordering_order_display_snapshot_shape
        CHECK (
            store_name_snapshot = btrim(store_name_snapshot)
            AND length(store_name_snapshot) BETWEEN 1 AND 200
            AND pickup_window_end_snapshot > pickup_window_start_snapshot
        );

ALTER TABLE ordering_order
    ALTER COLUMN public_reference SET NOT NULL,
    ALTER COLUMN pickup_business_date SET NOT NULL,
    ALTER COLUMN pickup_sequence SET NOT NULL,
    ALTER COLUMN store_name_snapshot SET NOT NULL,
    ALTER COLUMN pickup_window_start_snapshot SET NOT NULL,
    ALTER COLUMN pickup_window_end_snapshot SET NOT NULL;

INSERT INTO ordering_pickup_counter (store_id, business_date, last_sequence)
SELECT store_id, pickup_business_date, max(pickup_sequence)
  FROM ordering_order
 GROUP BY store_id, pickup_business_date
ON CONFLICT (store_id, business_date) DO UPDATE
SET last_sequence = GREATEST(ordering_pickup_counter.last_sequence, EXCLUDED.last_sequence);

CREATE OR REPLACE FUNCTION reject_ordering_order_display_identity_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.public_reference IS DISTINCT FROM OLD.public_reference
        OR NEW.pickup_business_date IS DISTINCT FROM OLD.pickup_business_date
        OR NEW.pickup_sequence IS DISTINCT FROM OLD.pickup_sequence
        OR NEW.store_name_snapshot IS DISTINCT FROM OLD.store_name_snapshot
        OR NEW.pickup_window_start_snapshot IS DISTINCT FROM OLD.pickup_window_start_snapshot
        OR NEW.pickup_window_end_snapshot IS DISTINCT FROM OLD.pickup_window_end_snapshot
    THEN
        RAISE EXCEPTION 'ordering_order_display_identity_immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER ordering_order_display_identity_immutable
BEFORE UPDATE ON ordering_order
FOR EACH ROW
EXECUTE FUNCTION reject_ordering_order_display_identity_update();
