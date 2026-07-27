CREATE TABLE fulfillment_pickup_slot (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    capacity bigint NOT NULL CHECK (capacity >= 0),
    reserved_count bigint NOT NULL DEFAULT 0 CHECK (reserved_count >= 0),
    confirmed_count bigint NOT NULL DEFAULT 0 CHECK (confirmed_count >= 0),
    version bigint NOT NULL DEFAULT 0,
    CHECK (ends_at > starts_at),
    CHECK (reserved_count + confirmed_count <= capacity)
);

CREATE TABLE fulfillment_pickup_reservation (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE,
    slot_id uuid NOT NULL REFERENCES fulfillment_pickup_slot(id),
    state varchar(24) NOT NULL CHECK (state IN ('RESERVED', 'CONFIRMED', 'EXPIRED')),
    expires_at timestamptz NOT NULL,
    source_reference varchar(160) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0
);

CREATE INDEX idx_pickup_reservation_due
    ON fulfillment_pickup_reservation (state, expires_at, id);

CREATE TABLE inventory_sellable_stock (
    id uuid PRIMARY KEY,
    store_id uuid NOT NULL,
    available_quantity bigint NOT NULL CHECK (available_quantity >= 0),
    reserved_quantity bigint NOT NULL DEFAULT 0 CHECK (reserved_quantity >= 0),
    confirmed_quantity bigint NOT NULL DEFAULT 0 CHECK (confirmed_quantity >= 0),
    version bigint NOT NULL DEFAULT 0
);

CREATE TABLE inventory_stock_reservation (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    sellable_unit_id uuid NOT NULL REFERENCES inventory_sellable_stock(id),
    quantity bigint NOT NULL CHECK (quantity > 0),
    state varchar(24) NOT NULL CHECK (state IN ('RESERVED', 'CONFIRMED', 'EXPIRED')),
    expires_at timestamptz NOT NULL,
    source_reference varchar(160) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (order_id, sellable_unit_id),
    UNIQUE (source_reference, sellable_unit_id)
);

CREATE INDEX idx_stock_reservation_due
    ON inventory_stock_reservation (state, expires_at, id);
