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
    val state: SettlementBatchState = SettlementBatchState.OPEN,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Version
    var version: Long = 0,
) {
    init {
        require(state == SettlementBatchState.OPEN) {
            "Plan 20 can create only OPEN SettlementBatch aggregates"
        }
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
