-- Productization Plan 30 owns this migration.
-- CustomerAccount and its Loyalty PointAccount are provisioned by application ports in one
-- PostgreSQL transaction. This migration intentionally neither owns nor backfills Loyalty rows.
CREATE TABLE identity_customer_account (
    id uuid PRIMARY KEY,
    login_id varchar(32) NOT NULL,
    password_hash varchar(255) NOT NULL,
    credential_version bigint NOT NULL DEFAULT 0,
    display_name varchar(100) NOT NULL,
    state varchar(32) NOT NULL,
    locked_until timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_identity_customer_login_id
        CHECK (login_id ~ '^[a-z0-9][a-z0-9._-]{3,30}[a-z0-9]$'),
    CONSTRAINT ck_identity_customer_password_hash
        CHECK (btrim(password_hash) <> ''),
    CONSTRAINT ck_identity_customer_display_name
        CHECK (char_length(display_name) BETWEEN 1 AND 100 AND btrim(display_name) <> ''),
    CONSTRAINT ck_identity_customer_state
        CHECK (state IN ('ACTIVE', 'LOCKED')),
    CONSTRAINT ck_identity_customer_lock_shape
        CHECK ((state = 'ACTIVE' AND locked_until IS NULL)
            OR (state = 'LOCKED' AND locked_until IS NOT NULL)),
    CONSTRAINT ck_identity_customer_versions
        CHECK (credential_version >= 0 AND version >= 0),
    CONSTRAINT ck_identity_customer_timestamps
        CHECK (created_at <= updated_at)
);

CREATE UNIQUE INDEX ux_identity_customer_account_login_id
    ON identity_customer_account (login_id);

CREATE TABLE identity_login_attempt (
    id uuid PRIMARY KEY,
    actor_type varchar(16) NOT NULL,
    scope_type varchar(16) NOT NULL,
    scope_hmac char(64) NOT NULL,
    window_start timestamptz NOT NULL,
    failure_count integer NOT NULL,
    blocked_until timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ux_identity_login_attempt_scope
        UNIQUE (actor_type, scope_type, scope_hmac),
    CONSTRAINT ck_identity_login_attempt_actor
        CHECK (actor_type IN ('CUSTOMER', 'MERCHANT')),
    CONSTRAINT ck_identity_login_attempt_scope
        CHECK (scope_type IN ('LOGIN_ID', 'IP')),
    CONSTRAINT ck_identity_login_attempt_hmac
        CHECK (scope_hmac ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_identity_login_attempt_count
        CHECK ((scope_type = 'LOGIN_ID' AND failure_count BETWEEN 1 AND 5)
            OR (scope_type = 'IP' AND failure_count BETWEEN 1 AND 30)),
    CONSTRAINT ck_identity_login_attempt_block_shape
        CHECK ((scope_type = 'LOGIN_ID'
                AND ((failure_count < 5 AND blocked_until IS NULL)
                    OR (failure_count = 5 AND blocked_until IS NOT NULL)))
            OR (scope_type = 'IP'
                AND ((failure_count < 30 AND blocked_until IS NULL)
                    OR (failure_count = 30 AND blocked_until IS NOT NULL)))),
    CONSTRAINT ck_identity_login_attempt_timestamps
        CHECK (window_start <= updated_at
            AND (blocked_until IS NULL OR blocked_until > updated_at))
);

CREATE INDEX ix_identity_login_attempt_retention
    ON identity_login_attempt (updated_at, id);
