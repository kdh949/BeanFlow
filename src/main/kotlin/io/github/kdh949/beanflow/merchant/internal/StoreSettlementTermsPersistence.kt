package io.github.kdh949.beanflow.merchant.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

@Entity
@Immutable
@Table(name = "merchant_store_settlement_terms")
internal class StoreSettlementTermsEntity(
    @Id
    @Column(name = "terms_version_id")
    val termsVersionId: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Column(name = "source_reference", nullable = false, length = 240)
    val sourceReference: String,
    @Column(name = "fee_rate_bps", nullable = false)
    val feeRateBps: Int,
    @Column(name = "effective_from", nullable = false)
    val effectiveFrom: Instant,
    @Column(name = "effective_to")
    val effectiveTo: Instant?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

internal interface StoreSettlementTermsJpaRepository : JpaRepository<StoreSettlementTermsEntity, UUID> {
    @Query(
        "select terms from StoreSettlementTermsEntity terms " +
            "where terms.storeId = :storeId " +
            "and terms.effectiveFrom <= :effectiveAt " +
            "and (terms.effectiveTo is null or terms.effectiveTo > :effectiveAt) " +
            "order by terms.effectiveFrom desc, terms.termsVersionId",
    )
    fun findApplicable(
        @Param("storeId") storeId: UUID,
        @Param("effectiveAt") effectiveAt: Instant,
    ): List<StoreSettlementTermsEntity>
}
