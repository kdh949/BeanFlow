CREATE TABLE merchant_store_settlement_terms (
    terms_version_id uuid PRIMARY KEY,
    store_id uuid NOT NULL REFERENCES merchant_store(id),
    source_reference varchar(240) NOT NULL UNIQUE
        CHECK (length(btrim(source_reference)) > 0),
    fee_rate_bps integer NOT NULL CHECK (fee_rate_bps BETWEEN 0 AND 10000),
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    created_at timestamptz NOT NULL,
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX idx_store_settlement_terms_applicable
    ON merchant_store_settlement_terms (store_id, effective_from DESC, terms_version_id);

CREATE FUNCTION reject_overlapping_store_settlement_terms()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.store_id::text, 0));
    IF EXISTS (
        SELECT 1
          FROM merchant_store_settlement_terms existing
         WHERE existing.store_id = NEW.store_id
           AND existing.terms_version_id <> NEW.terms_version_id
           AND existing.effective_from < COALESCE(NEW.effective_to, 'infinity'::timestamptz)
           AND NEW.effective_from < COALESCE(existing.effective_to, 'infinity'::timestamptz)
    ) THEN
        RAISE EXCEPTION USING
            ERRCODE = '23P01',
            MESSAGE = 'StoreSettlementTerms effective intervals overlap';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER store_settlement_terms_no_overlap
    BEFORE INSERT ON merchant_store_settlement_terms
    FOR EACH ROW EXECUTE FUNCTION reject_overlapping_store_settlement_terms();

CREATE FUNCTION reject_store_settlement_terms_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'StoreSettlementTerms versions are immutable';
END;
$$;

CREATE TRIGGER store_settlement_terms_immutable
    BEFORE UPDATE OR DELETE ON merchant_store_settlement_terms
    FOR EACH ROW EXECUTE FUNCTION reject_store_settlement_terms_change();
