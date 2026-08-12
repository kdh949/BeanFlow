SET LOCAL lock_timeout = '5s';

ALTER TABLE identity_customer_support_profile
    ADD COLUMN legal_name_ciphertext varchar(16384),
    ADD COLUMN legal_name_key_version integer,
    ADD COLUMN legal_name_aad_version smallint,
    ADD COLUMN masked_legal_name varchar(200),
    ADD CONSTRAINT chk_identity_customer_legal_name_tuple CHECK (
        (legal_name_ciphertext IS NULL AND legal_name_key_version IS NULL
            AND legal_name_aad_version IS NULL AND masked_legal_name IS NULL)
        OR (
            legal_name_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
            AND legal_name_key_version > 0
            AND split_part(legal_name_ciphertext, ':', 2) = 'v' || legal_name_key_version::text
            AND legal_name_aad_version = 1
            AND masked_legal_name = btrim(masked_legal_name)
            AND length(masked_legal_name) BETWEEN 1 AND 200
            AND masked_legal_name LIKE '%*%'
            AND masked_legal_name !~ '[[:cntrl:]]'
        )
    );

ALTER TABLE merchant_store_support_profile
    ADD COLUMN public_display_name_ciphertext varchar(16384),
    ADD COLUMN public_display_name_key_version integer,
    ADD COLUMN public_display_name_aad_version smallint,
    ADD COLUMN masked_public_display_name varchar(200),
    ADD COLUMN public_phone_ciphertext varchar(16384),
    ADD COLUMN public_phone_key_version integer,
    ADD COLUMN public_phone_aad_version smallint,
    ADD COLUMN masked_public_phone varchar(32),
    ADD COLUMN public_description varchar(1000),
    ADD COLUMN pickup_instructions varchar(1000),
    ADD COLUMN legal_representative_ciphertext varchar(16384),
    ADD COLUMN legal_representative_key_version integer,
    ADD COLUMN legal_representative_aad_version smallint,
    ADD COLUMN masked_legal_representative varchar(200),
    ADD COLUMN settlement_account_reference_ciphertext varchar(16384),
    ADD COLUMN settlement_account_reference_key_version integer,
    ADD COLUMN settlement_account_reference_aad_version smallint,
    ADD COLUMN masked_settlement_account_reference varchar(200),
    ADD CONSTRAINT chk_merchant_store_public_display_tuple CHECK (
        (public_display_name_ciphertext IS NULL AND public_display_name_key_version IS NULL
            AND public_display_name_aad_version IS NULL AND masked_public_display_name IS NULL)
        OR (
            public_display_name_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
            AND public_display_name_key_version > 0
            AND split_part(public_display_name_ciphertext, ':', 2) = 'v' || public_display_name_key_version::text
            AND public_display_name_aad_version = 1
            AND masked_public_display_name = btrim(masked_public_display_name)
            AND length(masked_public_display_name) BETWEEN 1 AND 200
            AND masked_public_display_name LIKE '%*%'
            AND masked_public_display_name !~ '[[:cntrl:]]'
        )
    ),
    ADD CONSTRAINT chk_merchant_store_public_phone_tuple CHECK (
        (public_phone_ciphertext IS NULL AND public_phone_key_version IS NULL
            AND public_phone_aad_version IS NULL AND masked_public_phone IS NULL)
        OR (
            public_phone_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
            AND public_phone_key_version > 0
            AND split_part(public_phone_ciphertext, ':', 2) = 'v' || public_phone_key_version::text
            AND public_phone_aad_version = 1
            AND masked_public_phone ~ '^\*\*\*-\*\*\*\*-[0-9]{4}$'
        )
    ),
    ADD CONSTRAINT chk_merchant_store_public_text CHECK (
        (public_description IS NULL OR (
            public_description = btrim(public_description)
            AND length(public_description) BETWEEN 1 AND 1000
            AND public_description !~ '[[:cntrl:]]'
        ))
        AND (pickup_instructions IS NULL OR (
            pickup_instructions = btrim(pickup_instructions)
            AND length(pickup_instructions) BETWEEN 1 AND 1000
            AND pickup_instructions !~ '[[:cntrl:]]'
        ))
    ),
    ADD CONSTRAINT chk_merchant_store_representative_tuple CHECK (
        (legal_representative_ciphertext IS NULL AND legal_representative_key_version IS NULL
            AND legal_representative_aad_version IS NULL AND masked_legal_representative IS NULL)
        OR (
            legal_representative_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
            AND legal_representative_key_version > 0
            AND split_part(legal_representative_ciphertext, ':', 2) = 'v' || legal_representative_key_version::text
            AND legal_representative_aad_version = 1
            AND masked_legal_representative = btrim(masked_legal_representative)
            AND length(masked_legal_representative) BETWEEN 1 AND 200
            AND masked_legal_representative LIKE '%*%'
            AND masked_legal_representative !~ '[[:cntrl:]]'
        )
    ),
    ADD CONSTRAINT chk_merchant_store_settlement_reference_tuple CHECK (
        (settlement_account_reference_ciphertext IS NULL AND settlement_account_reference_key_version IS NULL
            AND settlement_account_reference_aad_version IS NULL AND masked_settlement_account_reference IS NULL)
        OR (
            settlement_account_reference_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
            AND settlement_account_reference_key_version > 0
            AND split_part(settlement_account_reference_ciphertext, ':', 2) =
                'v' || settlement_account_reference_key_version::text
            AND settlement_account_reference_aad_version = 1
            AND masked_settlement_account_reference = btrim(masked_settlement_account_reference)
            AND length(masked_settlement_account_reference) BETWEEN 3 AND 200
            AND masked_settlement_account_reference LIKE '%*%'
            AND masked_settlement_account_reference !~ '[[:cntrl:]]'
        )
    );

