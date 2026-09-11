CREATE TABLE support_compensation_incident (
    id uuid PRIMARY KEY,
    origin_case_id uuid NOT NULL REFERENCES support_case(id),
    customer_id uuid NOT NULL,
    order_id uuid,
    category varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    actor_id uuid NOT NULL,
    idempotency_key varchar(128) NOT NULL CHECK (length(idempotency_key) BETWEEN 8 AND 128),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    UNIQUE (actor_id, idempotency_key),
    CHECK (occurred_at <= created_at)
);
CREATE INDEX ix_support_compensation_incident_scope ON support_compensation_incident(customer_id, order_id, id);
CREATE TRIGGER trg_support_compensation_incident_immutable
    BEFORE UPDATE OR DELETE ON support_compensation_incident
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

INSERT INTO operations_audit_action_category(action, audit_category) VALUES
    ('SUPPORT_COMPENSATION_INCIDENT_REGISTERED', 'OPERATIONS_POLICY'),
    ('SUPPORT_COMPENSATION_INCIDENTS_READ', 'OPERATIONS_POLICY');
