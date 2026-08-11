SET LOCAL lock_timeout = '5s';

CREATE TABLE identity_customer_support_profile (
    customer_id uuid PRIMARY KEY,
    display_name_ciphertext varchar(16384) NOT NULL,
    display_name_key_version integer NOT NULL CHECK (display_name_key_version > 0),
    display_name_aad_version smallint NOT NULL DEFAULT 1 CHECK (display_name_aad_version = 1),
    masked_display_name varchar(200) NOT NULL,
    primary_phone_ciphertext varchar(16384),
    primary_phone_key_version integer,
    primary_phone_aad_version smallint,
    masked_primary_phone varchar(32),
    primary_email_ciphertext varchar(16384),
    primary_email_key_version integer,
    primary_email_aad_version smallint,
    masked_primary_email varchar(320),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT chk_identity_customer_display_ciphertext CHECK (
        display_name_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
        AND split_part(display_name_ciphertext, ':', 2) = 'v' || display_name_key_version::text
    ),
    CONSTRAINT chk_identity_customer_masked_display CHECK (
        masked_display_name = btrim(masked_display_name)
        AND length(masked_display_name) BETWEEN 1 AND 200
        AND masked_display_name LIKE '%*%'
        AND masked_display_name !~ '[[:cntrl:]]'
    ),
    CONSTRAINT chk_identity_customer_phone_tuple CHECK (
        (primary_phone_ciphertext IS NULL AND primary_phone_key_version IS NULL
            AND primary_phone_aad_version IS NULL AND masked_primary_phone IS NULL)
        OR (
            primary_phone_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
            AND primary_phone_key_version > 0
            AND split_part(primary_phone_ciphertext, ':', 2) = 'v' || primary_phone_key_version::text
            AND primary_phone_aad_version = 1
            AND masked_primary_phone ~ '^\*\*\*-\*\*\*\*-[0-9]{4}$'
        )
    ),
    CONSTRAINT chk_identity_customer_email_tuple CHECK (
        (primary_email_ciphertext IS NULL AND primary_email_key_version IS NULL
            AND primary_email_aad_version IS NULL AND masked_primary_email IS NULL)
        OR (
            primary_email_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
            AND primary_email_key_version > 0
            AND split_part(primary_email_ciphertext, ':', 2) = 'v' || primary_email_key_version::text
            AND primary_email_aad_version = 1
            AND masked_primary_email = btrim(masked_primary_email)
            AND length(masked_primary_email) BETWEEN 3 AND 320
            AND masked_primary_email LIKE '%*%@%*%'
            AND masked_primary_email !~ '[[:space:][:cntrl:]]'
        )
    ),
    CONSTRAINT chk_identity_customer_searchable_contact CHECK (
        primary_phone_ciphertext IS NOT NULL OR primary_email_ciphertext IS NOT NULL
    ),
    CONSTRAINT chk_identity_customer_profile_time CHECK (created_at <= updated_at)
);

CREATE TABLE identity_customer_support_profile_exact_index (
    customer_id uuid NOT NULL REFERENCES identity_customer_support_profile(customer_id) ON DELETE CASCADE,
    criterion_type varchar(16) NOT NULL CHECK (criterion_type IN ('PHONE', 'EMAIL')),
    index_key_version integer NOT NULL CHECK (index_key_version > 0),
    blind_index bytea NOT NULL CHECK (octet_length(blind_index) = 32),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (customer_id, criterion_type, index_key_version)
);

CREATE INDEX idx_identity_customer_support_profile_exact_lookup
    ON identity_customer_support_profile_exact_index
        (criterion_type, index_key_version, blind_index, customer_id);