ALTER TABLE delivery_external_courier_support_profile
    ADD COLUMN masked_provider_courier_reference varchar(200),
    ADD COLUMN payout_reference_ciphertext varchar(16384),
    ADD COLUMN payout_reference_key_version integer,
    ADD COLUMN payout_reference_aad_version smallint,
    ADD COLUMN masked_payout_reference varchar(200),
    ADD CONSTRAINT chk_delivery_courier_payout_reference_tuple CHECK (
        (payout_reference_ciphertext IS NULL AND payout_reference_key_version IS NULL
            AND payout_reference_aad_version IS NULL AND masked_payout_reference IS NULL)
        OR (
            payout_reference_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
            AND payout_reference_key_version > 0
            AND split_part(payout_reference_ciphertext, ':', 2) = 'v' || payout_reference_key_version::text
            AND payout_reference_aad_version = 1
            AND masked_payout_reference = btrim(masked_payout_reference)
            AND length(masked_payout_reference) BETWEEN 3 AND 200
            AND masked_payout_reference LIKE '%*%'
            AND masked_payout_reference !~ '[[:cntrl:]]'
        )
    ),
    ADD CONSTRAINT chk_delivery_courier_provider_reference_mask CHECK (
        masked_provider_courier_reference IS NULL OR (
            masked_provider_courier_reference LIKE '%*%'
            AND masked_provider_courier_reference !~ '[[:cntrl:]]'
        )
    );

