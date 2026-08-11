package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal data class OrderReferenceBackfillResult(
    val processedCount: Long,
    val batchCount: Long,
)

@Service
internal class OrderReferenceBackfillService(
    private val jdbcTemplate: JdbcTemplate,
    generator: PublicOrderReferenceCandidateGenerator,
    private val meterRegistry: MeterRegistry,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val references = PublicOrderReferenceRegistry(jdbcTemplate, generator, meterRegistry)
    private val transaction = TransactionTemplate(transactionManager)
    private val processed = meterRegistry.counter("beanflow.order.reference_backfill.processed.count")
    private val failed = meterRegistry.counter("beanflow.order.reference_backfill.failed.count")
    private val duration =
        Timer
            .builder("beanflow.order.reference_backfill.duration")
            .publishPercentiles(0.95)
            .register(meterRegistry)

    fun runAll(batchSize: Int): OrderReferenceBackfillResult = requireNotNull(duration.recordCallable { runAllMeasured(batchSize) })

    private fun runAllMeasured(batchSize: Int): OrderReferenceBackfillResult {
        require(batchSize in 1..MAX_BATCH_SIZE) { "batchSize must be between 1 and $MAX_BATCH_SIZE" }
        preflight()

        var cursor: BackfillCursor? = null
        var processedCount = 0L
        var batchCount = 0L
        while (true) {
            val candidates = candidatesAfter(cursor, batchSize)
            if (candidates.isEmpty()) break
            batchCount += 1
            try {
                val batchProcessed =
                    requireNotNull(
                        transaction.execute {
                            candidates.count { backfillOne(it) }.toLong()
                        },
                    )
                processedCount += batchProcessed
                processed.increment(batchProcessed.toDouble())
            } catch (failure: RuntimeException) {
                failed.increment()
                throw failure
            }
            cursor = candidates.last().cursor
        }

        val remaining =
            scalarLong(
                """
                SELECT count(*)
                  FROM ordering_order
                 WHERE public_reference IS NULL
                    OR pickup_business_date IS NULL
                    OR pickup_sequence IS NULL
                    OR store_name_snapshot IS NULL
                    OR pickup_window_start_snapshot IS NULL
                    OR pickup_window_end_snapshot IS NULL
                """.trimIndent(),
            )
        if (remaining != 0L) {
            failed.increment()
            throw dependencyFailure("Order reference backfill ended with $remaining incomplete orders")
        }
        return OrderReferenceBackfillResult(processedCount, batchCount)
    }

    private fun preflight() {
        val partialRows =
            scalarLong(
                """
                SELECT count(*)
                  FROM ordering_order
                 WHERE num_nonnulls(
                           public_reference,
                           pickup_business_date,
                           pickup_sequence,
                           store_name_snapshot,
                           pickup_window_start_snapshot,
                           pickup_window_end_snapshot
                       ) NOT IN (0, 6)
                """.trimIndent(),
            )
        if (partialRows != 0L) {
            throw dependencyFailure("Order reference backfill found $partialRows partially populated orders")
        }

        val missingOwners =
            scalarLong(
                """
                SELECT count(*)
                  FROM ordering_order bean_order
                  LEFT JOIN fulfillment_pickup_slot slot
                    ON slot.id = bean_order.pickup_slot_id
                   AND slot.store_id = bean_order.store_id
                  LEFT JOIN merchant_store_discovery_profile profile
                    ON profile.store_id = bean_order.store_id
                 WHERE bean_order.public_reference IS NULL
                   AND (slot.id IS NULL OR profile.store_id IS NULL OR btrim(profile.name) = '')
                """.trimIndent(),
            )
        if (missingOwners != 0L) {
            throw dependencyFailure(
                "Order reference backfill requires a verified slot and non-empty store profile for $missingOwners orders",
            )
        }
    }

    private fun candidatesAfter(
        cursor: BackfillCursor?,
        batchSize: Int,
    ): List<BackfillCandidate> {
        val cursorPredicate =
            if (cursor == null) {
                ""
            } else {
                "AND (created_at, id) > (?, ?)"
            }
        val arguments =
            if (cursor == null) {
                arrayOf<Any>(batchSize)
            } else {
                arrayOf(Timestamp.from(cursor.createdAt), cursor.orderId, batchSize)
            }
        return jdbcTemplate.query(
            """
            WITH ranked AS (
                SELECT bean_order.id,
                       bean_order.created_at,
                       bean_order.public_reference,
                       bean_order.store_id,
                       profile.name AS store_name,
                       slot.starts_at,
                       slot.ends_at,
                       (slot.starts_at AT TIME ZONE 'Asia/Seoul')::date AS pickup_business_date,
                       row_number() OVER (
                           PARTITION BY bean_order.store_id,
                                        (slot.starts_at AT TIME ZONE 'Asia/Seoul')::date
                           ORDER BY bean_order.created_at, bean_order.id
                       ) AS pickup_sequence
                  FROM ordering_order bean_order
                  JOIN fulfillment_pickup_slot slot
                    ON slot.id = bean_order.pickup_slot_id
                   AND slot.store_id = bean_order.store_id
                  JOIN merchant_store_discovery_profile profile
                    ON profile.store_id = bean_order.store_id
            )
            SELECT id, created_at, store_id, store_name, starts_at, ends_at,
                   pickup_business_date, pickup_sequence
              FROM ranked
             WHERE public_reference IS NULL
               $cursorPredicate
             ORDER BY created_at, id
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                BackfillCandidate(
                    orderId = resultSet.getObject("id", UUID::class.java),
                    cursor =
                        BackfillCursor(
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getObject("id", UUID::class.java),
                        ),
                    storeId = resultSet.getObject("store_id", UUID::class.java),
                    storeName = resultSet.getString("store_name").trim(),
                    pickupWindowStart = resultSet.getTimestamp("starts_at").toInstant(),
                    pickupWindowEnd = resultSet.getTimestamp("ends_at").toInstant(),
                    pickupBusinessDate = resultSet.getObject("pickup_business_date", LocalDate::class.java),
                    pickupSequence = resultSet.getLong("pickup_sequence"),
                )
            },
            *arguments,
        )
    }

    private fun backfillOne(candidate: BackfillCandidate): Boolean {
        val shape =
            requireNotNull(
                jdbcTemplate.queryForObject(
                    """
                    SELECT num_nonnulls(
                               public_reference,
                               pickup_business_date,
                               pickup_sequence,
                               store_name_snapshot,
                               pickup_window_start_snapshot,
                               pickup_window_end_snapshot
                           )
                      FROM ordering_order
                     WHERE id = ?
                     FOR UPDATE
                    """.trimIndent(),
                    Int::class.java,
                    candidate.orderId,
                ),
            )
        if (shape == 6) return false
        if (shape != 0) throw dependencyFailure("Order ${candidate.orderId} has a partial display identity")

        val reference = references.reserve(clock.instant())
        val updated =
            jdbcTemplate.update(
                """
                UPDATE ordering_order
                   SET public_reference = ?,
                       pickup_business_date = ?,
                       pickup_sequence = ?,
                       store_name_snapshot = ?,
                       pickup_window_start_snapshot = ?,
                       pickup_window_end_snapshot = ?
                 WHERE id = ?
                   AND public_reference IS NULL
                """.trimIndent(),
                reference.value,
                candidate.pickupBusinessDate,
                candidate.pickupSequence,
                candidate.storeName,
                Timestamp.from(candidate.pickupWindowStart),
                Timestamp.from(candidate.pickupWindowEnd),
                candidate.orderId,
            )
        if (updated != 1) throw dependencyFailure("Order ${candidate.orderId} changed during reference backfill")
        jdbcTemplate.update(
            """
            INSERT INTO ordering_pickup_counter (store_id, business_date, last_sequence)
            VALUES (?, ?, ?)
            ON CONFLICT (store_id, business_date) DO UPDATE
            SET last_sequence = GREATEST(ordering_pickup_counter.last_sequence, EXCLUDED.last_sequence)
            """.trimIndent(),
            candidate.storeId,
            candidate.pickupBusinessDate,
            candidate.pickupSequence,
        )
        return true
    }

    private fun scalarLong(sql: String): Long = requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java))

    private fun dependencyFailure(message: String) = DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

    private data class BackfillCursor(
        val createdAt: Instant,
        val orderId: UUID,
    )

    private data class BackfillCandidate(
        val orderId: UUID,
        val cursor: BackfillCursor,
        val storeId: UUID,
        val storeName: String,
        val pickupWindowStart: Instant,
        val pickupWindowEnd: Instant,
        val pickupBusinessDate: LocalDate,
        val pickupSequence: Long,
    )

    private companion object {
        const val MAX_BATCH_SIZE = 1_000
    }
}
