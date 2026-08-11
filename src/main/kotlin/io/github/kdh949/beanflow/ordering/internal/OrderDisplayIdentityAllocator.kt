package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal data class AllocatedOrderDisplayIdentity(
    val publicReference: PublicOrderReference,
    val pickupBusinessDate: LocalDate,
    val pickupSequence: Long,
)

@Component
internal class PickupSequenceAllocator(
    private val jdbcTemplate: JdbcTemplate,
    meterRegistry: MeterRegistry,
) {
    private val allocationTimer =
        Timer
            .builder("beanflow.order.pickup_sequence.allocation.duration")
            .publishPercentiles(0.95)
            .register(meterRegistry)

    @Transactional(propagation = Propagation.MANDATORY)
    fun next(
        storeId: UUID,
        businessDate: LocalDate,
    ): Long =
        requireNotNull(
            allocationTimer.recordCallable {
                requireNotNull(
                    jdbcTemplate.queryForObject(
                        """
                        WITH sequence_baseline AS (
                            SELECT GREATEST(
                                       count(*),
                                       COALESCE(max(bean_order.pickup_sequence), 0)
                                   ) + 1 AS next_sequence
                              FROM ordering_order bean_order
                              JOIN fulfillment_pickup_slot slot
                                ON slot.id = bean_order.pickup_slot_id
                               AND slot.store_id = bean_order.store_id
                             WHERE bean_order.store_id = ?
                               AND (slot.starts_at AT TIME ZONE 'Asia/Seoul')::date = ?
                        )
                        INSERT INTO ordering_pickup_counter (store_id, business_date, last_sequence)
                        SELECT ?, ?, next_sequence FROM sequence_baseline
                        ON CONFLICT (store_id, business_date) DO UPDATE
                        SET last_sequence = GREATEST(
                            ordering_pickup_counter.last_sequence + 1,
                            EXCLUDED.last_sequence
                        )
                        RETURNING last_sequence
                        """.trimIndent(),
                        Long::class.java,
                        storeId,
                        businessDate,
                        storeId,
                        businessDate,
                    ),
                )
            },
        )
}

internal class PublicOrderReferenceRegistry(
    private val jdbcTemplate: JdbcTemplate,
    private val generator: PublicOrderReferenceCandidateGenerator,
    private val meterRegistry: MeterRegistry,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun reserve(allocatedAt: Instant): PublicOrderReference {
        repeat(MAX_ATTEMPTS) {
            val candidate = generator.next()
            val reserved =
                jdbcTemplate.query(
                    """
                    INSERT INTO ordering_public_reference_registry (public_reference, allocated_at)
                    VALUES (?, ?)
                    ON CONFLICT (public_reference) DO NOTHING
                    RETURNING public_reference
                    """.trimIndent(),
                    { resultSet, _ -> resultSet.getString("public_reference") },
                    candidate.value,
                    Timestamp.from(allocatedAt),
                )
            if (reserved.isNotEmpty()) {
                return candidate
            }
            meterRegistry.counter("beanflow.order.public_reference.collision.count").increment()
        }
        meterRegistry.counter("beanflow.order.public_reference.exhausted.count").increment()
        throw DomainFailure(
            FailureCode.ORDER_REFERENCE_EXHAUSTED,
            "Public order reference reservation was exhausted after $MAX_ATTEMPTS attempts",
        )
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}

@Component
internal class OrderDisplayIdentityAllocator(
    jdbcTemplate: JdbcTemplate,
    generator: PublicOrderReferenceGenerator,
    meterRegistry: MeterRegistry,
    private val pickupSequences: PickupSequenceAllocator,
    private val clock: Clock,
) {
    private val references = PublicOrderReferenceRegistry(jdbcTemplate, generator, meterRegistry)

    @Transactional(propagation = Propagation.MANDATORY)
    fun allocate(
        storeId: UUID,
        pickupWindowStart: Instant,
    ): AllocatedOrderDisplayIdentity {
        val businessDate = pickupWindowStart.atZone(BUSINESS_ZONE).toLocalDate()
        val sequence = pickupSequences.next(storeId, businessDate)
        val reference = references.reserve(clock.instant())
        return AllocatedOrderDisplayIdentity(reference, businessDate, sequence)
    }

    private companion object {
        val BUSINESS_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
