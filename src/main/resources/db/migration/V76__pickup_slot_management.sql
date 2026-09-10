SET LOCAL lock_timeout = '5s';

INSERT INTO operations_audit_action_category (action, audit_category) VALUES
 ('PICKUP_SLOT_CREATED', 'ORDER_AND_FULFILLMENT'), ('PICKUP_SLOT_REPLACED', 'ORDER_AND_FULFILLMENT');

-- 관리 목록도 V35의 (store_id, starts_at, id) covering index를 사용한다.
CREATE TABLE fulfillment_pickup_slot_command (
 id uuid PRIMARY KEY,
 actor_id uuid NOT NULL,
 operation varchar(16) NOT NULL CHECK (operation IN ('CREATE', 'REPLACE')),
 idempotency_key varchar(128) NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 8 AND 128),
 slot_id uuid NOT NULL REFERENCES fulfillment_pickup_slot(id),
 payload_hash varchar(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
 response_json text NOT NULL,
 created_at timestamptz NOT NULL,
 UNIQUE(actor_id, operation, idempotency_key)
);
CREATE INDEX idx_pickup_slot_command_retention ON fulfillment_pickup_slot_command(created_at, id);

CREATE FUNCTION guard_pickup_slot_management()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.store_id IS DISTINCT FROM NEW.store_id THEN
  RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Pickup slot store is immutable';
 END IF;
 IF (OLD.reserved_count > 0 OR OLD.confirmed_count > 0)
    AND (OLD.starts_at IS DISTINCT FROM NEW.starts_at OR OLD.ends_at IS DISTINCT FROM NEW.ends_at) THEN
  RAISE EXCEPTION USING ERRCODE = '23514', MESSAGE = 'Consumed pickup slot window is immutable';
 END IF;
 RETURN NEW;
END
$$;
CREATE TRIGGER pickup_slot_management_guard BEFORE UPDATE ON fulfillment_pickup_slot
 FOR EACH ROW EXECUTE FUNCTION guard_pickup_slot_management();
