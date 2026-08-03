package io.github.kdh949.beanflow.dispute.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

internal enum class SettlementDisputeState {
    FILED,
    UNDER_REVIEW,
    ACCEPTED,
    REJECTED,
    WITHDRAWN,
}

@Entity
@Table(name = "settlement_dispute")
internal class SettlementDisputeEntity(
    @Id
    val id: UUID,
    @Column(name = "settlement_item_id", nullable = false)
    val settlementItemId: UUID,
    @Column(name = "store_id", nullable = false)
    val storeId: UUID,
    @Column(name = "previous_dispute_id")
    val previousDisputeId: UUID?,
    @Column(name = "refile_count", nullable = false)
    val refileCount: Int,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var state: SettlementDisputeState,
    @Column(name = "expected_adjustment_krw", nullable = false)
    val expectedAdjustmentKrw: Long,
    @Column(name = "held_amount_krw", nullable = false)
    var heldAmountKrw: Long,
    @Column(nullable = false, length = 1000)
    val reason: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_references", nullable = false, columnDefinition = "jsonb")
    val evidenceReferences: List<String>,
    @Column(name = "actor_id", nullable = false)
    val actorId: UUID,
    @Column(nullable = false, length = 64)
    val operation: String,
    @Column(name = "idempotency_key", nullable = false, length = 200)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "response_status", nullable = false)
    val responseStatus: Int,
    @Column(name = "response_body", nullable = false, columnDefinition = "text")
    val responseBody: String,
    @Column(name = "correlation_id", nullable = false, length = 240)
    val correlationId: String,
    @Column(name = "settlement_adjustment_id")
    var settlementAdjustmentId: UUID? = null,
    @Column(name = "filed_at", nullable = false)
    val filedAt: Instant,
    @Column(name = "decided_at")
    var decidedAt: Instant? = null,
    @Version
    var version: Long = 0,
) {
    init {
        require(refileCount in 0..1) { "SettlementDispute refile count is invalid" }
        require((previousDisputeId == null) == (refileCount == 0)) {
            "SettlementDispute previous reference does not match refile count"
        }
        require(reason.isNotBlank() && reason == reason.trim() && reason.length <= 1_000) {
            "SettlementDispute reason is invalid"
        }
        require(evidenceReferences.isNotEmpty()) { "SettlementDispute evidence is required" }
        require(evidenceReferences.all { it.isNotBlank() && it == it.trim() && it.length <= 500 }) {
            "SettlementDispute evidence reference is invalid"
        }
        require(idempotencyKey.isNotBlank() && idempotencyKey == idempotencyKey.trim() && idempotencyKey.length <= 200) {
            "SettlementDispute idempotency key is invalid"
        }
        require(payloadHash.matches(Regex("[0-9a-f]{64}"))) { "SettlementDispute payload hash is invalid" }
        require(responseStatus == 201 && responseBody.isNotBlank()) { "SettlementDispute response snapshot is invalid" }
        require(correlationId.isNotBlank() && correlationId == correlationId.trim() && correlationId.length <= 240) {
            "SettlementDispute correlation is invalid"
        }
        validateState()
    }

    fun startReview() {
        check(state == SettlementDisputeState.FILED) { "SettlementDispute is not filed" }
        state = SettlementDisputeState.UNDER_REVIEW
        validateState()
    }

    fun accept(
        adjustmentId: UUID,
        decidedAt: Instant,
    ) {
        decide(SettlementDisputeState.ACCEPTED, adjustmentId, decidedAt)
    }

    fun reject(decidedAt: Instant) {
        decide(SettlementDisputeState.REJECTED, null, decidedAt)
    }

    fun withdraw(decidedAt: Instant) {
        decide(SettlementDisputeState.WITHDRAWN, null, decidedAt)
    }

    private fun decide(
        target: SettlementDisputeState,
        adjustmentId: UUID?,
        decidedAt: Instant,
    ) {
        check(state == SettlementDisputeState.UNDER_REVIEW) { "SettlementDispute is not under review" }
        require(!decidedAt.isBefore(filedAt)) { "SettlementDispute decision cannot precede filing" }
        state = target
        heldAmountKrw = 0
        settlementAdjustmentId = adjustmentId
        this.decidedAt = decidedAt
        validateState()
    }

    private fun validateState() {
        when (state) {
            SettlementDisputeState.FILED,
            SettlementDisputeState.UNDER_REVIEW,
            -> {
                require(heldAmountKrw == expectedAdjustmentKrw) { "Active SettlementDispute held amount changed" }
                require(decidedAt == null && settlementAdjustmentId == null) {
                    "Active SettlementDispute cannot contain a decision"
                }
            }

            SettlementDisputeState.ACCEPTED -> {
                require(heldAmountKrw == 0L && decidedAt != null && settlementAdjustmentId != null) {
                    "Accepted SettlementDispute requires its Adjustment"
                }
            }

            SettlementDisputeState.REJECTED,
            SettlementDisputeState.WITHDRAWN,
            -> {
                require(heldAmountKrw == 0L && decidedAt != null && settlementAdjustmentId == null) {
                    "Closed SettlementDispute has invalid held or Adjustment state"
                }
            }
        }
    }
}

internal interface SettlementDisputeJpaRepository : JpaRepository<SettlementDisputeEntity, UUID> {
    fun findByActorIdAndOperationAndIdempotencyKey(
        actorId: UUID,
        operation: String,
        idempotencyKey: String,
    ): SettlementDisputeEntity?

    fun findBySettlementItemIdAndStateIn(
        settlementItemId: UUID,
        states: Collection<SettlementDisputeState>,
    ): SettlementDisputeEntity?

    fun findFirstBySettlementItemIdOrderByFiledAtDescIdDesc(settlementItemId: UUID): SettlementDisputeEntity?

    fun findByStateOrderByFiledAtAscIdAsc(
        state: SettlementDisputeState,
        pageable: org.springframework.data.domain.Pageable,
    ): List<SettlementDisputeEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select dispute from SettlementDisputeEntity dispute where dispute.id = :id")
    fun findLockedById(
        @Param("id") id: UUID,
    ): SettlementDisputeEntity?
}
