package io.github.kdh949.beanflow.operations.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "operations_customer_cancellation_refund_reconciliation_command")
internal class CustomerCancellationRefundReconciliationCommandEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "order_id", nullable = false)
    val orderId: UUID,
    @Column(name = "cancellation_order_version", nullable = false)
    val cancellationOrderVersion: Long,
    @Column(name = "operator_reason", nullable = false, length = 500)
    val operatorReason: String,
    @Column(nullable = false, length = 32)
    val state: String,
    @Column(name = "response_json", nullable = false, columnDefinition = "text")
    val responseJson: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
)

internal interface CustomerCancellationRefundReconciliationCommandJpaRepository :
    JpaRepository<CustomerCancellationRefundReconciliationCommandEntity, UUID> {
    fun findByActorIdAndIdempotencyKey(
        actorId: UUID,
        idempotencyKey: String,
    ): CustomerCancellationRefundReconciliationCommandEntity?

    @Query(
        "select command.id from CustomerCancellationRefundReconciliationCommandEntity command " +
            "where command.retentionExpiresAt <= :now order by command.retentionExpiresAt, command.id",
    )
    fun findDueIds(
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<UUID>
}
