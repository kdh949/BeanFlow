ALTER TABLE ordering_order
    ADD COLUMN rejection_cause varchar(32),
    ADD COLUMN rejection_actor_type varchar(32),
    ADD COLUMN rejection_event_id uuid,
    ADD COLUMN rejection_terminal_version bigint;

WITH exact_rejection_event AS (
    SELECT c.order_id,
           c.event_id,
           c.terminal_order_version,
           min(p.serialized_event::jsonb ->> 'actorType') AS actor_type,
           min((p.serialized_event::jsonb ->> 'rejectedAt')::timestamptz) AS rejected_at
      FROM operations_order_compensation_case c
      JOIN event_publication p
        ON p.event_type = 'io.github.kdh949.beanflow.eventing.api.OrderRejectedV1'
       AND p.serialized_event::jsonb -> 'envelope' ->> 'eventId' = c.event_id::text
     WHERE c.trigger = 'STORE_REJECTION'
     GROUP BY c.order_id, c.event_id, c.terminal_order_version
    HAVING count(DISTINCT p.serialized_event) = 1
       AND count(DISTINCT p.serialized_event::jsonb ->> 'actorType') = 1
       AND min(p.serialized_event::jsonb ->> 'actorType') IN
           ('STORE_OWNER', 'STORE_STAFF', 'SYSTEM_TIMEOUT')
       AND bool_and(p.serialized_event::jsonb -> 'envelope' ->> 'eventType' = 'OrderRejectedV1')
       AND bool_and((p.serialized_event::jsonb -> 'envelope' ->> 'payloadVersion')::integer = 1)
       AND bool_and((p.serialized_event::jsonb -> 'envelope' ->> 'aggregateVersion')::bigint =
                    c.terminal_order_version)
       AND bool_and(p.serialized_event::jsonb -> 'envelope' ->> 'aggregateId' = c.order_id::text)
       AND bool_and(p.serialized_event::jsonb ->> 'orderId' = c.order_id::text)
       AND bool_and(p.serialized_event::jsonb ->> 'customerId' = c.customer_id::text)
       AND bool_and(p.serialized_event::jsonb ->> 'storeId' = c.store_id::text)
       AND bool_and((p.serialized_event::jsonb -> 'envelope' ->> 'occurredAt')::timestamptz =
                    (p.serialized_event::jsonb ->> 'rejectedAt')::timestamptz)
)
UPDATE ordering_order o
   SET rejection_cause = CASE
           WHEN e.actor_type = 'SYSTEM_TIMEOUT' THEN 'ACCEPTANCE_TIMEOUT'
           ELSE 'STORE_REJECTION'
       END,
       rejection_actor_type = e.actor_type,
       rejection_event_id = e.event_id,
       rejection_terminal_version = e.terminal_order_version
  FROM exact_rejection_event e
  JOIN operations_order_compensation_case c
    ON c.order_id = e.order_id
   AND c.event_id = e.event_id
   AND c.terminal_order_version = e.terminal_order_version
 WHERE o.id = e.order_id
   AND o.state = 'REJECTED'
   AND o.version = e.terminal_order_version
   AND o.rejected_at = e.rejected_at
   AND o.customer_id = c.customer_id
   AND o.store_id = c.store_id
   AND c.source_reference = 'order:' || o.id || ':rejection:' || o.version;

ALTER TABLE ordering_order
    ADD CONSTRAINT chk_order_rejection_evidence_shape
        CHECK (
            (rejection_cause IS NULL
                AND rejection_actor_type IS NULL
                AND rejection_event_id IS NULL
                AND rejection_terminal_version IS NULL)
            OR
            (rejection_cause IS NOT NULL
                AND rejection_actor_type IS NOT NULL
                AND rejection_event_id IS NOT NULL
                AND rejection_terminal_version IS NOT NULL
                AND rejection_terminal_version > 0)
        ),
    ADD CONSTRAINT chk_order_rejection_evidence_state
        CHECK (
            state = 'REJECTED'
            OR
            (rejection_cause IS NULL
                AND rejection_actor_type IS NULL
                AND rejection_event_id IS NULL
                AND rejection_terminal_version IS NULL)
        ),
    ADD CONSTRAINT chk_order_rejection_cause_actor
        CHECK (
            rejection_cause IS NULL
            OR (rejection_cause = 'STORE_REJECTION'
                AND rejection_actor_type IN ('STORE_OWNER', 'STORE_STAFF'))
            OR (rejection_cause = 'ACCEPTANCE_TIMEOUT'
                AND rejection_actor_type = 'SYSTEM_TIMEOUT')
        );

CREATE UNIQUE INDEX uq_order_rejection_event_id
    ON ordering_order (rejection_event_id)
    WHERE rejection_event_id IS NOT NULL;

CREATE FUNCTION reject_order_rejection_evidence_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.rejection_cause IS NOT NULL
       AND (NEW.rejection_cause IS DISTINCT FROM OLD.rejection_cause
            OR NEW.rejection_actor_type IS DISTINCT FROM OLD.rejection_actor_type
            OR NEW.rejection_event_id IS DISTINCT FROM OLD.rejection_event_id
            OR NEW.rejection_terminal_version IS DISTINCT FROM OLD.rejection_terminal_version) THEN
        RAISE EXCEPTION 'Order rejection settlement evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ordering_order_rejection_evidence_immutable
    BEFORE UPDATE OF rejection_cause, rejection_actor_type,
                     rejection_event_id, rejection_terminal_version
    ON ordering_order
    FOR EACH ROW EXECUTE FUNCTION reject_order_rejection_evidence_mutation();
