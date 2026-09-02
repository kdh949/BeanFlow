SET LOCAL lock_timeout = '5s';

ALTER TABLE operations_operator_permission_grant
    DROP CONSTRAINT chk_operator_permission_vocabulary,
    ADD CONSTRAINT chk_operator_permission_vocabulary CHECK (permission IN (
        'EXPIRED_BENEFIT_POLICY_READ', 'EXPIRED_BENEFIT_POLICY_WRITE', 'POINT_ACCOUNT_READ', 'POINT_ADJUSTMENT',
        'POINT_ACCRUAL_POLICY_READ', 'POINT_ACCRUAL_POLICY_WRITE', 'ORDER_COMPENSATION_READ',
        'PAYMENT_CANCELLATION_SETUP_REPAIR', 'CUSTOMER_CANCELLATION_REFUND_RECONCILE',
        'SUPPORT_CASE_READ', 'SUPPORT_CASE_WRITE', 'SUPPORT_CASE_ASSIGN', 'SUPPORT_SUBJECT_SEARCH',
        'SUPPORT_VERIFICATION_MANAGE', 'SUPPORT_PII_REVEAL_REQUEST', 'SUPPORT_PII_REVEAL_APPROVE',
        'SUPPORT_PII_REVEAL_BASIC', 'SUPPORT_PII_REVEAL_SENSITIVE', 'SUPPORT_BREAK_GLASS_REQUEST',
        'SUPPORT_ACTION_REQUEST', 'SUPPORT_ACTION_APPROVE', 'SUPPORT_ACTION_EXECUTE', 'SUPPORT_ORDER_READ',
        'SUPPORT_ORDER_CANCEL', 'SUPPORT_PICKUP_RESCHEDULE', 'SUPPORT_RESOLUTION_REQUEST',
        'SUPPORT_RESOLUTION_APPROVE', 'SUPPORT_RESOLUTION_EXECUTE', 'SUPPORT_COMPENSATION_REQUEST',
        'SUPPORT_COMPENSATION_APPROVE', 'SUPPORT_COMPENSATION_EXECUTE', 'SUPPORT_PROFILE_R1_CHANGE',
        'SUPPORT_PROFILE_R2_CHANGE', 'SUPPORT_PROFILE_R3_REQUEST', 'SUPPORT_PROFILE_R3_APPROVE',
        'SUPPORT_DELIVERY_READ', 'SUPPORT_DELIVERY_INCIDENT_WRITE', 'SUPPORT_DELIVERY_CHANGE',
        'OPERATIONS_SUPPORT_INVESTIGATION', 'OPERATIONS_LEGAL_HOLD_MANAGE', 'OPERATIONS_RETENTION_MANAGE',
        'PRIVACY_AUDIT_READ', 'PRIVACY_BREAK_GLASS_REVIEW', 'MERCHANT_CREDENTIAL_MANAGE',
        'STORE_BRAND_MANAGE', 'STORE_MEDIA_MANAGE', 'PROMOTION_CAMPAIGN_READ', 'PROMOTION_CAMPAIGN_WRITE'
    ));

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('COUPON_CAMPAIGN_DRAFT_CREATED', 'OPERATIONS_POLICY'),
    ('COUPON_CAMPAIGN_DRAFT_UPDATED', 'OPERATIONS_POLICY'),
    ('COUPON_CAMPAIGN_BANNER_UPDATED', 'OPERATIONS_POLICY'),
    ('COUPON_CAMPAIGN_PUBLISHED', 'OPERATIONS_POLICY'),
    ('COUPON_CAMPAIGN_STOPPED', 'OPERATIONS_POLICY');

-- Draft campaigns need complete cost terms before they become active. Existing rows remain valid;
-- this only removes the old coupling that forced every inactive campaign to have null terms.
ALTER TABLE promotion_campaign
    DROP CONSTRAINT promotion_campaign_cost_burden_check,
    ADD CONSTRAINT promotion_campaign_cost_burden_check CHECK (
        (
            cost_bearer IS NULL
            AND platform_share_bps IS NULL
            AND store_share_bps IS NULL
        ) OR (
            cost_bearer IS NOT NULL
            AND platform_share_bps IS NOT NULL
            AND store_share_bps IS NOT NULL
            AND (
                (cost_bearer = 'PLATFORM' AND platform_share_bps = 10000 AND store_share_bps = 0)
                OR (cost_bearer = 'STORE' AND platform_share_bps = 0 AND store_share_bps = 10000)
                OR (
                    cost_bearer = 'SHARED'
                    AND platform_share_bps BETWEEN 1 AND 9999
                    AND store_share_bps BETWEEN 1 AND 9999
                    AND platform_share_bps + store_share_bps = 10000
                )
            )
        )
    );

