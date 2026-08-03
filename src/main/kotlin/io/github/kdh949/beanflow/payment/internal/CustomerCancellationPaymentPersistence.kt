package io.github.kdh949.beanflow.payment.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "payment_cancellation_recovery_snapshot")
internal class CustomerCancellationPaymentSnapshotEntity(
    @Id
    val id: UUID,
    @Column(name = "payment_id", nullable = false)
    val paymentId: UUID,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "cancellation_order_version", nullable = false)
    val cancellationOrderVersion: Long,
    @Column(name = "approved_amount_krw", nullable = false)
    val approvedAmountKrw: Long,
    @Column(name = "succeeded_refund_amount_before_cancellation_krw", nullable = false)
    val succeededRefundAmountBeforeCancellationKrw: Long,
    @Column(name = "cancellation_requested_refund_amount_krw", nullable = false)
    val cancellationRequestedRefundAmountKrw: Long,
    @Column(name = "cancellation_refund_id")
    val cancellationRefundId: UUID?,
    @Column(name = "refund_source_reference", length = 240)
    val refundSourceReference: String?,
    @Column(name = "provider_idempotency_key", length = 240)
    val providerIdempotencyKey: String?,
    @Column(name = "correlation_id", nullable = false, length = 128)
    val correlationId: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Version
    var version: Long = 0,
)

internal interface CustomerCancellationPaymentSnapshotJpaRepository : JpaRepository<CustomerCancellationPaymentSnapshotEntity, UUID> {
    fun findByOrderId(orderId: UUID): CustomerCancellationPaymentSnapshotEntity?
}
