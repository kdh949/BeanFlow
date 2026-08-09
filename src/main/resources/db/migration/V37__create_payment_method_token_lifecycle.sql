DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM payment_method
         WHERE provider = 'TOSS_PAYMENTS'
    ) THEN
        RAISE EXCEPTION 'existing TOSS_PAYMENTS payment method has no verified provider customer reference';
    END IF;
END
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM payment_method
         GROUP BY provider, token_reference
        HAVING count(DISTINCT customer_id) > 1
    ) THEN
        RAISE EXCEPTION 'payment method provider token is bound to multiple owners';
    END IF;
END
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM payment_payment payment
          LEFT JOIN payment_method method ON method.id = payment.payment_method_id
         WHERE payment.type = 'EXTERNAL'
           AND (
               payment.payment_method_id IS NULL
               OR method.id IS NULL
               OR method.customer_id <> payment.customer_id
               OR length(trim(method.provider)) = 0
               OR length(trim(method.token_reference)) = 0
           )
    ) THEN
        RAISE EXCEPTION 'external payment has no unambiguous payment method binding';
    END IF;
END
$$;

DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT conname
      INTO constraint_name
      FROM pg_constraint
     WHERE conrelid = 'payment_method'::regclass
       AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%REVOKED%';

    IF constraint_name IS NULL THEN
        RAISE EXCEPTION 'payment method lifecycle constraint was not found';
    END IF;

    EXECUTE format('ALTER TABLE payment_method DROP CONSTRAINT %I', constraint_name);
END
$$;

ALTER TABLE payment_method
    ALTER COLUMN status TYPE varchar(40),
    ADD COLUMN provider_customer_reference varchar(200),
    ADD COLUMN is_default boolean NOT NULL DEFAULT false;

UPDATE payment_method
   SET status = 'DEACTIVATED'
 WHERE status = 'REVOKED';

ALTER TABLE payment_method
    ADD CONSTRAINT ck_payment_method_lifecycle_status
        CHECK (status IN (
            'ACTIVE', 'DEACTIVATION_REQUESTED', 'DEACTIVATION_UNKNOWN',
            'RECONCILING', 'MANUAL_REVIEW', 'DEACTIVATED'
        )),
    ADD CONSTRAINT ck_payment_method_provider_customer_reference
        CHECK (
            (provider = 'TOSS_PAYMENTS'
                AND provider_customer_reference IS NOT NULL
                AND provider_customer_reference ~ '^bf_[A-Za-z0-9_-]{43}$')
            OR
            (provider <> 'TOSS_PAYMENTS' AND provider_customer_reference IS NULL)
        ),
    ADD CONSTRAINT ck_payment_method_active_default
        CHECK (NOT is_default OR status = 'ACTIVE');

CREATE UNIQUE INDEX uq_payment_method_customer_active_default
    ON payment_method (customer_id)
    WHERE is_default = true AND status = 'ACTIVE';

CREATE INDEX idx_payment_method_provider_token
    ON payment_method (provider, token_reference, id);

CREATE INDEX idx_payment_method_customer_lifecycle
    ON payment_method (customer_id, is_default DESC, created_at DESC, id DESC)
    WHERE provider = 'TOSS_PAYMENTS';

