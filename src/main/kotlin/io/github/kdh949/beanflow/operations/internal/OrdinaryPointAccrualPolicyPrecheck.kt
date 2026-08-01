package io.github.kdh949.beanflow.operations.internal

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
@Profile("!ordinary-point-accrual-policy-bootstrap")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
internal class OrdinaryPointAccrualPolicyPrecheck(
    private val jdbcTemplate: JdbcTemplate,
    private val meterRegistry: MeterRegistry,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        try {
            val completeGlobalCount =
                jdbcTemplate.queryForObject(
                    """
                    SELECT count(*)
                      FROM operations_point_accrual_policy_head head
                      JOIN operations_point_accrual_policy_version policy
                        ON policy.policy_version_id = head.policy_version_id
                       AND policy.scope_type = head.scope_type
                       AND policy.scope_reference = head.scope_reference
                     WHERE head.scope_type = 'GLOBAL'
                       AND head.scope_reference = '00000000-0000-0000-0000-000000000000'::uuid
                       AND policy.state = 'OVERRIDE'
                       AND policy.accrual_rate_bps BETWEEN 0 AND 10000
                       AND policy.rounding_mode IN ('FLOOR', 'HALF_UP')
                       AND policy.issuer_type IN ('PLATFORM', 'BRAND', 'STORE')
                       AND policy.issuer_reference IS NOT NULL
                       AND policy.expiry_rule IN (
                           'EXACT_DURATION_FROM_COMPLETION',
                           'SEOUL_CALENDAR_DAYS_FROM_COMPLETION'
                       )
                       AND policy.validity_days BETWEEN 1 AND 3650
                       AND policy.payload_hash ~ '^[0-9a-f]{64}$'
                    """.trimIndent(),
                    Long::class.java,
                ) ?: throw IllegalStateException("GLOBAL ordinary point accrual policy precheck returned no result")
            if (completeGlobalCount != 1L) {
                metric("INVALID")
                throw IllegalStateException(
                    "GLOBAL ordinary point accrual policy must have exactly one complete current version",
                )
            }
            metric("VALID")
        } catch (failure: IllegalStateException) {
            throw failure
        } catch (failure: DataAccessException) {
            metric("DEPENDENCY_UNAVAILABLE")
            throw IllegalStateException("GLOBAL ordinary point accrual policy could not be verified", failure)
        }
    }

    private fun metric(outcome: String) {
        meterRegistry
            .counter("beanflow.operations.point_accrual_policy.precheck.count", "outcome", outcome)
            .increment()
    }
}
