CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE merchant_store_discovery_profile (
    store_id uuid PRIMARY KEY REFERENCES merchant_store(id),
    name varchar(200) NOT NULL CHECK (length(trim(name)) > 0),
    location geography(Point,4326) NOT NULL
);

CREATE INDEX idx_store_discovery_profile_location
    ON merchant_store_discovery_profile USING GIST (location);

-- Fail-closed coverage gate. An empty merchant_store passes. A non-empty merchant_store only
-- passes when the same release already inserted an owner-verified public name and location for
-- every store. Placeholder names, (0,0) coordinates, menu names, order history and external
-- geocoders are not acceptable sources, so an unresolved row stops the migration instead of
-- silently excluding the store from search.
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
