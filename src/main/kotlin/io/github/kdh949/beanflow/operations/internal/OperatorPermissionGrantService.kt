package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.persistence.EntityManager
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

internal enum class OperatorPermissionBootstrapAction {
    GRANT,
    REVOKE,
    REGRANT,
}

internal enum class OperatorPermissionBootstrapResult {
    APPLIED,
    INVALID_INPUT,
    IDENTITY_VERIFICATION_FAILED,
    GRANT_STATE_CONFLICT,
    DEPENDENCY_UNAVAILABLE,
}

internal data class VerifiedReleasePrincipal(
    val reference: String,
)

internal data class OperatorPermissionBootstrapCommand(
    val action: OperatorPermissionBootstrapAction,
    val actorId: UUID,
    val permission: OperatorPermission,
    val reason: String,
    val evidenceReference: String,
    val correlationId: String,
    val now: Instant,
)

@Service
internal class OperatorPermissionAuthorizationService(
    private val repository: OperatorPermissionGrantJpaRepository,
    private val metrics: OperatorSecurityMetrics,
) : OperatorPermissionAuthorization {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun requireActive(
        actorId: UUID,
        permission: OperatorPermission,
    ) {
        if (!hasActive(actorId, permission)) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Active operator permission grant is required")
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun hasActive(
        actorId: UUID,
        permission: OperatorPermission,
    ): Boolean {
        try {
            val active = repository.findActiveLocked(actorId, permission) != null
            if (!active) {
                metrics.authorization(permission, OperatorSecurityOutcome.DENIED)
                return false
            }
            metrics.authorization(permission, OperatorSecurityOutcome.ACTIVE)
            return true
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            metrics.authorization(permission, OperatorSecurityOutcome.DEPENDENCY_UNAVAILABLE)
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Operator permission grant could not be verified",
            )
        }
    }
}

internal class GrantStateConflict : RuntimeException()

@Service
internal class OperatorPermissionGrantTransaction(
    private val repository: OperatorPermissionGrantJpaRepository,
    private val advisoryLock: DatabaseAdvisoryLock,
    private val auditRecordOperations: AuditRecordOperations,
    private val entityManager: EntityManager,
) {
    @Transactional
    fun apply(
        command: OperatorPermissionBootstrapCommand,
        principal: VerifiedReleasePrincipal,
    ) {
        validate(command, principal)
        advisoryLock.lock("operator-permission-grant:${command.actorId}:${command.permission.name}")
        val existing = repository.findLocked(command.actorId, command.permission)
        val previousVersion = existing?.version ?: 0
        val nextVersion = (existing?.version ?: 0) + 1
        val source =
            "operator-permission-grant:${command.actorId}:${command.permission.name}:" +
                "$nextVersion:${command.action.name.lowercase()}"
        val beforeState = existing?.state?.name ?: "ABSENT"
        val grant = transition(existing, command, nextVersion, source)
        repository.save(grant)
        auditRecordOperations.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = principal.reference,
                    actorType = AuditActorType.SYSTEM,
                    category = AuditCategory.SECURITY_AND_PERMISSION,
                    action = auditAction(command.action),
                    targetType = "OPERATOR_PERMISSION_GRANT",
                    targetId = targetId(command.actorId, command.permission),
                    occurredAt = command.now,
                    reason = "VERIFIED_RELEASE_OPERATOR_PERMISSION_CHANGE",
                    beforeSummary =
                        mapOf(
                            "state" to beforeState,
                            "version" to previousVersion.toString(),
                        ),
                    afterSummary =
                        mapOf(
                            "state" to grant.state.name,
                            "version" to grant.version.toString(),
                            "permission" to grant.permission.name,
                            "targetActorId" to grant.actorId.toString(),
                            "evidenceReference" to command.evidenceReference.trim(),
                        ),
                    correlationId = command.correlationId.trim(),
                    sourceReference = source,
                ),
            ),
        )
        entityManager.flush()
    }

    private fun transition(
        existing: OperatorPermissionGrantEntity?,
        command: OperatorPermissionBootstrapCommand,
        nextVersion: Long,
        source: String,
    ): OperatorPermissionGrantEntity =
        when (command.action) {
            OperatorPermissionBootstrapAction.GRANT -> {
                if (existing != null) throw GrantStateConflict()
                OperatorPermissionGrantEntity(
                    actorId = command.actorId,
                    permission = command.permission,
                    state = OperatorPermissionGrantState.ACTIVE,
                    grantedAt = command.now,
                    revokedAt = null,
                    version = nextVersion,
                    auditSourceReference = source,
                )
            }

            OperatorPermissionBootstrapAction.REVOKE -> {
                if (existing?.state != OperatorPermissionGrantState.ACTIVE) throw GrantStateConflict()
                existing.state = OperatorPermissionGrantState.REVOKED
                existing.revokedAt = command.now
                existing.version = nextVersion
                existing.auditSourceReference = source
                existing
            }

            OperatorPermissionBootstrapAction.REGRANT -> {
                if (existing?.state != OperatorPermissionGrantState.REVOKED) throw GrantStateConflict()
                existing.state = OperatorPermissionGrantState.ACTIVE
                existing.grantedAt = command.now
                existing.revokedAt = null
                existing.version = nextVersion
                existing.auditSourceReference = source
                existing
            }
        }

    private fun validate(
        command: OperatorPermissionBootstrapCommand,
        principal: VerifiedReleasePrincipal,
    ) {
        if (command.reason.trim().length !in 1..500 || command.reason.hasControlCharacter()) {
            throw IllegalArgumentException("Invalid bootstrap reason")
        }
        if (command.evidenceReference.trim().length !in 1..500 || command.evidenceReference.hasControlCharacter()) {
            throw IllegalArgumentException("Invalid bootstrap evidence reference")
        }
        if (command.correlationId.trim().length !in 1..160 || command.correlationId.hasControlCharacter()) {
            throw IllegalArgumentException("Invalid bootstrap correlation ID")
        }
        if (principal.reference.length !in 1..500 || principal.reference.hasControlCharacter()) {
            throw IllegalArgumentException("Invalid release principal reference")
        }
    }

    private fun targetId(
        actorId: UUID,
        permission: OperatorPermission,
    ): UUID = UUID.nameUUIDFromBytes("operator-permission-grant:$actorId:${permission.name}".toByteArray(StandardCharsets.UTF_8))

    private fun auditAction(action: OperatorPermissionBootstrapAction): String =
        when (action) {
            OperatorPermissionBootstrapAction.GRANT -> "OPERATOR_PERMISSION_GRANTED"
            OperatorPermissionBootstrapAction.REVOKE -> "OPERATOR_PERMISSION_REVOKED"
            OperatorPermissionBootstrapAction.REGRANT -> "OPERATOR_PERMISSION_REGRANTED"
        }
}

