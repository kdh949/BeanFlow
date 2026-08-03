ALTER TABLE notification_delivery
    ADD COLUMN logical_source varchar(240);

UPDATE notification_delivery
SET logical_source =
    'event:' || event_id || ':recipient:' || recipient_id || ':channel:' || logical_channel
WHERE logical_source IS NULL;

ALTER TABLE notification_delivery
    ALTER COLUMN logical_source SET NOT NULL,
    ADD CONSTRAINT chk_notification_delivery_logical_source CHECK (
        logical_source = btrim(logical_source)
        AND length(logical_source) BETWEEN 1 AND 240
    );

CREATE UNIQUE INDEX uq_notification_delivery_logical_source
    ON notification_delivery (logical_source);

ALTER TABLE notification_delivery
    DROP CONSTRAINT chk_notification_delivery_template,
    ADD CONSTRAINT chk_notification_delivery_template CHECK (
        template IN (
            'STORE_ACCEPTANCE_WARNING',
            'ORDER_REJECTED',
            'ORDER_READY',
            'ORDER_CANCELLATION_ACCEPTED',
            'CUSTOMER_CANCELLATION_REFUND_SUCCEEDED',
            'CUSTOMER_CANCELLATION_REFUND_DELAYED'
        )
    );
