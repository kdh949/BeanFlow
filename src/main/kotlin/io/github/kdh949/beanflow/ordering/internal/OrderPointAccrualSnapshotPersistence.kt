package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualExpiryRule
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicyScopeType
import io.github.kdh949.beanflow.operations.api.OrdinaryPointAccrualPolicySelectionSource
import io.github.kdh949.beanflow.operations.api.PointAccrualIssuerType
import io.github.kdh949.beanflow.operations.api.PointAccrualRoundingMode
import io.github.kdh949.beanflow.ordering.api.OrderPointAccrualSourceState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Entity
@Immutable
@Table(name = "ordering_order_point_accrual_source")
internal class OrderPointAccrualSourceEntity(
    @Id
    @Column(name = "order_id")
    val orderId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "source_state", nullable = false)
    val sourceState: OrderPointAccrualSourceState,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

@Entity
@Immutable
@Table(name = "ordering_order_point_accrual_snapshot")
internal class OrderPointAccrualSnapshotEntity(
    @Id
    @Column(name = "order_id")
    val orderId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "source_state", nullable = false)
    val sourceState: OrderPointAccrualSourceState = OrderPointAccrualSourceState.SNAPSHOTTED,
    @Column(name = "policy_version_id", nullable = false)
    val policyVersionId: Long,
    @Enumerated(EnumType.STRING)
    @Column(name = "selected_scope_type", nullable = false)
    val selectedScopeType: OrdinaryPointAccrualPolicyScopeType,
    @Column(name = "selected_scope_reference", nullable = false)
    val selectedScopeReference: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "selection_source", nullable = false)
    val selectionSource: OrdinaryPointAccrualPolicySelectionSource,
    @Column(name = "accrual_rate_bps", nullable = false)
    val accrualRateBps: Int,
    @Enumerated(EnumType.STRING)
    @Column(name = "rounding_mode", nullable = false)
    val roundingMode: PointAccrualRoundingMode,
    @Enumerated(EnumType.STRING)
    @Column(name = "issuer_type", nullable = false)
    val issuerType: PointAccrualIssuerType,
    @Column(name = "issuer_reference", nullable = false, length = 240)
    val issuerReference: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "expiry_rule", nullable = false)
    val expiryRule: OrdinaryPointAccrualExpiryRule,
    @Column(name = "validity_days", nullable = false)
    val validityDays: Int,
    @Column(name = "canonical_policy_hash", nullable = false, length = 64)
    val canonicalPolicyHash: String,
    @Column(name = "order_payable_krw", nullable = false)
    val orderPayableKrw: Long,
    @Column(name = "gross_accrual_amount_krw", nullable = false)
    val grossAccrualAmountKrw: Long,
    @Column(name = "snapshot_schema_version", nullable = false)
    val snapshotSchemaVersion: Int = 1,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

internal data class OrderPointAccrualUnitId(
    var orderId: UUID = UUID(0, 0),
    var lineSequence: Int = 0,
    var unitPosition: Int = 0,
) : Serializable

@Entity
@Immutable
@IdClass(OrderPointAccrualUnitId::class)
@Table(name = "ordering_order_point_accrual_unit")
internal class OrderPointAccrualUnitEntity(
    @Id
    @Column(name = "order_id")
    val orderId: UUID,
    @Column(name = "order_line_id", nullable = false)
    val orderLineId: UUID,
    @Id
    @Column(name = "line_sequence")
    val lineSequence: Int,
    @Id
    @Column(name = "unit_position")
    val unitPosition: Int,
    @Column(name = "cash_payable_krw", nullable = false)
    val cashPayableKrw: Long,
    @Column(name = "accrued_amount_krw", nullable = false)
    val accruedAmountKrw: Long,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

internal interface OrderPointAccrualSourceJpaRepository : JpaRepository<OrderPointAccrualSourceEntity, UUID>

internal interface OrderPointAccrualSnapshotJpaRepository : JpaRepository<OrderPointAccrualSnapshotEntity, UUID>

internal interface OrderPointAccrualUnitJpaRepository : JpaRepository<OrderPointAccrualUnitEntity, OrderPointAccrualUnitId> {
    fun findAllByOrderIdOrderByLineSequenceAscUnitPositionAsc(orderId: UUID): List<OrderPointAccrualUnitEntity>
}
