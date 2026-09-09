SET LOCAL lock_timeout = '5s';

-- 거래 카탈로그 version은 이미지와 표시 metadata가 공유하는 JPA version과 분리한다.
-- 기존 거래 row는 현재 동작을 보존하도록 ACTIVE/version 0/epoch로 결정적으로 backfill한다.
ALTER TABLE merchant_menu
    ADD COLUMN lifecycle varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (lifecycle IN ('ACTIVE', 'ARCHIVED')),
    ADD COLUMN trade_version bigint NOT NULL DEFAULT 0
        CHECK (trade_version >= 0),
    ADD COLUMN trade_updated_at timestamptz NOT NULL
        DEFAULT TIMESTAMPTZ '1970-01-01 00:00:00+00',
    ADD COLUMN archived_at timestamptz,
    ADD CONSTRAINT ck_merchant_menu_archive_state CHECK (
        (lifecycle = 'ACTIVE' AND archived_at IS NULL)
        OR (lifecycle = 'ARCHIVED' AND archived_at IS NOT NULL)
    );

ALTER TABLE merchant_menu_option
    ADD COLUMN lifecycle varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (lifecycle IN ('ACTIVE', 'ARCHIVED')),
    ADD COLUMN archived_at timestamptz,
    ADD CONSTRAINT ck_merchant_menu_option_archive_state CHECK (
        (lifecycle = 'ACTIVE' AND archived_at IS NULL)
        OR (lifecycle = 'ARCHIVED' AND archived_at IS NOT NULL)
    );

ALTER TABLE merchant_menu_configuration
    ADD COLUMN lifecycle varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (lifecycle IN ('ACTIVE', 'ARCHIVED')),
    ADD COLUMN archived_at timestamptz,
    ADD CONSTRAINT ck_merchant_menu_configuration_archive_state CHECK (
        (lifecycle = 'ACTIVE' AND archived_at IS NULL)
        OR (lifecycle = 'ARCHIVED' AND archived_at IS NOT NULL)
    );

-- 보관된 구성은 복원하지 않지만 같은 Option 집합의 새 구성은 새 ID로 만들 수 있다.
ALTER TABLE merchant_menu_configuration
    DROP CONSTRAINT merchant_menu_configuration_menu_id_normalized_option_key_key;

CREATE UNIQUE INDEX uq_merchant_menu_configuration_active_option_key
    ON merchant_menu_configuration (menu_id, normalized_option_key)
    WHERE lifecycle = 'ACTIVE';

CREATE INDEX ix_merchant_menu_active_store_name_id
    ON merchant_menu (store_id, name, id)
    WHERE lifecycle = 'ACTIVE';

CREATE INDEX ix_merchant_menu_option_active_menu_name_id
    ON merchant_menu_option (menu_id, name, id)
    WHERE lifecycle = 'ACTIVE';

CREATE INDEX ix_merchant_menu_configuration_active_menu_id
    ON merchant_menu_configuration (menu_id, id)
    WHERE lifecycle = 'ACTIVE';

-- create/replace/archive가 최초 terminal response를 90일 보존하는 공용 Menu command 원장이다.
CREATE TABLE merchant_menu_catalog_command (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL,
    operation varchar(40) NOT NULL CHECK (
        operation IN ('CREATE_MENU_V1', 'REPLACE_MENU_TRADE_CONTENT_V1', 'ARCHIVE_MENU_V1')
    ),
    idempotency_key varchar(128) NOT NULL CHECK (
        length(idempotency_key) BETWEEN 8 AND 128
        AND idempotency_key = btrim(idempotency_key)
        AND idempotency_key !~ '[[:cntrl:]]'
    ),
    payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    store_id uuid NOT NULL REFERENCES merchant_store(id) ON DELETE CASCADE,
    menu_id uuid NOT NULL REFERENCES merchant_menu(id),
    response_json text NOT NULL CHECK (length(btrim(response_json)) > 0),
    created_at timestamptz NOT NULL,
    retention_expires_at timestamptz NOT NULL,
    UNIQUE (actor_id, operation, idempotency_key),
    CONSTRAINT ck_menu_catalog_command_retention
        CHECK (retention_expires_at = created_at + interval '90 days')
);

CREATE INDEX ix_menu_catalog_command_retention
    ON merchant_menu_catalog_command (retention_expires_at, id);

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
    ('MENU_CATALOG_CREATED', 'OPERATIONS_POLICY'),
    ('MENU_CATALOG_UPDATED', 'OPERATIONS_POLICY'),
    ('MENU_CATALOG_ARCHIVED', 'OPERATIONS_POLICY');
