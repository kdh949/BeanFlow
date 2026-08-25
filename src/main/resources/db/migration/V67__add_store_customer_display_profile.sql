SET LOCAL lock_timeout = '5s';

CREATE TABLE merchant_store_customer_display_profile (
    store_id uuid PRIMARY KEY
        REFERENCES merchant_store(id) ON DELETE CASCADE,
    address_line varchar(300),
    directions_hint varchar(200),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_store_customer_display_address CHECK (
        address_line IS NULL OR (
            address_line = btrim(address_line)
            AND length(address_line) BETWEEN 1 AND 300
            AND address_line !~ '[[:cntrl:]]'
        )
    ),
    CONSTRAINT ck_store_customer_display_directions CHECK (
        directions_hint IS NULL OR (
            directions_hint = btrim(directions_hint)
            AND length(directions_hint) BETWEEN 1 AND 200
            AND directions_hint !~ '[[:cntrl:]]'
        )
    ),
    CONSTRAINT ck_store_customer_display_timestamps CHECK (created_at <= updated_at)
);

CREATE TABLE merchant_store_operating_hours (
    store_id uuid NOT NULL
        REFERENCES merchant_store_customer_display_profile(store_id) ON DELETE CASCADE,
    day_of_week smallint NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    closed boolean NOT NULL,
    opens_at time without time zone,
    closes_at time without time zone,
    PRIMARY KEY (store_id, day_of_week),
    CONSTRAINT ck_store_operating_hours_tuple CHECK (
        (closed AND opens_at IS NULL AND closes_at IS NULL)
        OR
        (NOT closed AND opens_at IS NOT NULL AND closes_at IS NOT NULL AND opens_at < closes_at)
    )
);

ALTER TABLE merchant_menu
    ADD COLUMN display_category varchar(50),
    ADD COLUMN public_description varchar(500),
    ADD CONSTRAINT ck_merchant_menu_display_category CHECK (
        display_category IS NULL OR (
            display_category = btrim(display_category)
            AND length(display_category) BETWEEN 1 AND 50
            AND display_category !~ '[[:cntrl:]]'
        )
    ),
    ADD CONSTRAINT ck_merchant_menu_public_description CHECK (
        public_description IS NULL OR (
            public_description = btrim(public_description)
            AND length(public_description) BETWEEN 1 AND 500
            AND public_description !~ '[[:cntrl:]]'
        )
    );

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('STORE_CUSTOMER_DISPLAY_UPDATED', 'OPERATIONS_POLICY'),
    ('MENU_DISPLAY_CONTENT_UPDATED', 'OPERATIONS_POLICY');
