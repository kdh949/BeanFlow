package io.github.kdh949.beanflow.settlement.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal enum class SettlementBatchState {
    OPEN,
    CALCULATED,
    CONFIRMED,
}

internal data class SettlementBatchCalculation(
    val itemCount: Int,
    val grossPaidKrw: Long,
    val feeKrw: Long,
    val benefitCostKrw: Long,
    val itemNetSettlementKrw: Long,
    val adjustmentKrw: Long,
    val carryForwardInKrw: Long,
    val carryForwardSourceBatchId: UUID?,
    val adjustmentCursorEffectiveAt: Instant?,
    val adjustmentCursorId: UUID?,
) {
    val netSettlementKrw: Long =
        Math.addExact(
            Math.addExact(itemNetSettlementKrw, adjustmentKrw),
            carryForwardInKrw,
        )
    val carryForwardOutKrw: Long = minOf(netSettlementKrw, 0)

    init {
        require(itemCount >= 0) { "SettlementBatch item count must be non-negative" }
        require(grossPaidKrw >= 0 && feeKrw >= 0 && benefitCostKrw >= 0 && itemNetSettlementKrw >= 0) {
            "SettlementBatch item summary must be non-negative"
        }
        require(
            itemNetSettlementKrw ==
                Math.subtractExact(
                    Math.subtractExact(grossPaidKrw, feeKrw),
                    benefitCostKrw,
                ),
        ) { "SettlementBatch item summary does not tie out" }
        require(carryForwardInKrw <= 0) { "SettlementBatch carry-forward input must not be positive" }
        require(
            (carryForwardSourceBatchId == null && carryForwardInKrw == 0L) ||
                (carryForwardSourceBatchId != null && carryForwardInKrw < 0L),
        ) { "SettlementBatch carry-forward source does not match its amount" }
        require((adjustmentCursorEffectiveAt == null) == (adjustmentCursorId == null)) {
            "SettlementBatch adjustment cursor must be complete"
        }
    }
}

