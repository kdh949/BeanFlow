CREATE EXTENSION IF NOT EXISTS postgis;

-- Schema only. The fail-closed coverage gate is V34, so an operator with existing stores can
-- migrate to V33, load the owner-verified profiles, and then run V34. Creating the table and
-- asserting full coverage in one migration would leave no moment in which the profiles could be
-- written at all.
CREATE TABLE merchant_store_discovery_profile (
    store_id uuid PRIMARY KEY REFERENCES merchant_store(id),
    name varchar(200) NOT NULL CHECK (length(trim(name)) > 0),
    location geography(Point,4326) NOT NULL
        -- POINT EMPTY satisfies the type, GeometryType() and ST_IsValid(), but never matches
        -- ST_DWithin. Such a store would be silently unsearchable, which is exactly what the
        -- coverage rule forbids.
        CHECK (NOT ST_IsEmpty(location::geometry))
);

CREATE INDEX idx_store_discovery_profile_location
    ON merchant_store_discovery_profile USING GIST (location);
