CREATE TABLE identity_merchant_login_attempt_source (
    login_id_hmac char(64) NOT NULL,
    ip_hmac char(64) NOT NULL,
    actor_type varchar(16) NOT NULL DEFAULT 'MERCHANT',
    scope_type varchar(16) NOT NULL DEFAULT 'IP',
    last_attempt_at timestamptz NOT NULL,
    PRIMARY KEY (login_id_hmac, ip_hmac),
    CONSTRAINT ck_identity_merchant_attempt_source_login_hmac
        CHECK (login_id_hmac ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_identity_merchant_attempt_source_scope
        CHECK (actor_type = 'MERCHANT' AND scope_type = 'IP'),
    CONSTRAINT fk_identity_merchant_attempt_source_ip
        FOREIGN KEY (actor_type, scope_type, ip_hmac)
        REFERENCES identity_login_attempt (actor_type, scope_type, scope_hmac)
        ON DELETE CASCADE
);

CREATE INDEX ix_identity_merchant_attempt_source_ip
    ON identity_merchant_login_attempt_source (ip_hmac);

CREATE INDEX ix_identity_merchant_attempt_source_retention
    ON identity_merchant_login_attempt_source (last_attempt_at, login_id_hmac, ip_hmac);
