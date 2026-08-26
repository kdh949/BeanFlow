ALTER TABLE notification_delivery
    ADD COLUMN classification varchar(32);

ALTER TABLE notification_delivery
    DROP CONSTRAINT notification_delivery_state_check,
    DROP CONSTRAINT chk_notification_delivery_state_fields,
    ADD CONSTRAINT chk_notification_delivery_state CHECK (
        state IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'SKIPPED', 'RETRY_SCHEDULED', 'MANUAL_REVIEW')
    ),
    ADD CONSTRAINT chk_notification_delivery_state_fields CHECK (
        (state IN ('PENDING', 'RETRY_SCHEDULED')
            AND next_attempt_at IS NOT NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR (state = 'PROCESSING'
            AND claim_token IS NOT NULL
            AND claim_until IS NOT NULL)
        OR (state = 'SUCCEEDED'
            AND provider_delivery_reference IS NOT NULL
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
        OR (state = 'SKIPPED'
            AND classification = 'MARKETING'
            AND provider_delivery_reference IS NULL
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL
            AND last_failure_code IS NULL)
        OR (state = 'MANUAL_REVIEW'
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claim_until IS NULL)
    );

UPDATE notification_delivery
SET classification = CASE
    WHEN recipient_type = 'CUSTOMER'
        AND template = 'SUPPORT_GOODWILL_COMPENSATION_ISSUED'
        AND NOT (payload_json::jsonb ? 'relatedOrderId')
        THEN 'MARKETING'
    ELSE 'TRANSACTIONAL'
END;

UPDATE notification_delivery
SET order_id = NULL
WHERE classification = 'MARKETING';

ALTER TABLE notification_delivery
    ALTER COLUMN order_id DROP NOT NULL,
    ALTER COLUMN classification SET NOT NULL,
    ADD CONSTRAINT chk_notification_delivery_classification CHECK (
        (recipient_type = 'CUSTOMER' AND (
            (classification = 'TRANSACTIONAL' AND order_id IS NOT NULL)
            OR (classification = 'MARKETING' AND order_id IS NULL)
        ))
        OR (recipient_type IN ('STORE', 'PROFILE_TARGET') AND classification = 'TRANSACTIONAL')
    );

CREATE TABLE notification_inbox_item (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL,
    logical_source varchar(240) NOT NULL,
    order_id uuid,
    classification varchar(32) NOT NULL CHECK (classification IN ('TRANSACTIONAL', 'MARKETING')),
    template varchar(80) NOT NULL CHECK (template IN (
        'ORDER_REJECTED', 'ORDER_READY', 'ORDER_CANCELLATION_ACCEPTED',
        'CUSTOMER_CANCELLATION_REFUND_SUCCEEDED', 'CUSTOMER_CANCELLATION_REFUND_DELAYED',
        'SUPPORT_PICKUP_RESCHEDULED', 'SUPPORT_POST_ACCEPTANCE_RESOLUTION',
        'SUPPORT_GOODWILL_COMPENSATION_ISSUED'
    )),
    title varchar(120) NOT NULL,
    body varchar(500) NOT NULL,
    target_type varchar(32) NOT NULL CHECK (target_type IN ('NONE', 'ORDER')),
    target_reference varchar(12),
    read_at timestamptz,
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    CONSTRAINT uq_notification_inbox_customer_source UNIQUE (customer_id, logical_source),
    CONSTRAINT chk_notification_inbox_logical_source CHECK (
        logical_source = btrim(logical_source)
        AND length(logical_source) BETWEEN 1 AND 240
        AND logical_source !~ '[[:cntrl:]]'
    ),
    CONSTRAINT chk_notification_inbox_copy CHECK (
        title = btrim(title)
        AND length(title) BETWEEN 1 AND 120
        AND title !~ '[[:cntrl:]]'
        AND body = btrim(body)
        AND length(body) BETWEEN 1 AND 500
        AND body !~ '[[:cntrl:]]'
    ),
    CONSTRAINT chk_notification_inbox_classification_order CHECK (
        (classification = 'TRANSACTIONAL' AND order_id IS NOT NULL)
        OR (classification = 'MARKETING' AND order_id IS NULL)
    ),
    CONSTRAINT chk_notification_inbox_target CHECK (
        (target_type = 'NONE' AND target_reference IS NULL)
        OR (target_type = 'ORDER' AND target_reference IS NOT NULL
            AND target_reference ~ '^BF-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}-[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{4}$')
    ),
    CONSTRAINT chk_notification_inbox_times CHECK (
        retention_expires_at = created_at + INTERVAL '90 days'
        AND (read_at IS NULL OR read_at >= created_at)
    )
);

CREATE INDEX ix_notification_inbox_customer_recent
    ON notification_inbox_item (customer_id, created_at DESC, id DESC);

CREATE INDEX ix_notification_inbox_customer_unread
    ON notification_inbox_item (customer_id)
    WHERE read_at IS NULL;

CREATE INDEX ix_notification_inbox_retention
    ON notification_inbox_item (retention_expires_at, id);

CREATE TABLE notification_customer_preference (
    customer_id uuid PRIMARY KEY,
    marketing_opt_in boolean NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL CHECK (version >= 0)
);

ALTER TABLE support_compensation_request
    DROP CONSTRAINT support_compensation_request_state_check,
    DROP CONSTRAINT chk_support_compensation_terminal,
    DROP CONSTRAINT chk_support_compensation_notification,
    ADD CONSTRAINT chk_support_compensation_state CHECK (state IN (
        'AWAITING_APPROVAL', 'READY_FOR_EXECUTION', 'BENEFIT_ISSUED',
        'NOTIFICATION_RETRY', 'NOTIFICATION_ACCEPTED', 'NOTIFICATION_SKIPPED', 'MANUAL_REVIEW'
    )),
    ADD CONSTRAINT chk_support_compensation_terminal CHECK (
        (state IN (
            'BENEFIT_ISSUED', 'NOTIFICATION_RETRY', 'NOTIFICATION_ACCEPTED',
            'NOTIFICATION_SKIPPED', 'MANUAL_REVIEW'
        ) AND terminal_benefit_id IS NOT NULL)
        OR (state IN ('AWAITING_APPROVAL', 'READY_FOR_EXECUTION') AND terminal_benefit_id IS NULL)
    ),
    ADD CONSTRAINT chk_support_compensation_notification CHECK (
        (state = 'NOTIFICATION_ACCEPTED' AND notification_delivery_id IS NOT NULL AND notification_failure_code IS NULL)
        OR (state IN ('NOTIFICATION_RETRY', 'MANUAL_REVIEW') AND notification_delivery_id IS NULL
            AND notification_failure_code IS NOT NULL)
        OR (state IN (
            'AWAITING_APPROVAL', 'READY_FOR_EXECUTION', 'BENEFIT_ISSUED', 'NOTIFICATION_SKIPPED'
        ) AND notification_delivery_id IS NULL AND notification_failure_code IS NULL)
    );

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('SUPPORT_COMPENSATION_NOTIFICATION_SKIPPED', 'OPERATIONS_POLICY');
