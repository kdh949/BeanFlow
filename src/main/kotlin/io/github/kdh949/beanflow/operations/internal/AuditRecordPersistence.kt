package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.RetentionClass
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

@Entity
@Immutable
@Table(name = "operations_audit_record")
internal class AuditRecordEntity(
    @Id
    val id: UUID,
    @Column(name = "actor_id", nullable = false)
    val actorId: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    val actorType: AuditActorType,
    @Enumerated(EnumType.STRING)
    @Column(name = "audit_category", nullable = false)
    val category: AuditCategory,
    @Column(nullable = false)
    val action: String,
    @Column(name = "target_type", nullable = false)
    val targetType: String,
    @Column(name = "target_id", nullable = false)
    val targetId: UUID,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant,
    @Column(nullable = false)
    val reason: String,
    @Column(name = "before_summary", nullable = false, columnDefinition = "text")
    val beforeSummary: String,
    @Column(name = "after_summary", nullable = false, columnDefinition = "text")
    val afterSummary: String,
    @Column(name = "correlation_id", nullable = false)
    val correlationId: String,
    @Column(name = "source_reference", nullable = false)
    val sourceReference: String,
    @Column(name = "retention_expires_at", nullable = false)
    val retentionExpiresAt: Instant,
    @Enumerated(EnumType.STRING)
    @Column(name = "retention_class", nullable = false)
    val retentionClass: RetentionClass,
    @Column(name = "retention_policy_version_id", nullable = false)
    val retentionPolicyVersionId: Long,
)

internal interface AuditRecordJpaRepository : JpaRepository<AuditRecordEntity, UUID> {
    fun existsByActionAndTargetTypeAndTargetIdAndSourceReference(
        action: String,
        targetType: String,
        targetId: UUID,
        sourceReference: String,
    ): Boolean
}
