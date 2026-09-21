-- Do not let the release wait indefinitely for payment writes, and bound the
-- transactional index build itself. A timeout rolls the whole migration back.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

CREATE INDEX IF NOT EXISTS idx_payment_idempotency_payment_id
    ON payment_idempotency_record (payment_id);

-- Staging may already contain the byte-equivalent index from the controlled A/B.
-- IF NOT EXISTS checks only the relation name, so verify the access path before
-- allowing Flyway to record V92 as successful.
DO $$
DECLARE
    expected_definition CONSTANT text :=
        'CREATE INDEX idx_payment_idempotency_payment_id ON public.payment_idempotency_record USING btree (payment_id)';
    actual_definition text;
    actual_valid boolean;
    actual_ready boolean;
BEGIN
    SELECT pg_get_indexdef(index_state.indexrelid),
           index_state.indisvalid,
           index_state.indisready
      INTO actual_definition, actual_valid, actual_ready
      FROM pg_index index_state
     WHERE index_state.indexrelid = to_regclass('public.idx_payment_idempotency_payment_id');

    IF actual_definition IS NULL
        OR actual_definition <> expected_definition
        OR actual_valid IS DISTINCT FROM true
        OR actual_ready IS DISTINCT FROM true THEN
        RAISE EXCEPTION
            'idx_payment_idempotency_payment_id does not match required definition; expected=%, actual=%, valid=%, ready=%',
            expected_definition,
            COALESCE(actual_definition, '<missing>'),
            actual_valid,
            actual_ready;
    END IF;
END
$$;
