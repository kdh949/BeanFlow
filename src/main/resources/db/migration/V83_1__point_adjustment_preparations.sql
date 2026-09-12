CREATE TABLE loyalty_point_adjustment_preparation (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    point_account_id uuid NOT NULL REFERENCES loyalty_point_account(id),
    request_body text NOT NULL,
    payload_hash char(64) NOT NULL,
    state varchar(16) NOT NULL CHECK (state IN ('PREPARED', 'APPLIED', 'CANCELLED')),
    response_body text,
    created_at timestamptz NOT NULL,
    dismissed_at timestamptz,
    CHECK ((state = 'APPLIED') = (response_body IS NOT NULL)),
    CHECK (state <> 'CANCELLED' OR dismissed_at IS NOT NULL),
    CHECK (dismissed_at IS NULL OR (state <> 'PREPARED' AND dismissed_at >= created_at))
);
CREATE UNIQUE INDEX uq_point_adjustment_preparation_open_actor
    ON loyalty_point_adjustment_preparation(actor_id) WHERE dismissed_at IS NULL;
CREATE INDEX idx_point_adjustment_preparation_retention
    ON loyalty_point_adjustment_preparation(dismissed_at, id) WHERE dismissed_at IS NOT NULL;

CREATE FUNCTION protect_point_adjustment_preparation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.actor_id, NEW.point_account_id, NEW.request_body, NEW.payload_hash, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.actor_id, OLD.point_account_id, OLD.request_body, OLD.payload_hash, OLD.created_at)
       OR (OLD.state <> 'PREPARED' AND NEW.state IS DISTINCT FROM OLD.state)
       OR (OLD.response_body IS NOT NULL AND NEW.response_body IS DISTINCT FROM OLD.response_body)
       OR (OLD.dismissed_at IS NOT NULL AND NEW.dismissed_at IS DISTINCT FROM OLD.dismissed_at) THEN
        RAISE EXCEPTION 'Point adjustment preparation is immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_point_adjustment_preparation_immutable
    BEFORE UPDATE ON loyalty_point_adjustment_preparation
    FOR EACH ROW EXECUTE FUNCTION protect_point_adjustment_preparation();

INSERT INTO operations_audit_action_category(action, audit_category) VALUES
    ('POINT_ADJUSTMENT_PREPARED', 'OPERATIONS_POLICY'),
    ('POINT_ADJUSTMENT_PREPARATION_READ', 'PII_ACCESS'),
    ('POINT_ADJUSTMENT_PREPARATION_DISMISSED', 'OPERATIONS_POLICY');
