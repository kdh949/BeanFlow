package io.github.kdh949.beanflow.payment.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

internal enum class PaymentType {
	BENEFIT_ONLY,
}

internal enum class PaymentApprovalState {
	APPROVED,
}

@Entity
@Table(name = "payment_payment")
internal class PaymentEntity(
	@Id
	val id: UUID,
	@Column(name = "order_id", nullable = false)
	val orderId: UUID,
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	val type: PaymentType,
	@Enumerated(EnumType.STRING)
	@Column(name = "approval_state", nullable = false)
	val approvalState: PaymentApprovalState,
	@Column(name = "approved_amount_krw", nullable = false)
	val approvedAmountKrw: Long,
	@Column(nullable = false)
	val currency: String,
	@Column(name = "benefit_snapshot_reference", nullable = false)
	val benefitSnapshotReference: String,
	@Column(name = "source_reference", nullable = false)
	val sourceReference: String,
	@Column(name = "correlation_id", nullable = false)
	val correlationId: String,
	@Column(name = "approved_at", nullable = false)
	val approvedAt: Instant,
	@Column(name = "updated_at", nullable = false)
	val updatedAt: Instant,
)

internal interface PaymentJpaRepository : JpaRepository<PaymentEntity, UUID> {
	fun findByOrderId(orderId: UUID): PaymentEntity?
	fun findBySourceReference(sourceReference: String): PaymentEntity?
}
