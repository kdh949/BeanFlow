package io.github.kdh949.beanflow

import org.testcontainers.utility.DockerImageName

/**
 * The single PostgreSQL image every BeanFlow test uses.
 *
 * PostgreSQL 17 with PostGIS 3.5. The Flyway baseline enables the PostGIS extension and the nearby
 * search depends on `geography` and a GiST index, so a plain PostgreSQL image must never be able to
 * make a spatial test pass. The image publishes a `linux/amd64` manifest only; an arm64 workstation
 * runs it under Docker emulation while CI runs it natively.
 */
internal val BEANFLOW_POSTGRES_IMAGE: DockerImageName =
    DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres")
