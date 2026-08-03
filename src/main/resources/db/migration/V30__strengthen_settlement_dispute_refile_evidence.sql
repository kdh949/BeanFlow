CREATE OR REPLACE FUNCTION settlement_dispute_validate_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    item_store_id uuid;
    item_batch_state varchar(24);
    previous_item_id uuid;
    previous_state varchar(24);
    previous_refile_count smallint;
    previous_evidence jsonb;
BEGIN
    SELECT item.store_id, batch.state
      INTO item_store_id, item_batch_state
      FROM settlement_item item
      JOIN settlement_batch batch ON batch.id = item.settlement_batch_id
     WHERE item.id = NEW.settlement_item_id
     FOR SHARE OF item, batch;

    IF NOT FOUND OR item_store_id IS DISTINCT FROM NEW.store_id THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementDispute Item scope is invalid';
    END IF;
    IF item_batch_state <> 'CONFIRMED' THEN
        RAISE EXCEPTION USING
            ERRCODE = '23514',
            MESSAGE = 'SettlementDispute requires a confirmed Item';
    END IF;

    IF NEW.previous_dispute_id IS NOT NULL THEN
        SELECT settlement_item_id, state, refile_count, evidence_references
          INTO previous_item_id, previous_state, previous_refile_count, previous_evidence
          FROM settlement_dispute
         WHERE id = NEW.previous_dispute_id
         FOR SHARE;
        IF NOT FOUND
            OR previous_item_id IS DISTINCT FROM NEW.settlement_item_id
            OR previous_state NOT IN ('ACCEPTED', 'REJECTED', 'WITHDRAWN')
            OR previous_refile_count <> 0
            OR NOT EXISTS (
                SELECT 1
                  FROM jsonb_array_elements_text(NEW.evidence_references) candidate(reference)
                 WHERE NOT previous_evidence ? candidate.reference
            ) THEN
            RAISE EXCEPTION USING
                ERRCODE = '23514',
                MESSAGE = 'SettlementDispute refile guard failed';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
