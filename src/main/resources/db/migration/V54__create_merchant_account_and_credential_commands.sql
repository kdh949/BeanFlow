SET LOCAL lock_timeout = '5s';

CREATE TABLE identity_merchant_account (
    id uuid PRIMARY KEY,
    login_id varchar(32) NOT NULL,
    password_hash varchar(255) NOT NULL,
    credential_version bigint NOT NULL DEFAULT 0,
    display_name varchar(100) NOT NULL,
    state varchar(32) NOT NULL,
    temporary_password_expires_at timestamptz,
    password_changed_at timestamptz,
    locked_until timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_identity_merchant_login_id
        CHECK (login_id ~ '^[a-z0-9][a-z0-9._-]{3,30}[a-z0-9]$'),
    CONSTRAINT ck_identity_merchant_password_hash
        CHECK (btrim(password_hash) <> ''),
    CONSTRAINT ck_identity_merchant_display_name
        CHECK (char_length(display_name) BETWEEN 1 AND 100 AND btrim(display_name) <> ''),
    CONSTRAINT ck_identity_merchant_state
        CHECK (state IN ('INITIAL_PASSWORD', 'ACTIVE', 'EXPIRED')),
    CONSTRAINT ck_identity_merchant_state_shape
        CHECK ((state = 'INITIAL_PASSWORD'
                AND temporary_password_expires_at IS NOT NULL
                AND password_changed_at IS NULL)
            OR (state = 'ACTIVE'
                AND temporary_password_expires_at IS NULL
                AND password_changed_at IS NOT NULL)
            OR (state = 'EXPIRED'
                AND temporary_password_expires_at IS NOT NULL
                AND password_changed_at IS NULL)),
    CONSTRAINT ck_identity_merchant_versions
        CHECK (credential_version >= 0 AND version >= 0),
    CONSTRAINT ck_identity_merchant_timestamps
        CHECK (created_at <= updated_at
            AND (temporary_password_expires_at IS NULL
                OR temporary_password_expires_at > created_at)
            AND (password_changed_at IS NULL OR password_changed_at >= created_at))
);

CREATE UNIQUE INDEX ux_identity_merchant_account_login_id
    ON identity_merchant_account (login_id);

CREATE TABLE operations_merchant_credential_command_idempotency (
    id uuid PRIMARY KEY,
    operator_id uuid NOT NULL,
    operation varchar(32) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    payload_hash char(64) NOT NULL,
    merchant_account_id uuid NOT NULL,
    outcome varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    CONSTRAINT ux_operations_merchant_credential_idempotency
        UNIQUE (operator_id, operation, idempotency_key),
    CONSTRAINT ck_operations_merchant_credential_operation
        CHECK (operation IN ('CREATE', 'RESET_TEMPORARY_PASSWORD', 'RELEASE_LOCK')),
    CONSTRAINT ck_operations_merchant_credential_outcome
        CHECK ((operation = 'CREATE' AND outcome = 'ACCOUNT_CREATED')
            OR (operation = 'RESET_TEMPORARY_PASSWORD' AND outcome = 'PASSWORD_RESET')
            OR (operation = 'RELEASE_LOCK' AND outcome = 'LOCK_RELEASED')),
    CONSTRAINT ck_operations_merchant_credential_idempotency_key
        CHECK (length(idempotency_key) BETWEEN 8 AND 128
            AND idempotency_key = btrim(idempotency_key)
            AND idempotency_key !~ '[[:cntrl:]]'),
    CONSTRAINT ck_operations_merchant_credential_payload_hash
        CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_operations_merchant_credential_retention
        CHECK (retention_expires_at = created_at + interval '90 days')
);

CREATE INDEX ix_operations_merchant_credential_idempotency_retention
    ON operations_merchant_credential_command_idempotency (retention_expires_at, id);

CREATE FUNCTION reject_merchant_credential_idempotency_change()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'Merchant credential terminal idempotency row is immutable';
END;
$$;

CREATE TRIGGER merchant_credential_idempotency_immutable
    BEFORE UPDATE ON operations_merchant_credential_command_idempotency
    FOR EACH ROW EXECUTE FUNCTION reject_merchant_credential_idempotency_change();

ALTER TABLE operations_audit_record
    DROP CONSTRAINT chk_audit_actor_type,
    ADD CONSTRAINT chk_audit_actor_type
        CHECK (actor_type IN (
            'CUSTOMER', 'MERCHANT', 'STORE_OWNER', 'STORE_STAFF', 'PLATFORM_OPERATOR', 'SYSTEM'
        ));

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('MERCHANT_ACCOUNT_CREATED', 'SECURITY_AND_PERMISSION'),
    ('MERCHANT_PASSWORD_CHANGED', 'SECURITY_AND_PERMISSION'),
    ('MERCHANT_TEMPORARY_PASSWORD_RESET', 'SECURITY_AND_PERMISSION'),
    ('MERCHANT_LOCK_RELEASED', 'SECURITY_AND_PERMISSION'),
    ('MERCHANT_ACCOUNT_READ', 'SECURITY_AND_PERMISSION');