CREATE TABLE payment_method_registration (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(80) NOT NULL CHECK (operation = 'REGISTER_PAYMENT_METHOD_V1'),
    idempotency_key varchar(128) NOT NULL CHECK (length(idempotency_key) BETWEEN 8 AND 128),
    customer_id uuid NOT NULL,
    intended_payment_method_id uuid NOT NULL,
    provider varchar(40) NOT NULL CHECK (provider = 'TOSS_PAYMENTS'),
    authorization_key_hash varchar(64) NOT NULL CHECK (authorization_key_hash ~ '^[0-9a-f]{64}$'),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    display_alias varchar(80) NOT NULL CHECK (
        length(display_alias) BETWEEN 1 AND 80
        AND display_alias = trim(display_alias)
        AND display_alias !~ '[[:cntrl:]]'
    ),
    provider_customer_reference varchar(200) NOT NULL
        CHECK (provider_customer_reference ~ '^bf_[A-Za-z0-9_-]{43}$'),
    status varchar(32) NOT NULL CHECK (status IN (
        'READY', 'PROCESSING', 'COMPLETED', 'REJECTED',
        'REGISTRATION_UNKNOWN', 'MISCONFIGURED_RETRYABLE', 'MANUAL_REVIEW'
    )),
    claim_token uuid,
    claim_started_at timestamptz,
    first_response_status integer,
    first_response_body text,
    started_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    terminal_at timestamptz,
    retention_expires_at timestamptz,
    manual_review_reason varchar(64),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_method_registration_idempotency
        UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT uq_payment_method_registration_auth_key
        UNIQUE (customer_id, provider, authorization_key_hash),
    CONSTRAINT ck_payment_method_registration_claim CHECK (
        (claim_token IS NULL AND claim_started_at IS NULL)
        OR (claim_token IS NOT NULL AND claim_started_at IS NOT NULL)
    ),
    CONSTRAINT ck_payment_method_registration_response CHECK (
        (first_response_status IS NULL AND first_response_body IS NULL)
        OR (first_response_status IS NOT NULL AND first_response_body IS NOT NULL)
    ),
    CONSTRAINT ck_payment_method_registration_retention CHECK (
        (status IN ('COMPLETED', 'REJECTED')
            AND terminal_at IS NOT NULL
            AND retention_expires_at = terminal_at + interval '90 days')
        OR
        (status NOT IN ('COMPLETED', 'REJECTED')
            AND terminal_at IS NULL
            AND retention_expires_at IS NULL)
    ),
    CONSTRAINT ck_payment_method_registration_manual_review CHECK (
        (status = 'MANUAL_REVIEW' AND manual_review_reason IS NOT NULL)
        OR (status <> 'MANUAL_REVIEW' AND manual_review_reason IS NULL)
    )
);

CREATE INDEX idx_payment_method_registration_ready
    ON payment_method_registration (updated_at, id)
    WHERE status IN ('READY', 'MISCONFIGURED_RETRYABLE');

CREATE INDEX idx_payment_method_registration_terminal_retention
    ON payment_method_registration (retention_expires_at, id)
    WHERE retention_expires_at IS NOT NULL;

CREATE TABLE payment_method_default_command (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(80) NOT NULL CHECK (operation = 'SET_DEFAULT_PAYMENT_METHOD_V1'),
    idempotency_key varchar(128) NOT NULL CHECK (length(idempotency_key) BETWEEN 8 AND 128),
    customer_id uuid NOT NULL,
    payment_method_id uuid NOT NULL REFERENCES payment_method(id),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    first_response_status integer NOT NULL CHECK (first_response_status = 200),
    first_response_body text NOT NULL,
    started_at timestamptz NOT NULL,
    terminal_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_method_default_idempotency
        UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT ck_payment_method_default_retention
        CHECK (retention_expires_at = terminal_at + interval '90 days')
);

CREATE INDEX idx_payment_method_default_terminal_retention
    ON payment_method_default_command (retention_expires_at, id);

CREATE TABLE payment_method_deactivation (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(80) NOT NULL CHECK (operation = 'DEACTIVATE_PAYMENT_METHOD_V1'),
    idempotency_key varchar(128) NOT NULL CHECK (length(idempotency_key) BETWEEN 8 AND 128),
    customer_id uuid NOT NULL,
    payment_method_id uuid NOT NULL REFERENCES payment_method(id),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    status varchar(32) NOT NULL CHECK (status IN (
        'READY', 'PROCESSING', 'DEACTIVATION_UNKNOWN',
        'RECONCILING', 'MANUAL_REVIEW', 'COMPLETED'
    )),
    claim_token uuid,
    claim_started_at timestamptz,
    unknown_at timestamptz,
    manual_review_at timestamptz,
    first_response_status integer,
    first_response_body text,
    started_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    terminal_at timestamptz,
    retention_expires_at timestamptz,
    manual_review_reason varchar(64),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_method_deactivation_idempotency
        UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT ck_payment_method_deactivation_claim CHECK (
        (claim_token IS NULL AND claim_started_at IS NULL)
        OR (claim_token IS NOT NULL AND claim_started_at IS NOT NULL)
    ),
    CONSTRAINT ck_payment_method_deactivation_unknown_deadline CHECK (
        (status IN ('DEACTIVATION_UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW')
            AND unknown_at IS NOT NULL
            AND manual_review_at = unknown_at + interval '96 hours')
        OR
        (status NOT IN ('DEACTIVATION_UNKNOWN', 'RECONCILING', 'MANUAL_REVIEW')
            AND unknown_at IS NULL
            AND manual_review_at IS NULL)
    ),
    CONSTRAINT ck_payment_method_deactivation_response CHECK (
        (first_response_status IS NULL AND first_response_body IS NULL)
        OR (first_response_status IS NOT NULL AND first_response_body IS NOT NULL)
    ),
    CONSTRAINT ck_payment_method_deactivation_retention CHECK (
        (status = 'COMPLETED'
            AND terminal_at IS NOT NULL
            AND retention_expires_at = terminal_at + interval '90 days')
        OR
        (status <> 'COMPLETED'
            AND terminal_at IS NULL
            AND retention_expires_at IS NULL)
    ),
    CONSTRAINT ck_payment_method_deactivation_manual_review CHECK (
        (status = 'MANUAL_REVIEW' AND manual_review_reason IS NOT NULL)
        OR (status <> 'MANUAL_REVIEW' AND manual_review_reason IS NULL)
    )
);