CREATE TABLE support_profile_change (
    id uuid PRIMARY KEY,
    support_case_id uuid NOT NULL REFERENCES support_case(id),
    subject_type varchar(24) NOT NULL CHECK (subject_type IN ('CUSTOMER', 'STORE', 'RIDER')),
    subject_id uuid NOT NULL,
    purpose varchar(48) NOT NULL CHECK (purpose IN (
        'CUSTOMER_DISPLAY_NAME', 'CUSTOMER_LEGAL_NAME_TYPO', 'CUSTOMER_PRIMARY_PHONE',
        'CUSTOMER_CREDENTIAL_RESET', 'STORE_PUBLIC_PROFILE', 'STORE_OPERATIONS_CONTACT',
        'STORE_REPRESENTATIVE', 'STORE_SETTLEMENT_ACCOUNT', 'STORE_ACCESS_REREGISTRATION',
        'COURIER_DISPLAY_NAME', 'COURIER_RELAY_CONTACT', 'COURIER_PROVIDER_IDENTITY',
        'COURIER_PAYOUT_REFERENCE', 'COURIER_PROVIDER_REREGISTRATION'
    )),
    risk_class varchar(2) NOT NULL CHECK (risk_class IN ('R1', 'R2', 'R3', 'R4')),
    requester_actor_id uuid NOT NULL,
    executor_actor_id uuid NOT NULL,
    verification_session_id uuid NOT NULL REFERENCES support_verification_session(id),
    expected_profile_version bigint NOT NULL CHECK (expected_profile_version >= 0),
    current_profile_version bigint CHECK (current_profile_version >= expected_profile_version),
    payload_digest varchar(64) NOT NULL CHECK (payload_digest ~ '^[0-9a-f]{64}$'),
    action_request_id uuid UNIQUE REFERENCES support_action_request(id),
    owner_change_id uuid UNIQUE,
    masked_before varchar(1000),
    masked_after varchar(1000),
    state varchar(32) NOT NULL CHECK (state IN (
        'AWAITING_APPROVAL', 'READY_FOR_EXECUTION', 'EXECUTED'
    )),
    notification_state varchar(24) NOT NULL CHECK (notification_state IN (
        'NOT_REQUESTED', 'PENDING', 'ACCEPTED', 'RETRY_SCHEDULED', 'MANUAL_REVIEW'
    )),
    notification_failure_code varchar(80) CHECK (
        notification_failure_code IS NULL OR notification_failure_code ~ '^[A-Z0-9_]{1,80}$'
    ),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_support_profile_change_action_lineage UNIQUE (action_request_id, id),
    CONSTRAINT chk_support_profile_change_binding CHECK (
        (subject_type = 'CUSTOMER' AND purpose LIKE 'CUSTOMER_%')
        OR (subject_type = 'STORE' AND purpose LIKE 'STORE_%')
        OR (subject_type = 'RIDER' AND purpose LIKE 'COURIER_%')
    ),
    CONSTRAINT chk_support_profile_change_risk CHECK (
        (purpose IN ('CUSTOMER_DISPLAY_NAME', 'STORE_PUBLIC_PROFILE', 'COURIER_DISPLAY_NAME') AND risk_class = 'R1')
        OR (purpose IN ('CUSTOMER_LEGAL_NAME_TYPO', 'STORE_OPERATIONS_CONTACT', 'COURIER_RELAY_CONTACT') AND risk_class = 'R2')
        OR (purpose IN ('CUSTOMER_PRIMARY_PHONE', 'STORE_REPRESENTATIVE', 'STORE_SETTLEMENT_ACCOUNT',
            'COURIER_PROVIDER_IDENTITY', 'COURIER_PAYOUT_REFERENCE') AND risk_class = 'R3')
        OR (purpose IN ('CUSTOMER_CREDENTIAL_RESET', 'STORE_ACCESS_REREGISTRATION',
            'COURIER_PROVIDER_REREGISTRATION') AND risk_class = 'R4')
    ),
    CONSTRAINT chk_support_profile_change_approval CHECK (
        (risk_class IN ('R1', 'R2') AND action_request_id IS NULL AND state = 'EXECUTED')
        OR (risk_class IN ('R3', 'R4') AND action_request_id IS NOT NULL)
    ),
    CONSTRAINT chk_support_profile_change_outcome CHECK (
        (state IN ('AWAITING_APPROVAL', 'READY_FOR_EXECUTION')
            AND owner_change_id IS NULL AND current_profile_version IS NULL
            AND masked_before IS NULL AND masked_after IS NULL
            AND notification_state = 'NOT_REQUESTED' AND notification_failure_code IS NULL)
        OR (state = 'EXECUTED' AND owner_change_id IS NOT NULL AND current_profile_version IS NOT NULL
            AND masked_before IS NOT NULL AND masked_after IS NOT NULL)
    ),
    CONSTRAINT chk_support_profile_change_notification CHECK (
        (notification_state IN ('NOT_REQUESTED', 'PENDING', 'ACCEPTED') AND notification_failure_code IS NULL)
        OR (notification_state IN ('RETRY_SCHEDULED', 'MANUAL_REVIEW') AND notification_failure_code IS NOT NULL)
    ),
    CONSTRAINT chk_support_profile_change_time CHECK (created_at <= updated_at)
);

CREATE INDEX idx_support_profile_change_case
    ON support_profile_change(support_case_id, updated_at DESC, id DESC);
CREATE INDEX idx_support_profile_change_subject
    ON support_profile_change(subject_type, subject_id, updated_at DESC, id DESC);
CREATE INDEX idx_support_profile_change_notification_due
    ON support_profile_change(updated_at, id) WHERE notification_state = 'RETRY_SCHEDULED';

