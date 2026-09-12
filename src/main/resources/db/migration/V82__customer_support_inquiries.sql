CREATE TABLE support_customer_inquiry (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL,
    title varchar(100) NOT NULL CHECK (length(trim(title)) BETWEEN 1 AND 100),
    category varchar(32) NOT NULL CHECK (category IN ('ORDER_STATUS', 'PICKUP_RESCHEDULE', 'ORDER_CANCELLATION', 'PAYMENT_OR_REFUND', 'COUPON_OR_POINT', 'CUSTOMER_PROFILE', 'DELIVERY_STATUS', 'ACCOUNT_RECOVERY', 'PRIVACY', 'SAFETY', 'OTHER')),
    order_id uuid,
    order_reference varchar(100),
    support_case_id uuid UNIQUE REFERENCES support_case(id),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL CHECK (updated_at >= created_at),
    retention_policy_version_id bigint NOT NULL,
    retention_policy_category varchar(48) NOT NULL DEFAULT 'SUPPORT_CASE' CHECK (retention_policy_category = 'SUPPORT_CASE'),
    CHECK ((order_id IS NULL) = (order_reference IS NULL)),
    FOREIGN KEY (retention_policy_version_id, retention_policy_category)
        REFERENCES operations_retention_policy_version(policy_version_id, category)
);
CREATE INDEX idx_support_inquiry_customer ON support_customer_inquiry(customer_id, created_at DESC, id DESC);
CREATE INDEX idx_support_inquiry_received ON support_customer_inquiry(created_at DESC, id DESC) WHERE support_case_id IS NULL;

CREATE TABLE support_customer_inquiry_message (
    id uuid PRIMARY KEY,
    inquiry_id uuid NOT NULL REFERENCES support_customer_inquiry(id) ON DELETE CASCADE,
    author_type varchar(16) NOT NULL CHECK (author_type IN ('CUSTOMER', 'SUPPORT')),
    actor_id uuid NOT NULL,
    content varchar(2000) NOT NULL CHECK (length(trim(content)) BETWEEN 1 AND 2000),
    created_at timestamptz NOT NULL
);
CREATE INDEX idx_support_inquiry_message_page ON support_customer_inquiry_message(inquiry_id, created_at DESC, id DESC);

-- Messages can be removed by parent retention, but never rewritten into a different public statement.
CREATE TRIGGER trg_support_inquiry_message_immutable
    BEFORE UPDATE ON support_customer_inquiry_message
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

CREATE FUNCTION protect_customer_inquiry_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW.customer_id, NEW.order_id, NEW.order_reference, NEW.title, NEW.category, NEW.created_at,
        NEW.retention_policy_version_id, NEW.retention_policy_category)
       IS DISTINCT FROM
       (OLD.id, OLD.customer_id, OLD.order_id, OLD.order_reference, OLD.title, OLD.category, OLD.created_at,
        OLD.retention_policy_version_id, OLD.retention_policy_category)
       OR (OLD.support_case_id IS NOT NULL AND NEW.support_case_id IS DISTINCT FROM OLD.support_case_id) THEN
        RAISE EXCEPTION 'Customer inquiry identity is immutable' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_customer_inquiry_identity BEFORE UPDATE ON support_customer_inquiry
    FOR EACH ROW EXECUTE FUNCTION protect_customer_inquiry_identity();

CREATE TABLE support_customer_inquiry_command (
    actor_id uuid NOT NULL,
    operation varchar(64) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    payload_hash char(64) NOT NULL,
    inquiry_id uuid NOT NULL REFERENCES support_customer_inquiry(id) ON DELETE CASCADE,
    message_id uuid REFERENCES support_customer_inquiry_message(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    PRIMARY KEY(actor_id, operation, idempotency_key)
);
CREATE INDEX idx_support_inquiry_command_expiry ON support_customer_inquiry_command(expires_at);

INSERT INTO operations_audit_action_category(action, audit_category) VALUES
    ('CUSTOMER_INQUIRY_CREATE', 'OPERATIONS_POLICY'),
    ('CUSTOMER_INQUIRY_MESSAGE', 'OPERATIONS_POLICY'),
    ('SUPPORT_INQUIRY_CLAIM', 'OPERATIONS_POLICY'),
    ('SUPPORT_INQUIRY_MESSAGE', 'OPERATIONS_POLICY');
