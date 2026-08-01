package io.github.kdh949.beanflow.loyalty.internal

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
internal class PointLotIssuerPrecheck(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val invalidLotCount =
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM loyalty_point_lot
                WHERE issuer_type IS NULL
                   OR issuer_type NOT IN ('PLATFORM', 'BRAND', 'STORE')
                   OR issuer_reference IS NULL
                   OR length(btrim(issuer_reference)) = 0
                """.trimIndent(),
                Long::class.java,
            ) ?: throw IllegalStateException("PointLot issuer precheck did not return a count")
        if (invalidLotCount != 0L) {
            record(UNRESOLVABLE)
            throw IllegalStateException("PointLot issuer precheck found $invalidLotCount invalid lot(s)")
        }

        val lotCount =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM loyalty_point_lot",
                Long::class.java,
            ) ?: throw IllegalStateException("PointLot issuer precheck did not return a count")
        record(if (lotCount == 0L) EMPTY else VERIFIED)
    }

    private fun record(outcome: String) {
        meterRegistry.counter("beanflow.loyalty.issuer_precheck.count", "outcome", outcome).increment()
    }

    private companion object {
        const val EMPTY = "EMPTY"
        const val VERIFIED = "VERIFIED"
        const val UNRESOLVABLE = "UNRESOLVABLE"
    }
}