CREATE TABLE merchant_store_support_profile (
    store_id uuid PRIMARY KEY REFERENCES merchant_store(id),
    legal_display_name_ciphertext varchar(16384) NOT NULL,
    legal_display_name_key_version integer NOT NULL CHECK (legal_display_name_key_version > 0),
    legal_display_name_aad_version smallint NOT NULL DEFAULT 1 CHECK (legal_display_name_aad_version = 1),
    masked_display_name varchar(200) NOT NULL,
    support_phone_ciphertext varchar(16384),
    support_phone_key_version integer,
    support_phone_aad_version smallint,
    masked_support_phone varchar(32),
    support_email_ciphertext varchar(16384),
    support_email_key_version integer,
    support_email_aad_version smallint,
    masked_support_email varchar(320),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT chk_merchant_store_display_ciphertext CHECK (
        legal_display_name_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
        AND split_part(legal_display_name_ciphertext, ':', 2) = 'v' || legal_display_name_key_version::text
    ),
    CONSTRAINT chk_merchant_store_masked_display CHECK (
        masked_display_name = btrim(masked_display_name)
        AND length(masked_display_name) BETWEEN 1 AND 200
        AND masked_display_name LIKE '%*%'
        AND masked_display_name !~ '[[:cntrl:]]'
    ),
    CONSTRAINT chk_merchant_store_phone_tuple CHECK (
        (support_phone_ciphertext IS NULL AND support_phone_key_version IS NULL
            AND support_phone_aad_version IS NULL AND masked_support_phone IS NULL)
        OR (
            support_phone_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
            AND support_phone_key_version > 0
            AND split_part(support_phone_ciphertext, ':', 2) = 'v' || support_phone_key_version::text
            AND support_phone_aad_version = 1
            AND masked_support_phone ~ '^\*\*\*-\*\*\*\*-[0-9]{4}$'
        )
    ),
    CONSTRAINT chk_merchant_store_email_tuple CHECK (
        (support_email_ciphertext IS NULL AND support_email_key_version IS NULL
            AND support_email_aad_version IS NULL AND masked_support_email IS NULL)
        OR (
            support_email_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
            AND support_email_key_version > 0
            AND split_part(support_email_ciphertext, ':', 2) = 'v' || support_email_key_version::text
            AND support_email_aad_version = 1
            AND masked_support_email = btrim(masked_support_email)
            AND length(masked_support_email) BETWEEN 3 AND 320
            AND masked_support_email LIKE '%*%@%*%'
            AND masked_support_email !~ '[[:space:][:cntrl:]]'
        )
    ),
    CONSTRAINT chk_merchant_store_searchable_contact CHECK (
        support_phone_ciphertext IS NOT NULL OR support_email_ciphertext IS NOT NULL
    ),
    CONSTRAINT chk_merchant_store_profile_time CHECK (created_at <= updated_at)
);

CREATE TABLE merchant_store_support_profile_exact_index (
    store_id uuid NOT NULL REFERENCES merchant_store_support_profile(store_id) ON DELETE CASCADE,
    criterion_type varchar(16) NOT NULL CHECK (criterion_type IN ('PHONE', 'EMAIL')),
    index_key_version integer NOT NULL CHECK (index_key_version > 0),
    blind_index bytea NOT NULL CHECK (octet_length(blind_index) = 32),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (store_id, criterion_type, index_key_version)
);

CREATE INDEX idx_merchant_store_support_profile_exact_lookup
    ON merchant_store_support_profile_exact_index
        (criterion_type, index_key_version, blind_index, store_id);

