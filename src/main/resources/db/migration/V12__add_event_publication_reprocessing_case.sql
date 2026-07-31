ALTER TABLE operations_reprocessing_case
    DROP CONSTRAINT chk_reprocessing_case_type,
    ADD CONSTRAINT chk_reprocessing_case_type
        CHECK (
            case_type IN (
                'PAYMENT_RECONCILIATION',
                'NOTIFICATION_DELIVERY',
                'EVENT_PUBLICATION'
            )
        );
