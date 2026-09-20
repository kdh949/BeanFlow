ALTER TABLE identity_customer_account ADD COLUMN demo_store_id uuid REFERENCES merchant_store(id),
    ADD COLUMN demo_expires_at timestamptz,
    ADD CONSTRAINT ck_customer_demo_scope CHECK ((demo_store_id IS NULL) = (demo_expires_at IS NULL));
CREATE UNIQUE INDEX uq_customer_demo_store ON identity_customer_account(demo_store_id) WHERE demo_store_id IS NOT NULL;
ALTER TABLE identity_merchant_account ADD COLUMN demo_expires_at timestamptz;
CREATE TABLE demo_workspace (
    ordinal bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    id uuid PRIMARY KEY, browser_hash varchar(64) NOT NULL, start_key varchar(128) NOT NULL,
    mode varchar(16) NOT NULL CHECK (mode IN ('GUIDED', 'DIRECT')),
    customer_id uuid NOT NULL UNIQUE REFERENCES identity_customer_account(id),
    merchant_id uuid NOT NULL UNIQUE REFERENCES identity_merchant_account(id),
    store_id uuid NOT NULL UNIQUE REFERENCES merchant_store(id),
    menu_id uuid NOT NULL REFERENCES merchant_menu(id),
    customer_session_id varchar(128) NOT NULL, merchant_session_id varchar(128) NOT NULL,
    order_reference varchar(12), created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, ended_at timestamptz,
    CHECK (expires_at > created_at), UNIQUE (browser_hash, start_key)
);
CREATE INDEX ix_demo_workspace_browser ON demo_workspace(browser_hash, ordinal DESC);
CREATE INDEX ix_demo_workspace_expiry ON demo_workspace(expires_at) WHERE ended_at IS NULL;
CREATE INDEX ix_demo_workspace_created ON demo_workspace(created_at);
CREATE TABLE demo_workspace_command (
    workspace_id uuid NOT NULL REFERENCES demo_workspace(id), request_key varchar(128) NOT NULL,
    operation varchar(16) NOT NULL, payload varchar(200) NOT NULL, order_reference varchar(12) NOT NULL,
    PRIMARY KEY (workspace_id, request_key)
);

-- Audit actions are a closed vocabulary enforced by the audit owner's foreign key.
INSERT INTO operations_audit_action_category(action, audit_category) VALUES
    ('DEMO_WORKSPACE_CREATED', 'SECURITY_AND_PERMISSION'),
    ('DEMO_SAMPLE_CREATED', 'SECURITY_AND_PERMISSION'),
    ('DEMO_WORKSPACE_ENDED', 'SECURITY_AND_PERMISSION');
