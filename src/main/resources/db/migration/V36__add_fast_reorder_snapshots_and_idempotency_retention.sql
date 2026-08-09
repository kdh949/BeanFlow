ALTER TABLE ordering_order_line
    ADD COLUMN option_selection_snapshot_state varchar(32),
    ADD COLUMN normalized_option_ids_json jsonb;

CREATE OR REPLACE FUNCTION beanflow_is_canonical_uuid_jsonb_array(candidate jsonb)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    element jsonb;
    element_text text;
    current_uuid uuid;
    previous_uuid uuid;
BEGIN
    IF jsonb_typeof(candidate) <> 'array' THEN
        RETURN false;
    END IF;

    FOR element IN SELECT value FROM jsonb_array_elements(candidate)
    LOOP
        IF jsonb_typeof(element) <> 'string' THEN
            RETURN false;
        END IF;
        element_text := element #>> '{}';
        BEGIN
            current_uuid := element_text::uuid;
        EXCEPTION
            WHEN invalid_text_representation THEN
                RETURN false;
        END;
        IF current_uuid::text <> element_text THEN
            RETURN false;
        END IF;
        IF previous_uuid IS NOT NULL AND previous_uuid >= current_uuid THEN
            RETURN false;
        END IF;
        previous_uuid := current_uuid;
    END LOOP;
    RETURN true;
END
$$;

CREATE OR REPLACE FUNCTION beanflow_is_canonical_uuid_csv(candidate text)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    element_text text;
    current_uuid uuid;
    previous_uuid uuid;
BEGIN
    IF candidate = '' THEN
        RETURN true;
    END IF;

    FOREACH element_text IN ARRAY string_to_array(candidate, ',')
    LOOP
        BEGIN
            current_uuid := element_text::uuid;
        EXCEPTION
            WHEN invalid_text_representation THEN
                RETURN false;
        END;
        IF current_uuid::text <> element_text THEN
            RETURN false;
        END IF;
        IF previous_uuid IS NOT NULL AND previous_uuid >= current_uuid THEN
            RETURN false;
        END IF;
        previous_uuid := current_uuid;
    END LOOP;
    RETURN true;
END
$$;

UPDATE ordering_order_line
   SET option_selection_snapshot_state = 'LEGACY_UNAVAILABLE',
       normalized_option_ids_json = NULL;

ALTER TABLE ordering_order_line
    ALTER COLUMN option_selection_snapshot_state SET NOT NULL,
    ADD CONSTRAINT ck_ordering_order_line_option_selection_snapshot
        CHECK (
            (option_selection_snapshot_state = 'LEGACY_UNAVAILABLE'
                AND normalized_option_ids_json IS NULL)
            OR
            (option_selection_snapshot_state = 'SNAPSHOTTED'
                AND normalized_option_ids_json IS NOT NULL
                AND beanflow_is_canonical_uuid_jsonb_array(normalized_option_ids_json))
        );

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM merchant_menu_configuration
         WHERE NOT beanflow_is_canonical_uuid_csv(normalized_option_key)
    ) THEN
        RAISE EXCEPTION 'merchant menu configuration has a non-canonical normalized_option_key';
    END IF;
END
$$;

ALTER TABLE merchant_menu_configuration
    ADD CONSTRAINT ck_merchant_menu_configuration_normalized_option_key
        CHECK (beanflow_is_canonical_uuid_csv(normalized_option_key));

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM ordering_idempotency_record
         WHERE status IN ('COMPLETED', 'FAILED')
           AND completed_at IS NULL
    ) THEN
        RAISE EXCEPTION 'ordering idempotency terminal row without completed_at';
    END IF;
END
$$;

ALTER TABLE ordering_idempotency_record
    ADD COLUMN retention_expires_at timestamptz,
    ADD COLUMN manual_review_reason varchar(64),
    ADD COLUMN manual_review_started_at timestamptz,
    ADD COLUMN intended_order_exists boolean;

UPDATE ordering_idempotency_record
   SET retention_expires_at = completed_at + interval '90 days'
 WHERE status IN ('COMPLETED', 'FAILED');

UPDATE ordering_idempotency_record record
   SET manual_review_reason = 'LEGACY_UNSPECIFIED',
       manual_review_started_at = record.started_at,
       intended_order_exists = EXISTS (
           SELECT 1
             FROM ordering_order bean_order
            WHERE bean_order.id = record.intended_order_id
       )
 WHERE record.status = 'MANUAL_REVIEW';

ALTER TABLE ordering_idempotency_record
    ADD CONSTRAINT ck_ordering_idempotency_terminal_retention
        CHECK (
            (status IN ('COMPLETED', 'FAILED')
                AND completed_at IS NOT NULL
                AND retention_expires_at = completed_at + interval '90 days')
            OR
            (status IN ('PROCESSING', 'MANUAL_REVIEW')
                AND retention_expires_at IS NULL)
        ),
    ADD CONSTRAINT ck_ordering_idempotency_manual_review_metadata
        CHECK (
            (status = 'MANUAL_REVIEW'
                AND manual_review_reason IS NOT NULL
                AND manual_review_started_at IS NOT NULL
                AND intended_order_exists IS NOT NULL
                AND response_status IS NULL
                AND response_body IS NULL
                AND response_version IS NULL
                AND completed_at IS NULL)
            OR
            (status <> 'MANUAL_REVIEW'
                AND manual_review_reason IS NULL
                AND manual_review_started_at IS NULL
                AND intended_order_exists IS NULL)
        );

CREATE INDEX idx_ordering_idempotency_terminal_retention
    ON ordering_idempotency_record (retention_expires_at, id)
    WHERE retention_expires_at IS NOT NULL;