CREATE TABLE delivery_external_courier_support_profile (
    external_courier_id uuid PRIMARY KEY,
    provider_code varchar(64) NOT NULL CHECK (
        provider_code = btrim(provider_code)
        AND provider_code ~ '^[A-Z][A-Z0-9_-]{1,63}$'
    ),
    provider_courier_reference_ciphertext varchar(16384) NOT NULL,
    provider_courier_reference_key_version integer NOT NULL CHECK (provider_courier_reference_key_version > 0),
    provider_courier_reference_aad_version smallint NOT NULL DEFAULT 1
        CHECK (provider_courier_reference_aad_version = 1),
    display_name_ciphertext varchar(16384) NOT NULL,
    display_name_key_version integer NOT NULL CHECK (display_name_key_version > 0),
    display_name_aad_version smallint NOT NULL DEFAULT 1 CHECK (display_name_aad_version = 1),
    masked_display_name varchar(200) NOT NULL,
    relay_phone_ciphertext varchar(16384),
    relay_phone_key_version integer,
    relay_phone_aad_version smallint,
    masked_relay_phone varchar(32),
    relay_email_ciphertext varchar(16384),
    relay_email_key_version integer,
    relay_email_aad_version smallint,
    masked_relay_email varchar(320),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT chk_delivery_courier_reference_ciphertext CHECK (
        provider_courier_reference_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
        AND split_part(provider_courier_reference_ciphertext, ':', 2) =
            'v' || provider_courier_reference_key_version::text
    ),
    CONSTRAINT chk_delivery_courier_display_ciphertext CHECK (
        display_name_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
        AND split_part(display_name_ciphertext, ':', 2) = 'v' || display_name_key_version::text
    ),
    CONSTRAINT chk_delivery_courier_masked_display CHECK (
        masked_display_name = btrim(masked_display_name)
        AND length(masked_display_name) BETWEEN 1 AND 200
        AND masked_display_name LIKE '%*%'
        AND masked_display_name !~ '[[:cntrl:]]'
    ),
    CONSTRAINT chk_delivery_courier_phone_tuple CHECK (
        (relay_phone_ciphertext IS NULL AND relay_phone_key_version IS NULL
            AND relay_phone_aad_version IS NULL AND masked_relay_phone IS NULL)
        OR (
            relay_phone_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
            AND relay_phone_key_version > 0
            AND split_part(relay_phone_ciphertext, ':', 2) = 'v' || relay_phone_key_version::text
            AND relay_phone_aad_version = 1
            AND masked_relay_phone ~ '^\*\*\*-\*\*\*\*-[0-9]{4}$'
        )
    ),
    CONSTRAINT chk_delivery_courier_email_tuple CHECK (
        (relay_email_ciphertext IS NULL AND relay_email_key_version IS NULL
            AND relay_email_aad_version IS NULL AND masked_relay_email IS NULL)
        OR (
            relay_email_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
            AND relay_email_key_version > 0
            AND split_part(relay_email_ciphertext, ':', 2) = 'v' || relay_email_key_version::text
            AND relay_email_aad_version = 1
            AND masked_relay_email = btrim(masked_relay_email)
            AND length(masked_relay_email) BETWEEN 3 AND 320
            AND masked_relay_email LIKE '%*%@%*%'
            AND masked_relay_email !~ '[[:space:][:cntrl:]]'
        )
    ),
    CONSTRAINT chk_delivery_courier_searchable_contact CHECK (
        relay_phone_ciphertext IS NOT NULL OR relay_email_ciphertext IS NOT NULL
    ),
    CONSTRAINT chk_delivery_courier_profile_time CHECK (created_at <= updated_at)
);

CREATE TABLE delivery_external_courier_support_profile_exact_index (
    external_courier_id uuid NOT NULL
        REFERENCES delivery_external_courier_support_profile(external_courier_id) ON DELETE CASCADE,
    criterion_type varchar(16) NOT NULL CHECK (criterion_type IN ('PHONE', 'EMAIL')),
    index_key_version integer NOT NULL CHECK (index_key_version > 0),
    blind_index bytea NOT NULL CHECK (octet_length(blind_index) = 32),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (external_courier_id, criterion_type, index_key_version)
);

CREATE INDEX idx_delivery_courier_support_profile_exact_lookup
    ON delivery_external_courier_support_profile_exact_index
        (criterion_type, index_key_version, blind_index, external_courier_id);

CREATE TABLE support_subject_search_rate_window (
    actor_id uuid NOT NULL,
    window_started_at timestamptz NOT NULL,
    attempt_count smallint NOT NULL CHECK (attempt_count BETWEEN 1 AND 30),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (actor_id, window_started_at),
    CONSTRAINT chk_support_search_rate_window_boundary CHECK (
        window_started_at = to_timestamp(floor(extract(epoch FROM window_started_at) / 300) * 300)
    ),
    CONSTRAINT chk_support_search_rate_window_update CHECK (
        updated_at >= window_started_at AND updated_at < window_started_at + INTERVAL '5 minutes'
    )
);

CREATE INDEX idx_support_subject_search_rate_window_cleanup
    ON support_subject_search_rate_window (window_started_at, actor_id);