CREATE TABLE support_profile_change_notification (
    id uuid PRIMARY KEY,
    profile_change_id uuid NOT NULL REFERENCES support_profile_change(id),
    owner_target_id uuid NOT NULL,
    target_kind varchar(12) NOT NULL CHECK (target_kind IN ('OLD', 'NEW', 'CURRENT')),
    channel_type varchar(8) NOT NULL CHECK (channel_type IN ('PHONE', 'EMAIL')),
    delivery_id uuid UNIQUE REFERENCES notification_delivery(id),
    state varchar(24) NOT NULL CHECK (state IN ('PENDING', 'ACCEPTED', 'RETRY_SCHEDULED', 'MANUAL_REVIEW')),
    failure_code varchar(80) CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z0-9_]{1,80}$'),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_support_profile_change_notification_target UNIQUE (
        profile_change_id, owner_target_id
    ),
    CONSTRAINT chk_support_profile_change_notification_result CHECK (
        (state = 'ACCEPTED' AND delivery_id IS NOT NULL AND failure_code IS NULL)
        OR (state = 'PENDING' AND delivery_id IS NULL AND failure_code IS NULL)
        OR (state IN ('RETRY_SCHEDULED', 'MANUAL_REVIEW') AND delivery_id IS NULL AND failure_code IS NOT NULL)
    ),
    CONSTRAINT chk_support_profile_change_notification_time CHECK (created_at <= updated_at)
);

