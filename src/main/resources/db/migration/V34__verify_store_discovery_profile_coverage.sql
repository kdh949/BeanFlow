-- Fail-closed coverage gate for the searchable store profile (ADR-020).
--
-- An empty merchant_store passes. A non-empty merchant_store only passes when every store already
-- has an owner-verified public name and location. Placeholder names, (0,0) coordinates, menu names,
-- order history and external geocoders are not acceptable sources, so an unresolved row stops the
-- migration instead of silently excluding the store from search.
--
-- Deployment with existing stores is a two-step migration: run to target V33, load the verified
-- profiles, then run the remaining migrations so this gate sees complete coverage.
DO $$
DECLARE
    unresolved bigint;
BEGIN
    SELECT count(*)
      INTO unresolved
      FROM merchant_store store
      LEFT JOIN merchant_store_discovery_profile profile ON profile.store_id = store.id
     WHERE profile.store_id IS NULL;

    IF unresolved <> 0 THEN
        RAISE EXCEPTION
            'Nearby discovery migration found % merchant_store row(s) without a verified StoreDiscoveryProfile',
            unresolved;
    END IF;
END
$$;
