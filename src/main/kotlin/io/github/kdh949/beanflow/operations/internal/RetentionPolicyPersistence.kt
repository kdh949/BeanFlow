package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.RetentionClass
import io.github.kdh949.beanflow.operations.api.RetentionDurationBasis
import io.github.kdh949.beanflow.operations.api.RetentionPolicyCategory
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

@Entity
@Immutable
@Table(name = "operations_retention_policy_version")
internal class RetentionPolicyVersionEntity(
    @Id
    @Column(name = "policy_version_id", nullable = false)
    val policyVersionId: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val category: RetentionPolicyCategory,
    @Enumerated(EnumType.STRING)
    @Column(name = "retention_class", nullable = false)
    val retentionClass: RetentionClass,
    @Enumerated(EnumType.STRING)
    @Column(name = "duration_basis", nullable = false)
    val durationBasis: RetentionDurationBasis,
    @Column(name = "duration_value", nullable = false)
    val durationValue: Int,
    @Column(name = "effective_at", nullable = false)
    val effectiveAt: Instant,
    @Column(name = "actor_reference", nullable = false)
    val actorReference: String,
    @Column(name = "evidence_reference", nullable = false)
    val evidenceReference: String,
)

@Entity
@Table(name = "operations_retention_policy_head")
internal class RetentionPolicyHeadEntity(
    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val category: RetentionPolicyCategory,
    @Column(name = "policy_version_id", nullable = false)
    val policyVersionId: Long,
    @Column(nullable = false)
    val version: Long,
)

internal interface RetentionPolicyVersionJpaRepository : JpaRepository<RetentionPolicyVersionEntity, Long>

internal interface RetentionPolicyHeadJpaRepository : JpaRepository<RetentionPolicyHeadEntity, RetentionPolicyCategory> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select head from RetentionPolicyHeadEntity head where head.category = :category")
    fun findLockedByCategory(
        @Param("category") category: RetentionPolicyCategory,
    ): RetentionPolicyHeadEntity?
}