@Entity
@Table(name = "settlement_batch")
internal class SettlementBatchEntity(
    @Id
    val id: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Column(name = "settlement_date", nullable = false)
    val settlementDate: LocalDate,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var state: SettlementBatchState = SettlementBatchState.OPEN,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "item_count")
    var itemCount: Int? = null,
    @Column(name = "gross_paid_krw")
    var grossPaidKrw: Long? = null,
    @Column(name = "fee_krw")
    var feeKrw: Long? = null,
    @Column(name = "benefit_cost_krw")
    var benefitCostKrw: Long? = null,
    @Column(name = "item_net_settlement_krw")
    var itemNetSettlementKrw: Long? = null,
    @Column(name = "adjustment_krw")
    var adjustmentKrw: Long? = null,
    @Column(name = "carry_forward_in_krw")
    var carryForwardInKrw: Long? = null,
    @Column(name = "carry_forward_out_krw")
    var carryForwardOutKrw: Long? = null,
    @Column(name = "carry_forward_source_batch_id")
    var carryForwardSourceBatchId: UUID? = null,
    @Column(name = "adjustment_cursor_effective_at")
    var adjustmentCursorEffectiveAt: Instant? = null,
    @Column(name = "adjustment_cursor_id")
    var adjustmentCursorId: UUID? = null,
    @Column(name = "calculated_at")
    var calculatedAt: Instant? = null,
    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null,
    @Version
    var version: Long = 0,
) {
    init {
        validateState()
    }

    fun requireAcceptingItems(
        itemStoreId: UUID,
        itemSettlementDate: LocalDate,
    ) {
        check(state == SettlementBatchState.OPEN) {
            "SettlementBatch is not open for new items"
        }
        require(itemStoreId == storeId) {
            "SettlementItem store does not match its batch"
        }
        require(itemSettlementDate == settlementDate) {
            "SettlementItem date does not match its batch"
        }
    }

    fun calculate(
        summary: SettlementBatchCalculation,
        calculatedAt: Instant,
    ) {
        check(state == SettlementBatchState.OPEN) { "SettlementBatch is not open for calculation" }
        require(summary.carryForwardSourceBatchId != id) { "SettlementBatch cannot carry from itself" }
        state = SettlementBatchState.CALCULATED
        itemCount = summary.itemCount
        grossPaidKrw = summary.grossPaidKrw
        feeKrw = summary.feeKrw
        benefitCostKrw = summary.benefitCostKrw
        itemNetSettlementKrw = summary.itemNetSettlementKrw
        adjustmentKrw = summary.adjustmentKrw
        carryForwardInKrw = summary.carryForwardInKrw
        carryForwardOutKrw = summary.carryForwardOutKrw
        carryForwardSourceBatchId = summary.carryForwardSourceBatchId
        adjustmentCursorEffectiveAt = summary.adjustmentCursorEffectiveAt
        adjustmentCursorId = summary.adjustmentCursorId
        this.calculatedAt = calculatedAt
        validateState()
    }

    fun confirm(confirmedAt: Instant) {
        check(state == SettlementBatchState.CALCULATED) { "SettlementBatch is not calculated" }
        val calculated = requireNotNull(calculatedAt)
        require(!confirmedAt.isBefore(calculated)) { "SettlementBatch confirmation cannot precede calculation" }
        state = SettlementBatchState.CONFIRMED
        this.confirmedAt = confirmedAt
        validateState()
    }

    fun netSettlementKrw(): Long? {
        val itemNet = itemNetSettlementKrw ?: return null
        return Math.addExact(Math.addExact(itemNet, requireNotNull(adjustmentKrw)), requireNotNull(carryForwardInKrw))
    }

    private fun validateState() {
        when (state) {
            SettlementBatchState.OPEN -> {
                require(
                    listOf(
                        itemCount,
                        grossPaidKrw,
                        feeKrw,
                        benefitCostKrw,
                        itemNetSettlementKrw,
                        adjustmentKrw,
                        carryForwardInKrw,
                        carryForwardOutKrw,
                        carryForwardSourceBatchId,
                        adjustmentCursorEffectiveAt,
                        adjustmentCursorId,
                        calculatedAt,
                        confirmedAt,
                    ).all { it == null },
                ) { "OPEN SettlementBatch cannot contain a calculated summary" }
            }

            SettlementBatchState.CALCULATED,
            SettlementBatchState.CONFIRMED,
            -> {
                val summary =
                    SettlementBatchCalculation(
                        itemCount = requireNotNull(itemCount),
                        grossPaidKrw = requireNotNull(grossPaidKrw),
                        feeKrw = requireNotNull(feeKrw),
                        benefitCostKrw = requireNotNull(benefitCostKrw),
                        itemNetSettlementKrw = requireNotNull(itemNetSettlementKrw),
                        adjustmentKrw = requireNotNull(adjustmentKrw),
                        carryForwardInKrw = requireNotNull(carryForwardInKrw),
                        carryForwardSourceBatchId = carryForwardSourceBatchId,
                        adjustmentCursorEffectiveAt = adjustmentCursorEffectiveAt,
                        adjustmentCursorId = adjustmentCursorId,
                    )
                require(carryForwardOutKrw == summary.carryForwardOutKrw) {
                    "SettlementBatch carry-forward output does not tie out"
                }
                val calculated = requireNotNull(calculatedAt)
                if (state == SettlementBatchState.CALCULATED) {
                    require(confirmedAt == null) { "CALCULATED SettlementBatch cannot be confirmed" }
                } else {
                    val confirmed = requireNotNull(confirmedAt)
                    require(!confirmed.isBefore(calculated)) {
                        "SettlementBatch confirmation cannot precede calculation"
                    }
                }
            }
        }
    }
}

