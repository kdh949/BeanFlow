SET LOCAL lock_timeout = '5s';

ALTER TABLE merchant_menu
    ADD COLUMN image_original_key varchar(512),
    ADD COLUMN image_thumbnail_key varchar(512),
    ADD COLUMN image_sha256 varchar(64),
    ADD COLUMN image_updated_at timestamptz,
    ADD CONSTRAINT ck_merchant_menu_image_pointer CHECK (
        (
            image_original_key IS NULL
            AND image_thumbnail_key IS NULL
            AND image_sha256 IS NULL
            AND image_updated_at IS NULL
        ) OR (
            length(btrim(image_original_key)) BETWEEN 1 AND 512
            AND length(btrim(image_thumbnail_key)) BETWEEN 1 AND 512
            AND image_sha256 ~ '^[0-9a-f]{64}$'
            AND image_updated_at IS NOT NULL
        )
    );

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('MENU_IMAGE_UPDATED', 'OPERATIONS_POLICY'),
    ('MENU_IMAGE_DELETED', 'OPERATIONS_POLICY');
