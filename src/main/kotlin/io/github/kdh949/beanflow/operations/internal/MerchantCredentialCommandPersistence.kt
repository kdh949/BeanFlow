package io.github.kdh949.beanflow.operations.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

internal enum class MerchantCredentialOperation {
    CREATE,
    RESET_TEMPORARY_PASSWORD,
    RELEASE_LOCK,
}

internal enum class MerchantCredentialOutcome {
    ACCOUNT_CREATED,
    PASSWORD_RESET,
    LOCK_RELEASED,
}

@Entity
@Immutable
@Table(name = "operations_merchant_credential_command_idempotency")
internal class MerchantCredentialCommandEntity(
    @Id
    val id: UUID,
    @Column(name = "operator_id", nullable = false)
    val operatorId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val operation: MerchantCredentialOperation,
    @Column(name = "idempotency_key", nullable = false, length = 128)
    val idempotencyKey: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "merchant_account_id", nullable = false)
    val merchantAccountId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val outcome: MerchantCredentialOutcome,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
)

internal interface MerchantCredentialCommandJpaRepository : JpaRepository<MerchantCredentialCommandEntity, UUID> {
    fun findByOperatorIdAndOperationAndIdempotencyKey(
        operatorId: UUID,
        operation: MerchantCredentialOperation,
        idempotencyKey: String,
    ): MerchantCredentialCommandEntity?
}

internal data class MerchantCredentialRetentionResult(
    val deletedCount: Int,
    val oldestDueAt: Instant?,
)

@Repository
internal class MerchantCredentialRetentionRepository(
    private val jdbc: JdbcTemplate,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun purgeDue(
        now: Instant,
        limit: Int,
    ): MerchantCredentialRetentionResult {
        require(limit in 1..100) { "Merchant credential retention limit must be between 1 and 100" }
        val due =
            jdbc.query(
                """
                SELECT id, retention_expires_at
                  FROM operations_merchant_credential_command_idempotency
                 WHERE retention_expires_at <= ?
                 ORDER BY retention_expires_at, id
                 LIMIT ?
                 FOR UPDATE SKIP LOCKED
                """.trimIndent(),
                { result, _ -> result.getObject("id", UUID::class.java) to result.getTimestamp("retention_expires_at").toInstant() },
                Timestamp.from(now),
                limit,
            )
        if (due.isNotEmpty()) {
            jdbc.batchUpdate(
                "DELETE FROM operations_merchant_credential_command_idempotency WHERE id = ?",
                due.map { arrayOf<Any>(it.first) },
            )
        }
        return MerchantCredentialRetentionResult(due.size, due.firstOrNull()?.second)
    }
}