CREATE TABLE support_profile_change_idempotency (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(80) NOT NULL CHECK (operation ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    idempotency_key varchar(128) NOT NULL CHECK (
        idempotency_key = btrim(idempotency_key)
        AND length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    profile_change_id uuid NOT NULL REFERENCES support_profile_change(id),
    response_status integer NOT NULL CHECK (response_status IN (200, 201, 409)),
    response_body text NOT NULL CHECK (length(response_body) BETWEEN 1 AND 50000),
    failure_code varchar(64),
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    CONSTRAINT uq_support_profile_change_idempotency_scope UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT chk_support_profile_change_idempotency_retention CHECK (
        retention_expires_at = created_at + INTERVAL '90 days'
    ),
    CONSTRAINT chk_support_profile_change_idempotency_result CHECK (
        (response_status IN (200, 201) AND failure_code IS NULL)
        OR (response_status = 409 AND failure_code IS NOT NULL)
    )
);

CREATE INDEX idx_support_profile_change_idempotency_retention
    ON support_profile_change_idempotency(retention_expires_at, id);

CREATE TABLE identity_customer_profile_change_history (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL REFERENCES identity_customer_support_profile(customer_id),
    support_profile_change_id uuid NOT NULL UNIQUE,
    purpose varchar(40) NOT NULL CHECK (purpose IN (
        'CUSTOMER_DISPLAY_NAME', 'CUSTOMER_LEGAL_NAME_TYPO',
        'CUSTOMER_PRIMARY_PHONE', 'CUSTOMER_CREDENTIAL_RESET'
    )),
    risk_class varchar(2) NOT NULL CHECK (risk_class IN ('R1', 'R2', 'R3', 'R4')),
    previous_version bigint NOT NULL CHECK (previous_version >= 0),
    current_version bigint NOT NULL CHECK (current_version >= previous_version),
    masked_before varchar(1000) NOT NULL CHECK (length(masked_before) BETWEEN 1 AND 1000),
    masked_after varchar(1000) NOT NULL CHECK (length(masked_after) BETWEEN 1 AND 1000),
    changed_at timestamptz NOT NULL,
    CONSTRAINT uq_identity_customer_profile_history_version UNIQUE (customer_id, current_version, purpose),
    CONSTRAINT chk_identity_customer_profile_history_risk CHECK (
        (purpose = 'CUSTOMER_DISPLAY_NAME' AND risk_class = 'R1' AND current_version = previous_version + 1)
        OR (purpose = 'CUSTOMER_LEGAL_NAME_TYPO' AND risk_class = 'R2' AND current_version = previous_version + 1)
        OR (purpose = 'CUSTOMER_PRIMARY_PHONE' AND risk_class = 'R3' AND current_version = previous_version + 1)
        OR (purpose = 'CUSTOMER_CREDENTIAL_RESET' AND risk_class = 'R4' AND current_version = previous_version)
    )
);

CREATE TABLE identity_customer_profile_reset_intent (
    id uuid PRIMARY KEY,
    customer_id uuid NOT NULL REFERENCES identity_customer_support_profile(customer_id),
    profile_change_history_id uuid NOT NULL UNIQUE REFERENCES identity_customer_profile_change_history(id),
    intent_type varchar(32) NOT NULL CHECK (intent_type = 'CREDENTIAL_RESET'),
    state varchar(16) NOT NULL CHECK (state = 'REQUESTED'),
    created_at timestamptz NOT NULL
);

CREATE TABLE identity_customer_profile_notification_target (
    id uuid PRIMARY KEY,
    profile_change_history_id uuid NOT NULL REFERENCES identity_customer_profile_change_history(id),
    target_kind varchar(12) NOT NULL CHECK (target_kind IN ('OLD', 'NEW', 'CURRENT')),
    channel_type varchar(8) NOT NULL CHECK (channel_type IN ('PHONE', 'EMAIL')),
    destination_ciphertext varchar(16384) NOT NULL CHECK (
        destination_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
    ),
    destination_key_version integer NOT NULL CHECK (
        destination_key_version > 0
        AND split_part(destination_ciphertext, ':', 2) = 'v' || destination_key_version::text
    ),
    destination_aad_version smallint NOT NULL CHECK (destination_aad_version = 1),
    masked_destination varchar(320) NOT NULL CHECK (
        masked_destination = btrim(masked_destination)
        AND length(masked_destination) BETWEEN 3 AND 320
        AND masked_destination LIKE '%*%'
        AND masked_destination !~ '[[:cntrl:]]'
    ),
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_identity_customer_profile_notification_target UNIQUE (
        profile_change_history_id, target_kind, channel_type
    )
);

CREATE TABLE merchant_store_profile_change_history (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL REFERENCES merchant_store_support_profile(store_id),
    support_profile_change_id uuid NOT NULL UNIQUE,
    purpose varchar(40) NOT NULL CHECK (purpose IN (
        'STORE_PUBLIC_PROFILE', 'STORE_OPERATIONS_CONTACT', 'STORE_REPRESENTATIVE',
        'STORE_SETTLEMENT_ACCOUNT', 'STORE_ACCESS_REREGISTRATION'
    )),
    risk_class varchar(2) NOT NULL CHECK (risk_class IN ('R1', 'R2', 'R3', 'R4')),
    previous_version bigint NOT NULL CHECK (previous_version >= 0),
    current_version bigint NOT NULL CHECK (current_version >= previous_version),
    masked_before varchar(1000) NOT NULL CHECK (length(masked_before) BETWEEN 1 AND 1000),
    masked_after varchar(1000) NOT NULL CHECK (length(masked_after) BETWEEN 1 AND 1000),
    changed_at timestamptz NOT NULL,
    CONSTRAINT uq_merchant_store_profile_history_version UNIQUE (store_id, current_version, purpose),
    CONSTRAINT chk_merchant_store_profile_history_risk CHECK (
        (purpose = 'STORE_PUBLIC_PROFILE' AND risk_class = 'R1' AND current_version = previous_version + 1)
        OR (purpose = 'STORE_OPERATIONS_CONTACT' AND risk_class = 'R2' AND current_version = previous_version + 1)
        OR (purpose IN ('STORE_REPRESENTATIVE', 'STORE_SETTLEMENT_ACCOUNT')
            AND risk_class = 'R3' AND current_version = previous_version + 1)
        OR (purpose = 'STORE_ACCESS_REREGISTRATION' AND risk_class = 'R4' AND current_version = previous_version)
    )
);

CREATE TABLE merchant_store_profile_reset_intent (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL REFERENCES merchant_store_support_profile(store_id),
    profile_change_history_id uuid NOT NULL UNIQUE REFERENCES merchant_store_profile_change_history(id),
    intent_type varchar(32) NOT NULL CHECK (intent_type = 'ACCESS_REREGISTRATION'),
    state varchar(16) NOT NULL CHECK (state = 'REQUESTED'),
    created_at timestamptz NOT NULL
);

CREATE TABLE merchant_store_profile_notification_target (
    id uuid PRIMARY KEY,
    profile_change_history_id uuid NOT NULL REFERENCES merchant_store_profile_change_history(id),
    target_kind varchar(12) NOT NULL CHECK (target_kind IN ('OLD', 'NEW', 'CURRENT')),
    channel_type varchar(8) NOT NULL CHECK (channel_type IN ('PHONE', 'EMAIL')),
    destination_ciphertext varchar(16384) NOT NULL CHECK (
        destination_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
    ),
    destination_key_version integer NOT NULL CHECK (
        destination_key_version > 0
        AND split_part(destination_ciphertext, ':', 2) = 'v' || destination_key_version::text
    ),
    destination_aad_version smallint NOT NULL CHECK (destination_aad_version = 1),
    masked_destination varchar(320) NOT NULL CHECK (
        masked_destination = btrim(masked_destination)
        AND length(masked_destination) BETWEEN 3 AND 320
        AND masked_destination LIKE '%*%'
        AND masked_destination !~ '[[:cntrl:]]'
    ),
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_merchant_store_profile_notification_target UNIQUE (
        profile_change_history_id, target_kind, channel_type
    )
);

CREATE TABLE delivery_courier_profile_change_history (
    id uuid PRIMARY KEY,
    external_courier_id uuid NOT NULL REFERENCES delivery_external_courier_support_profile(external_courier_id),
    support_profile_change_id uuid NOT NULL UNIQUE,
    purpose varchar(48) NOT NULL CHECK (purpose IN (
        'COURIER_DISPLAY_NAME', 'COURIER_RELAY_CONTACT', 'COURIER_PROVIDER_IDENTITY',
        'COURIER_PAYOUT_REFERENCE', 'COURIER_PROVIDER_REREGISTRATION'
    )),
    risk_class varchar(2) NOT NULL CHECK (risk_class IN ('R1', 'R2', 'R3', 'R4')),
    previous_version bigint NOT NULL CHECK (previous_version >= 0),
    current_version bigint NOT NULL CHECK (current_version >= previous_version),
    masked_before varchar(1000) NOT NULL CHECK (length(masked_before) BETWEEN 1 AND 1000),
    masked_after varchar(1000) NOT NULL CHECK (length(masked_after) BETWEEN 1 AND 1000),
    changed_at timestamptz NOT NULL,
    CONSTRAINT uq_delivery_courier_profile_history_version UNIQUE (external_courier_id, current_version, purpose),
    CONSTRAINT chk_delivery_courier_profile_history_risk CHECK (
        (purpose = 'COURIER_DISPLAY_NAME' AND risk_class = 'R1' AND current_version = previous_version + 1)
        OR (purpose = 'COURIER_RELAY_CONTACT' AND risk_class = 'R2' AND current_version = previous_version + 1)
        OR (purpose IN ('COURIER_PROVIDER_IDENTITY', 'COURIER_PAYOUT_REFERENCE')
            AND risk_class = 'R3' AND current_version = previous_version + 1)
        OR (purpose = 'COURIER_PROVIDER_REREGISTRATION' AND risk_class = 'R4'
            AND current_version = previous_version)
    )
);

CREATE TABLE delivery_courier_profile_reset_intent (
    id uuid PRIMARY KEY,
    external_courier_id uuid NOT NULL REFERENCES delivery_external_courier_support_profile(external_courier_id),
    profile_change_history_id uuid NOT NULL UNIQUE REFERENCES delivery_courier_profile_change_history(id),
    intent_type varchar(32) NOT NULL CHECK (intent_type = 'PROVIDER_REREGISTRATION'),
    state varchar(16) NOT NULL CHECK (state = 'REQUESTED'),
    created_at timestamptz NOT NULL
);

CREATE TABLE delivery_courier_profile_notification_target (
    id uuid PRIMARY KEY,
    profile_change_history_id uuid NOT NULL REFERENCES delivery_courier_profile_change_history(id),
    target_kind varchar(12) NOT NULL CHECK (target_kind IN ('OLD', 'NEW', 'CURRENT')),
    channel_type varchar(8) NOT NULL CHECK (channel_type IN ('PHONE', 'EMAIL')),
    destination_ciphertext varchar(16384) NOT NULL CHECK (
        destination_ciphertext ~ '^vault:v[1-9][0-9]*:[^[:space:]]+$'
    ),
    destination_key_version integer NOT NULL CHECK (
        destination_key_version > 0
        AND split_part(destination_ciphertext, ':', 2) = 'v' || destination_key_version::text
    ),
    destination_aad_version smallint NOT NULL CHECK (destination_aad_version = 1),
    masked_destination varchar(320) NOT NULL CHECK (
        masked_destination = btrim(masked_destination)
        AND length(masked_destination) BETWEEN 3 AND 320
        AND masked_destination LIKE '%*%'
        AND masked_destination !~ '[[:cntrl:]]'
    ),
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_delivery_courier_profile_notification_target UNIQUE (
        profile_change_history_id, target_kind, channel_type
    )
);

CREATE TRIGGER trg_identity_customer_profile_history_append_only
    BEFORE UPDATE OR DELETE ON identity_customer_profile_change_history
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();
CREATE TRIGGER trg_identity_customer_profile_reset_append_only
    BEFORE UPDATE OR DELETE ON identity_customer_profile_reset_intent
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();
CREATE TRIGGER trg_identity_customer_profile_notification_append_only
    BEFORE UPDATE OR DELETE ON identity_customer_profile_notification_target
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();
CREATE TRIGGER trg_merchant_store_profile_history_append_only
    BEFORE UPDATE OR DELETE ON merchant_store_profile_change_history
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();
CREATE TRIGGER trg_merchant_store_profile_reset_append_only
    BEFORE UPDATE OR DELETE ON merchant_store_profile_reset_intent
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();
CREATE TRIGGER trg_merchant_store_profile_notification_append_only
    BEFORE UPDATE OR DELETE ON merchant_store_profile_notification_target
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();
CREATE TRIGGER trg_delivery_courier_profile_history_append_only
    BEFORE UPDATE OR DELETE ON delivery_courier_profile_change_history
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();
CREATE TRIGGER trg_delivery_courier_profile_reset_append_only
    BEFORE UPDATE OR DELETE ON delivery_courier_profile_reset_intent
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();
CREATE TRIGGER trg_delivery_courier_profile_notification_append_only
    BEFORE UPDATE OR DELETE ON delivery_courier_profile_notification_target
    FOR EACH ROW EXECUTE FUNCTION reject_support_case_history_mutation();

ALTER TABLE support_action_request
    DROP CONSTRAINT chk_support_action_request_action,
    DROP CONSTRAINT chk_support_action_request_target_type,
    DROP CONSTRAINT chk_support_action_request_target_binding,
    ADD COLUMN terminal_profile_change_id uuid,
    ADD CONSTRAINT chk_support_action_request_action CHECK (action IN (
        'ORDER_CANCELLATION', 'PICKUP_RESCHEDULE', 'POST_ACCEPTANCE_RESOLUTION',
        'GOODWILL_COMPENSATION', 'PROFILE_CHANGE'
    )),
    ADD CONSTRAINT chk_support_action_request_target_type CHECK (
        target_type IN ('ORDER', 'COMPENSATION_REQUEST', 'PROFILE_CHANGE_REQUEST')
    ),
    ADD CONSTRAINT chk_support_action_request_target_binding CHECK (
        (action = 'GOODWILL_COMPENSATION' AND target_type = 'COMPENSATION_REQUEST')
        OR (action = 'PROFILE_CHANGE' AND target_type = 'PROFILE_CHANGE_REQUEST')
        OR (action NOT IN ('GOODWILL_COMPENSATION', 'PROFILE_CHANGE') AND target_type = 'ORDER')
    ),
    ADD CONSTRAINT fk_support_action_request_terminal_profile_change
        FOREIGN KEY (id, terminal_profile_change_id)
        REFERENCES support_profile_change(action_request_id, id),
    ADD CONSTRAINT uq_support_action_request_terminal_profile_change UNIQUE (terminal_profile_change_id);

ALTER TABLE support_action_revision
    DROP CONSTRAINT chk_support_action_revision_action,
    DROP CONSTRAINT chk_support_action_revision_target_type,
    DROP CONSTRAINT chk_support_action_revision_target_binding,
    ADD CONSTRAINT chk_support_action_revision_action CHECK (action IN (
        'ORDER_CANCELLATION', 'PICKUP_RESCHEDULE', 'POST_ACCEPTANCE_RESOLUTION',
        'GOODWILL_COMPENSATION', 'PROFILE_CHANGE'
    )),
    ADD CONSTRAINT chk_support_action_revision_target_type CHECK (
        target_type IN ('ORDER', 'COMPENSATION_REQUEST', 'PROFILE_CHANGE_REQUEST')
    ),
    ADD CONSTRAINT chk_support_action_revision_target_binding CHECK (
        (action = 'GOODWILL_COMPENSATION' AND target_type = 'COMPENSATION_REQUEST')
        OR (action = 'PROFILE_CHANGE' AND target_type = 'PROFILE_CHANGE_REQUEST')
        OR (action NOT IN ('GOODWILL_COMPENSATION', 'PROFILE_CHANGE') AND target_type = 'ORDER')
    );

ALTER TABLE support_action_request
    DROP CONSTRAINT chk_support_action_request_terminal_result,
    DROP CONSTRAINT chk_support_action_request_terminal_action,
    ADD CONSTRAINT chk_support_action_request_terminal_result CHECK (
        (state = 'RESOLUTION_REQUIRED'
            AND terminal_execution_id IS NOT NULL
            AND terminal_resolution_id IS NULL
            AND terminal_compensation_id IS NULL
            AND terminal_profile_change_id IS NULL)
        OR (state = 'EXECUTED' AND num_nonnulls(
            terminal_execution_id, terminal_resolution_id, terminal_compensation_id, terminal_profile_change_id
        ) = 1)
        OR (state NOT IN ('EXECUTED', 'RESOLUTION_REQUIRED')
            AND terminal_execution_id IS NULL
            AND terminal_resolution_id IS NULL
            AND terminal_compensation_id IS NULL
            AND terminal_profile_change_id IS NULL)
    ),
    ADD CONSTRAINT chk_support_action_request_terminal_action CHECK (
        (terminal_compensation_id IS NULL OR action = 'GOODWILL_COMPENSATION')
        AND (terminal_profile_change_id IS NULL OR action = 'PROFILE_CHANGE')
        AND (action <> 'GOODWILL_COMPENSATION'
            OR terminal_execution_id IS NULL AND terminal_resolution_id IS NULL AND terminal_profile_change_id IS NULL)
        AND (action <> 'PROFILE_CHANGE'
            OR terminal_execution_id IS NULL AND terminal_resolution_id IS NULL AND terminal_compensation_id IS NULL)
    );

ALTER TABLE notification_delivery
    DROP CONSTRAINT notification_delivery_recipient_type_check,
    DROP CONSTRAINT notification_delivery_logical_channel_check,
    DROP CONSTRAINT chk_notification_delivery_template,
    ADD CONSTRAINT chk_notification_delivery_recipient_type CHECK (
        recipient_type IN ('STORE', 'CUSTOMER', 'PROFILE_TARGET')
    ),
    ADD CONSTRAINT chk_notification_delivery_logical_channel CHECK (
        logical_channel IN ('STORE_OPERATIONS', 'CUSTOMER_APP', 'PROFILE_OLD', 'PROFILE_NEW', 'PROFILE_CURRENT')
    ),
    ADD CONSTRAINT chk_notification_delivery_template CHECK (
        template IN (
            'STORE_ACCEPTANCE_WARNING', 'ORDER_REJECTED', 'ORDER_READY',
            'ORDER_CANCELLATION_ACCEPTED', 'CUSTOMER_CANCELLATION_REFUND_SUCCEEDED',
            'CUSTOMER_CANCELLATION_REFUND_DELAYED', 'SUPPORT_PICKUP_RESCHEDULED',
            'SUPPORT_POST_ACCEPTANCE_RESOLUTION', 'SUPPORT_GOODWILL_COMPENSATION_ISSUED',
            'SUPPORT_PROFILE_CHANGED'
        )
    );

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('SUPPORT_PROFILE_CHANGE_REQUESTED', 'OPERATIONS_POLICY'),
    ('SUPPORT_PROFILE_CHANGE_EXECUTED', 'PII_ACCESS'),
    ('SUPPORT_PROFILE_CHANGE_NOTIFICATION_RETRY', 'OPERATIONS_POLICY'),
    ('IDENTITY_CUSTOMER_PROFILE_CHANGED', 'PII_ACCESS'),
    ('MERCHANT_STORE_PROFILE_CHANGED', 'PII_ACCESS'),
    ('DELIVERY_COURIER_PROFILE_CHANGED', 'PII_ACCESS');
