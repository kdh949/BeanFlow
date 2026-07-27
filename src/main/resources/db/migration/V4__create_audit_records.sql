CREATE TABLE operations_audit_record (
    id uuid PRIMARY KEY,
    actor_id varchar(160) NOT NULL,
    actor_type varchar(24) NOT NULL CHECK (actor_type IN ('CUSTOMER', 'SYSTEM')),
    action varchar(100) NOT NULL,
    target_type varchar(100) NOT NULL,
    target_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    reason varchar(160) NOT NULL,
    before_summary text NOT NULL,
    after_summary text NOT NULL,
    correlation_id varchar(160) NOT NULL,
    source_reference varchar(200) NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    UNIQUE (action, target_type, target_id, source_reference)
);

CREATE INDEX idx_audit_target
    ON operations_audit_record (target_type, target_id, occurred_at);

CREATE INDEX idx_audit_correlation
    ON operations_audit_record (correlation_id, occurred_at);

CREATE INDEX idx_audit_retention
    ON operations_audit_record (retention_expires_at, id);
