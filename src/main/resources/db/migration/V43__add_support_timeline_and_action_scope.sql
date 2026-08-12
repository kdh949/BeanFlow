ALTER TABLE support_verification_session
    DROP CONSTRAINT support_verification_session_action_scope_check,
    ADD CONSTRAINT chk_support_verification_session_action_scope
        CHECK (action_scope IN ('PERSONAL_DATA_REVEAL', 'SUPPORT_ACTION'));

CREATE INDEX idx_payment_refund_order_timeline
    ON payment_refund (order_id, updated_at DESC, id DESC);

CREATE INDEX idx_notification_delivery_order_timeline
    ON notification_delivery (order_id, updated_at DESC, id DESC);
