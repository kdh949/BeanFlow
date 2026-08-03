ALTER TABLE operations_reprocessing_case
    DROP CONSTRAINT chk_reprocessing_case_type,
    ADD CONSTRAINT chk_reprocessing_case_type CHECK (
        case_type IN (
            'PAYMENT_RECONCILIATION',
            'NOTIFICATION_DELIVERY',
            'EVENT_PUBLICATION',
            'SETTLEMENT_LATE_ITEM',
            'ACCEPTANCE_TIMEOUT_WORK',
            'PAYMENT_CANCELLATION_SETUP'
        )
    );

CREATE INDEX idx_ordering_customer_cancellation_setup_scan
    ON ordering_order (cancelled_at, id)
    WHERE state = 'CANCELLED' AND cancellation_cause = 'CUSTOMER_REQUEST';
