CREATE TABLE operations_platform_point_cost_owner (
    id uuid PRIMARY KEY,
    display_name varchar(200) NOT NULL CHECK (length(btrim(display_name)) BETWEEN 1 AND 200 AND display_name !~ '[[:cntrl:]]'),
    normalized_name varchar(200) NOT NULL UNIQUE,
    actor_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL CHECK (length(idempotency_key) BETWEEN 8 AND 128),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    UNIQUE (actor_id, idempotency_key)
);
CREATE TRIGGER trg_platform_point_cost_owner_immutable
    BEFORE UPDATE OR DELETE ON operations_platform_point_cost_owner
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();
INSERT INTO operations_audit_action_category(action, audit_category) VALUES
    ('PLATFORM_POINT_COST_OWNER_REGISTERED', 'OPERATIONS_POLICY');