CREATE UNIQUE INDEX uq_payment_method_deactivation_active_work
    ON payment_method_deactivation (payment_method_id)
    WHERE status <> 'COMPLETED';

CREATE INDEX idx_payment_method_deactivation_due
    ON payment_method_deactivation (manual_review_at, id)
    WHERE status IN ('DEACTIVATION_UNKNOWN', 'RECONCILING');

CREATE INDEX idx_payment_method_deactivation_terminal_retention
    ON payment_method_deactivation (retention_expires_at, id)
    WHERE retention_expires_at IS NOT NULL;

CREATE TABLE payment_provider_notification_inbox (
    id uuid PRIMARY KEY,
    provider varchar(40) NOT NULL CHECK (provider = 'TOSS_PAYMENTS'),
    notification_id varchar(200) NOT NULL CHECK (length(trim(notification_id)) > 0),
    notification_type varchar(40) NOT NULL CHECK (notification_type = 'BILLING_DELETED'),
    token_fingerprint varchar(64) NOT NULL CHECK (token_fingerprint ~ '^[0-9a-f]{64}$'),
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    processed_at timestamptz,
    status varchar(24) NOT NULL CHECK (status IN ('ACCEPTED', 'PROCESSED', 'MANUAL_REVIEW')),
    closed_reason varchar(64),
    retention_expires_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_provider_notification UNIQUE (provider, notification_id),
    CONSTRAINT ck_payment_provider_notification_terminal CHECK (
        (status = 'ACCEPTED'
            AND processed_at IS NULL
            AND closed_reason IS NULL
            AND retention_expires_at IS NULL)
        OR
        (status = 'PROCESSED'
            AND processed_at IS NOT NULL
            AND closed_reason IS NOT NULL
            AND retention_expires_at = processed_at + interval '90 days')
        OR
        (status = 'MANUAL_REVIEW'
            AND processed_at IS NOT NULL
            AND closed_reason IS NOT NULL
            AND retention_expires_at IS NULL)
    )
);

CREATE INDEX idx_payment_provider_notification_terminal_retention
    ON payment_provider_notification_inbox (retention_expires_at, id)
    WHERE retention_expires_at IS NOT NULL;

CREATE TABLE payment_provider_request_snapshot (
    payment_id uuid PRIMARY KEY REFERENCES payment_payment(id),
    payment_method_id uuid NOT NULL REFERENCES payment_method(id),
    provider varchar(40) NOT NULL CHECK (length(trim(provider)) > 0),
    token_reference varchar(200) NOT NULL CHECK (length(trim(token_reference)) > 0),
    provider_customer_reference varchar(200),
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_payment_provider_snapshot_reference CHECK (
        (provider = 'TOSS_PAYMENTS'
            AND provider_customer_reference IS NOT NULL
            AND provider_customer_reference ~ '^bf_[A-Za-z0-9_-]{43}$')
        OR
        (provider <> 'TOSS_PAYMENTS' AND provider_customer_reference IS NULL)
    )
);

INSERT INTO payment_provider_request_snapshot (
    payment_id,
    payment_method_id,
    provider,
    token_reference,
    provider_customer_reference,
    created_at
)
SELECT payment.id,
       method.id,
       method.provider,
       method.token_reference,
       method.provider_customer_reference,
       payment.created_at
  FROM payment_payment payment
  JOIN payment_method method ON method.id = payment.payment_method_id
 WHERE payment.type = 'EXTERNAL';

CREATE OR REPLACE FUNCTION beanflow_reject_payment_provider_snapshot_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION USING
        ERRCODE = '23514',
        MESSAGE = 'payment provider request snapshot is immutable';
END
$$;

CREATE TRIGGER trg_payment_provider_request_snapshot_immutable
BEFORE UPDATE OR DELETE ON payment_provider_request_snapshot
FOR EACH ROW
EXECUTE FUNCTION beanflow_reject_payment_provider_snapshot_mutation();
