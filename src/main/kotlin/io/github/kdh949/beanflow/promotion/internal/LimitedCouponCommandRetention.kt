package io.github.kdh949.beanflow.promotion.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant

internal data class LimitedCouponCommandRetentionResult(
    val deletedCount: Int,
)

@Repository
internal class LimitedCouponCommandRetentionRepository(
    private val jdbc: JdbcTemplate,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun purgeCampaignCommands(
        now: Instant,
        limit: Int,
    ): LimitedCouponCommandRetentionResult = purgeDue(CommandTable.CAMPAIGN, now, limit)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun purgeClaimCommands(
        now: Instant,
        limit: Int,
    ): LimitedCouponCommandRetentionResult = purgeDue(CommandTable.CLAIM, now, limit)

    private fun purgeDue(
        table: CommandTable,
        now: Instant,
        limit: Int,
    ): LimitedCouponCommandRetentionResult {
        require(limit in 1..100) { "Limited coupon command retention limit must be between 1 and 100" }
        val deletedIds =
            jdbc.query(
                """
                WITH due AS (
                    SELECT id
                      FROM ${table.tableName}
                     WHERE retention_expires_at <= ?
                     ORDER BY retention_expires_at, id
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                DELETE FROM ${table.tableName} command
                 USING due
                 WHERE command.id = due.id
                 RETURNING command.id
                """.trimIndent(),
                { result, _ -> result.getObject("id") },
                Timestamp.from(now),
                limit,
            )
        return LimitedCouponCommandRetentionResult(deletedIds.size)
    }

    private enum class CommandTable(
        val tableName: String,
    ) {
        CAMPAIGN("promotion_limited_campaign_command"),
        CLAIM("promotion_limited_coupon_claim_command"),
    }
}

@Component
internal class LimitedCouponCommandRetentionWorker(
    private val repository: LimitedCouponCommandRetentionRepository,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${beanflow.limited-coupon-retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.limited-coupon-retention.initial-delay-ms:300000}",
    )
    fun runOnce(): Int {
        val now = clock.instant()
        var deletedCount = 0
        var firstFailure: RuntimeException? = null
        listOf(
            "campaign" to { repository.purgeCampaignCommands(now, BATCH_SIZE) },
            "claim" to { repository.purgeClaimCommands(now, BATCH_SIZE) },
        ).forEach { (table, purge) ->
            try {
                val result = purge()
                deletedCount += result.deletedCount
                meterRegistry
                    .counter(METRIC_NAME, "table", table, "outcome", "deleted")
                    .increment(result.deletedCount.toDouble())
            } catch (failure: RuntimeException) {
                meterRegistry.counter(METRIC_NAME, "table", table, "outcome", "failed").increment()
                logger.error("limited_coupon_command_retention table={} outcome=FAILED", table, failure)
                if (firstFailure == null) firstFailure = failure
            }
        }
        firstFailure?.let { throw it }
        return deletedCount
    }

    private companion object {
        const val BATCH_SIZE = 100
        const val METRIC_NAME = "beanflow.promotion.limited_coupon.command_retention"
    }
}