@Entity
@Immutable
@Table(name = "settlement_item")
internal class SettlementItemEntity(
    @Id
    val id: UUID,
    @Column(name = "settlement_batch_id", nullable = false)
    val settlementBatchId: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Column(name = "item_source", nullable = false, length = 240)
    val itemSource: String,
    @Column(name = "completed_at", nullable = false)
    val completedAt: Instant,
    @Column(name = "settlement_date", nullable = false)
    val settlementDate: LocalDate,
    @Column(nullable = false, length = 3)
    val currency: String,
    @Column(name = "gross_paid_krw", nullable = false)
    val grossPaidKrw: Long,
    @Column(name = "fee_rate_bps", nullable = false)
    val feeRateBps: Int,
    @Column(name = "fee_krw", nullable = false)
    val feeKrw: Long,
    @Column(name = "coupon_cost_krw", nullable = false)
    val couponCostKrw: Long,
    @Column(name = "point_cost_krw", nullable = false)
    val pointCostKrw: Long,
    @Column(name = "benefit_cost_krw", nullable = false)
    val benefitCostKrw: Long,
    @Column(name = "net_settlement_krw", nullable = false)
    val netSettlementKrw: Long,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    init {
        require(itemSource.isNotBlank() && itemSource == itemSource.trim() && itemSource.length <= 240) {
            "SettlementItem source must be present and at most 240 characters"
        }
        require(completedAt.atZone(SEOUL_ZONE_ID).toLocalDate() == settlementDate) {
            "SettlementItem date must be the Seoul date of completion"
        }
        require(currency == "KRW") {
            "SettlementItem currency must be KRW"
        }
        require(grossPaidKrw >= 0 && feeKrw >= 0 && couponCostKrw >= 0 && pointCostKrw >= 0) {
            "SettlementItem monetary inputs must be non-negative"
        }
        require(feeRateBps in 0..10_000) {
            "SettlementItem fee rate must be between 0 and 10000 basis points"
        }
        require(benefitCostKrw == Math.addExact(couponCostKrw, pointCostKrw)) {
            "SettlementItem benefit cost does not tie out"
        }
        require(
            netSettlementKrw ==
                Math.subtractExact(
                    Math.subtractExact(grossPaidKrw, feeKrw),
                    benefitCostKrw,
                ) && netSettlementKrw >= 0,
        ) {
            "SettlementItem net settlement does not tie out"
        }
    }

    private companion object {
        val SEOUL_ZONE_ID: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

internal enum class SettlementAdjustmentReason {
    REFUND_SUCCEEDED,
    DISPUTE_ACCEPTED,
    POST_ACCEPTANCE_RESOLUTION,
}

@Entity
@Immutable
@Table(name = "settlement_adjustment")
internal class SettlementAdjustmentEntity(
    @Id
    val id: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Column(name = "settlement_item_id", nullable = false)
    val settlementItemId: UUID,
    @Column(name = "source_settlement_batch_id", nullable = false)
    val sourceSettlementBatchId: UUID,
    @Column(name = "adjustment_source", nullable = false, length = 240)
    val adjustmentSource: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 32)
    val reasonCode: SettlementAdjustmentReason,
    @Column(name = "effective_at", nullable = false)
    val effectiveAt: Instant,
    @Column(name = "order_completed_at", nullable = false)
    val orderCompletedAt: Instant,
    @Column(name = "settlement_date", nullable = false)
    val settlementDate: LocalDate,
    @Column(nullable = false, length = 3)
    val currency: String,
    @Column(name = "amount_krw", nullable = false)
    val amountKrw: Long,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    init {
        require(adjustmentSource.isNotBlank() && adjustmentSource == adjustmentSource.trim()) {
            "SettlementAdjustment source must be present"
        }
        require(adjustmentSource.length <= 240) { "SettlementAdjustment source is too long" }
        require(currency == "KRW") { "SettlementAdjustment currency must be KRW" }
        require(orderCompletedAt.atZone(SEOUL_ZONE_ID).toLocalDate() == settlementDate) {
            "SettlementAdjustment settlement date must match completion"
        }
    }

    private companion object {
        val SEOUL_ZONE_ID: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

@Entity
@Immutable
@Table(name = "settlement_support_resolution_adjustment")
internal class SupportResolutionSettlementAdjustmentEntity(
    @Id
    val id: UUID,
    @Column(name = "resolution_id", nullable = false)
    val resolutionId: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Column(name = "settlement_adjustment_id", nullable = false)
    val settlementAdjustmentId: UUID,
    @Column(nullable = false, length = 16)
    val responsibility: String,
    @Column(name = "amount_krw", nullable = false)
    val amountKrw: Long,
    @Column(name = "source_reference", nullable = false, length = 240)
    val sourceReference: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "effective_at", nullable = false)
    val effectiveAt: Instant,
)

internal interface SettlementBatchJpaRepository : JpaRepository<SettlementBatchEntity, UUID> {
    fun findByStoreIdAndSettlementDate(
        storeId: UUID,
        settlementDate: LocalDate,
    ): SettlementBatchEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select batch from SettlementBatchEntity batch " +
            "where batch.storeId = :storeId and batch.settlementDate = :settlementDate",
    )
    fun findLockedByStoreIdAndSettlementDate(
        @Param("storeId") storeId: UUID,
        @Param("settlementDate") settlementDate: LocalDate,
    ): SettlementBatchEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select batch from SettlementBatchEntity batch where batch.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): SettlementBatchEntity?
}

internal interface SettlementItemJpaRepository : JpaRepository<SettlementItemEntity, UUID> {
    fun findByItemSource(itemSource: String): SettlementItemEntity?

    fun findByOrderId(orderId: UUID): SettlementItemEntity?
}

internal interface SettlementAdjustmentJpaRepository : JpaRepository<SettlementAdjustmentEntity, UUID> {
    fun findByAdjustmentSource(adjustmentSource: String): SettlementAdjustmentEntity?
}

internal interface SupportResolutionSettlementAdjustmentJpaRepository :
    JpaRepository<SupportResolutionSettlementAdjustmentEntity, UUID> {
    fun findBySourceReference(sourceReference: String): SupportResolutionSettlementAdjustmentEntity?
}
