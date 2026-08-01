-- For a non-empty V1–V13 database, the release operator must create this exact
-- read-only input relation before Flyway runs V14:
--
-- CREATE TABLE loyalty_point_lot_issuer_precheck (
--     point_lot_id uuid PRIMARY KEY,
--     issuer_type varchar(16) NOT NULL,
--     issuer_reference varchar(240) NOT NULL,
--     source_reference varchar(240) NOT NULL,
--     verified_at timestamptz NOT NULL
-- );
--
-- Every existing loyalty_point_lot must have exactly one source-evidenced row.
-- V14 deliberately does not create or populate this relation: a missing or invalid
-- mapping is a release blocker, not permission to infer a PLATFORM issuer.
DO $$
DECLARE
    legacy_lot_count bigint;
    mapping_row_count bigint;
    unresolved_lot_count bigint;
    unexpected_mapping_count bigint;
    invalid_mapping_count bigint;
BEGIN
    SELECT count(*) INTO legacy_lot_count
      FROM loyalty_point_lot;

    IF legacy_lot_count = 0 THEN
        RETURN;
    END IF;

    IF to_regclass('loyalty_point_lot_issuer_precheck') IS NULL THEN
        RAISE EXCEPTION
            'PointLot issuer precheck failed: % existing lot(s) have no verified mapping relation',
            legacy_lot_count;
    END IF;

    EXECUTE 'LOCK TABLE loyalty_point_lot_issuer_precheck IN SHARE MODE';

    EXECUTE $precheck$
        WITH mapping AS (
            SELECT
                point_lot_id,
                issuer_type,
                issuer_reference,
                source_reference,
                verified_at
            FROM loyalty_point_lot_issuer_precheck
        ),
        unresolved AS (
            SELECT count(*) AS count
            FROM loyalty_point_lot lot
            LEFT JOIN mapping ON mapping.point_lot_id = lot.id
            WHERE mapping.point_lot_id IS NULL
        ),
        unexpected AS (
            SELECT count(*) AS count
            FROM mapping
            LEFT JOIN loyalty_point_lot lot ON lot.id = mapping.point_lot_id
            WHERE lot.id IS NULL
        ),
        invalid AS (
            SELECT count(*) AS count
            FROM mapping
            WHERE issuer_type IS NULL
               OR issuer_type NOT IN ('PLATFORM', 'BRAND', 'STORE')
               OR issuer_reference IS NULL
               OR length(btrim(issuer_reference)) = 0
               OR source_reference IS NULL
               OR length(btrim(source_reference)) = 0
               OR verified_at IS NULL
        )
        SELECT
            (SELECT count(*) FROM mapping),
            (SELECT count FROM unresolved),
            (SELECT count FROM unexpected),
            (SELECT count FROM invalid)
    $precheck$
    INTO mapping_row_count, unresolved_lot_count, unexpected_mapping_count, invalid_mapping_count;

    IF mapping_row_count <> legacy_lot_count
        OR unresolved_lot_count <> 0
        OR unexpected_mapping_count <> 0
        OR invalid_mapping_count <> 0 THEN
        RAISE EXCEPTION
            'PointLot issuer precheck failed: lots=%, mappings=%, unresolved=%, unexpected=%, invalid=%',
            legacy_lot_count,
            mapping_row_count,
            unresolved_lot_count,
            unexpected_mapping_count,
            invalid_mapping_count;
    END IF;
END $$;

ALTER TABLE loyalty_point_lot
    ADD COLUMN issuer_type varchar(16),
    ADD COLUMN issuer_reference varchar(240);

DO $$
BEGIN
    IF to_regclass('loyalty_point_lot_issuer_precheck') IS NOT NULL THEN
        UPDATE loyalty_point_lot lot
           SET issuer_type = mapping.issuer_type,
               issuer_reference = mapping.issuer_reference
          FROM loyalty_point_lot_issuer_precheck mapping
         WHERE mapping.point_lot_id = lot.id;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM loyalty_point_lot
        WHERE issuer_type IS NULL
           OR issuer_type NOT IN ('PLATFORM', 'BRAND', 'STORE')
           OR length(btrim(issuer_reference)) = 0
    ) THEN
        RAISE EXCEPTION 'PointLot issuer backfill did not produce a complete verified mapping';
    END IF;
END $$;

ALTER TABLE loyalty_point_lot
    ALTER COLUMN issuer_type SET NOT NULL,
    ALTER COLUMN issuer_reference SET NOT NULL,
    ADD CONSTRAINT chk_point_lot_issuer_type
        CHECK (issuer_type IN ('PLATFORM', 'BRAND', 'STORE')),
    ADD CONSTRAINT chk_point_lot_issuer_reference
        CHECK (length(btrim(issuer_reference)) > 0);

CREATE FUNCTION reject_point_lot_issuer_snapshot_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.issuer_type IS DISTINCT FROM OLD.issuer_type
        OR NEW.issuer_reference IS DISTINCT FROM OLD.issuer_reference THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'PointLot issuer snapshot is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER point_lot_issuer_snapshot_immutable
    BEFORE UPDATE ON loyalty_point_lot
    FOR EACH ROW
    EXECUTE FUNCTION reject_point_lot_issuer_snapshot_change();
