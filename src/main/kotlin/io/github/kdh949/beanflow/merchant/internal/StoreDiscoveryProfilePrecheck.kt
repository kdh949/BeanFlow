package io.github.kdh949.beanflow.merchant.internal

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Fail-closed startup gate for the searchable store profile (ADR-020).
 *
 * The migration gate only sees the release it runs in, so startup re-checks the invariant against
 * the live database: PostGIS must be installed, `merchant_store` and
 * `merchant_store_discovery_profile` must cover each other exactly, every name must be non-blank
 * and every location must be a valid, non-empty SRID 4326 point. `POINT EMPTY` is rejected here as
 * well as by the table CHECK, because it passes the column type and `ST_IsValid` yet never matches
 * `ST_DWithin`, leaving the store permanently unsearchable. A violation fails application startup rather
 * than degrading readiness, because a partially searchable store set is not an acceptable product
 * state and no placeholder name or coordinate may be substituted.
 */
@Component
internal class StoreDiscoveryProfilePrecheck(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        requirePostgisExtension()
        val coverage = readCoverage()
        if (coverage.missing != 0L) {
            meterRegistry
                .counter("beanflow.discovery.profile.missing.count")
                .increment(coverage.missing.toDouble())
        }
        if (coverage.missing != 0L || coverage.orphaned != 0L || coverage.invalid != 0L) {
            record(UNRESOLVED)
            throw IllegalStateException(
                "Store discovery profile precheck failed: ${coverage.missing} store(s) without a profile, " +
                    "${coverage.orphaned} orphaned profile(s), ${coverage.invalid} invalid profile(s)",
            )
        }
        record(if (coverage.profiles == 0L) EMPTY else VERIFIED)
    }

    private fun requirePostgisExtension() {
        val installed =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'postgis'",
                Long::class.java,
            ) ?: throw IllegalStateException("Store discovery profile precheck did not return an extension count")
        if (installed == 0L) {
            record(UNRESOLVED)
            throw IllegalStateException("Store discovery requires the PostGIS extension; nearby search cannot start without it")
        }
    }

    private fun readCoverage(): ProfileCoverage =
        jdbcTemplate
            .query(
                """
                SELECT
                    (SELECT count(*)
                       FROM merchant_store store
                       LEFT JOIN merchant_store_discovery_profile profile ON profile.store_id = store.id
                      WHERE profile.store_id IS NULL) AS missing,
                    (SELECT count(*)
                       FROM merchant_store_discovery_profile profile
                       LEFT JOIN merchant_store store ON store.id = profile.store_id
                      WHERE store.id IS NULL) AS orphaned,
                    (SELECT count(*)
                       FROM merchant_store_discovery_profile
                      WHERE name IS NULL
                         OR length(btrim(name)) = 0
                         OR location IS NULL
                         OR ST_SRID(location::geometry) <> 4326
                         OR GeometryType(location::geometry) <> 'POINT'
                         OR ST_IsEmpty(location::geometry)
                         OR NOT ST_IsValid(location::geometry)) AS invalid,
                    (SELECT count(*) FROM merchant_store_discovery_profile) AS profiles
                """.trimIndent(),
                { resultSet, _ ->
                    ProfileCoverage(
                        missing = resultSet.getLong("missing"),
                        orphaned = resultSet.getLong("orphaned"),
                        invalid = resultSet.getLong("invalid"),
                        profiles = resultSet.getLong("profiles"),
                    )
                },
            ).singleOrNull()
            ?: throw IllegalStateException("Store discovery profile precheck did not return a coverage row")

    private fun record(outcome: String) {
        meterRegistry.counter("beanflow.discovery.profile.precheck.count", "outcome", outcome).increment()
    }

    private data class ProfileCoverage(
        val missing: Long,
        val orphaned: Long,
        val invalid: Long,
        val profiles: Long,
    )

    private companion object {
        const val EMPTY = "EMPTY"
        const val VERIFIED = "VERIFIED"
        const val UNRESOLVED = "UNRESOLVED"
    }
}