CREATE TABLE promotion_limited_campaign (
    campaign_id uuid PRIMARY KEY REFERENCES promotion_campaign(id),
    state varchar(16) NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED', 'STOPPED')),
    title varchar(80) NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 80),
    summary varchar(160) NOT NULL CHECK (length(btrim(summary)) BETWEEN 1 AND 160),
    banner_alt_text varchar(200) NOT NULL CHECK (length(btrim(banner_alt_text)) BETWEEN 1 AND 200),
    banner_original_key varchar(512),
    banner_thumbnail_key varchar(512),
    banner_sha256 varchar(64),
    banner_updated_at timestamptz,
    claim_starts_at timestamptz NOT NULL,
    claim_ends_at timestamptz NOT NULL,
    coupon_expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    published_at timestamptz,
    stopped_at timestamptz,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CHECK (claim_starts_at < claim_ends_at AND claim_ends_at <= coupon_expires_at),
    CHECK (
        (
            banner_original_key IS NULL AND banner_thumbnail_key IS NULL
            AND banner_sha256 IS NULL AND banner_updated_at IS NULL
        ) OR (
            length(btrim(banner_original_key)) BETWEEN 1 AND 512
            AND length(btrim(banner_thumbnail_key)) BETWEEN 1 AND 512
            AND banner_sha256 ~ '^[0-9a-f]{64}$'
            AND banner_updated_at IS NOT NULL
        )
    ),
    CHECK (
        (state = 'DRAFT' AND published_at IS NULL AND stopped_at IS NULL)
        OR (state = 'PUBLISHED' AND published_at IS NOT NULL AND stopped_at IS NULL)
        OR (state = 'STOPPED' AND published_at IS NOT NULL AND stopped_at IS NOT NULL)
    )
);

CREATE TABLE promotion_limited_campaign_counter (
    campaign_id uuid PRIMARY KEY REFERENCES promotion_limited_campaign(campaign_id),
    total_quota integer NOT NULL CHECK (total_quota BETWEEN 1 AND 1000000),
    issued_count integer NOT NULL DEFAULT 0 CHECK (issued_count >= 0),
    CHECK (issued_count <= total_quota)
);

CREATE TABLE promotion_limited_coupon_claim (
    campaign_id uuid NOT NULL REFERENCES promotion_limited_campaign(campaign_id),
    customer_id uuid NOT NULL,
    issuance_id uuid NOT NULL UNIQUE,
    claimed_at timestamptz NOT NULL,
    PRIMARY KEY (campaign_id, customer_id),
    CONSTRAINT fk_limited_claim_issuance
        FOREIGN KEY (issuance_id) REFERENCES promotion_coupon_issuance(id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE promotion_limited_campaign_command (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 128),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    campaign_id uuid NOT NULL REFERENCES promotion_limited_campaign(campaign_id),
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    UNIQUE (actor_id, operation, idempotency_key)
);

CREATE TABLE promotion_limited_coupon_claim_command (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 128),
    request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    campaign_id uuid NOT NULL REFERENCES promotion_limited_campaign(campaign_id),
    issuance_id uuid REFERENCES promotion_coupon_issuance(id),
    http_status integer NOT NULL CHECK (http_status BETWEEN 100 AND 599),
    response_body text NOT NULL,
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    UNIQUE (actor_id, operation, idempotency_key)
);

CREATE INDEX ix_limited_campaign_customer_list
    ON promotion_limited_campaign (claim_ends_at, campaign_id)
    WHERE state = 'PUBLISHED';

CREATE INDEX ix_limited_campaign_command_retention
    ON promotion_limited_campaign_command (retention_expires_at, id);

CREATE INDEX ix_limited_claim_command_retention
    ON promotion_limited_coupon_claim_command (retention_expires_at, id);