@Component
internal class OperatorPermissionGrantLifecycle(
    private val transaction: OperatorPermissionGrantTransaction,
    private val metrics: OperatorSecurityMetrics,
) {
    fun apply(
        command: OperatorPermissionBootstrapCommand,
        principal: VerifiedReleasePrincipal,
    ): OperatorPermissionBootstrapResult {
        val result =
            try {
                transaction.apply(command, principal)
                OperatorPermissionBootstrapResult.APPLIED
            } catch (_: IllegalArgumentException) {
                OperatorPermissionBootstrapResult.INVALID_INPUT
            } catch (_: GrantStateConflict) {
                OperatorPermissionBootstrapResult.GRANT_STATE_CONFLICT
            } catch (_: DataAccessException) {
                OperatorPermissionBootstrapResult.DEPENDENCY_UNAVAILABLE
            } catch (_: DomainFailure) {
                OperatorPermissionBootstrapResult.DEPENDENCY_UNAVAILABLE
            } catch (_: RuntimeException) {
                OperatorPermissionBootstrapResult.DEPENDENCY_UNAVAILABLE
            }
        metrics.grant(command.permission, command.action, result.toMetricOutcome())
        return result
    }

    private fun OperatorPermissionBootstrapResult.toMetricOutcome(): OperatorSecurityOutcome =
        when (this) {
            OperatorPermissionBootstrapResult.APPLIED -> OperatorSecurityOutcome.SUCCEEDED

            OperatorPermissionBootstrapResult.INVALID_INPUT -> OperatorSecurityOutcome.INVALID_INPUT

            OperatorPermissionBootstrapResult.GRANT_STATE_CONFLICT -> OperatorSecurityOutcome.CONFLICT

            OperatorPermissionBootstrapResult.IDENTITY_VERIFICATION_FAILED,
            OperatorPermissionBootstrapResult.DEPENDENCY_UNAVAILABLE,
            -> OperatorSecurityOutcome.DEPENDENCY_UNAVAILABLE
        }
}

internal fun String.hasControlCharacter(): Boolean = any { it.code < 0x20 || it.code == 0x7f }
