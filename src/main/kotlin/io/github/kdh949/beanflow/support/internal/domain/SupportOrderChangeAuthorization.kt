package io.github.kdh949.beanflow.support.internal.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

internal enum class SupportOrderChangeAuthorizationType {
    CONFIRMATION,
    DELEGATION,
}

internal enum class SupportOrderChangeCostResponsibility {
    STORE,
    PLATFORM,
    UNKNOWN,
}

internal enum class SupportOrderChangeAuthorizationConsumption {
    APPLIED,
    ALREADY_APPLIED,
}

internal data class ConsumeSupportOrderChangeAuthorizationCommand(
    val executionId: UUID,
    val storeId: UUID,
    val action: SupportActionType,
    val requestId: UUID,
    val revisionNumber: Int,
    val payloadDigest: String,
    val targetVersion: Long,
)

internal class SupportOrderChangeAuthorization private constructor(
    val id: UUID,
    val storeId: UUID,
    val action: SupportActionType,
    val type: SupportOrderChangeAuthorizationType,
    val policyVersion: String,
    val requestId: UUID?,
    val revisionNumber: Int?,
    val payloadDigest: String?,
    val targetVersion: Long?,
    val authorizedByActorId: UUID,
    val authorizedAt: Instant,
    val expiresAt: Instant,
    val maxSuccessfulUses: Int,
    val costResponsibility: SupportOrderChangeCostResponsibility,
    private val uses: MutableMap<UUID, ConsumeSupportOrderChangeAuthorizationCommand> = linkedMapOf(),
) {
    val successfulUses: Int
        get() = uses.size

    init {
        require(action == SupportActionType.ORDER_CANCELLATION || action == SupportActionType.PICKUP_RESCHEDULE)
        require(policyVersion.length in 1..160 && policyVersion.none(Char::isISOControl))
        require(authorizedAt.isBefore(expiresAt))
        require(maxSuccessfulUses > 0)
        require(costResponsibility == SupportOrderChangeCostResponsibility.STORE)
    }

    fun consume(
        command: ConsumeSupportOrderChangeAuthorizationCommand,
        now: Instant,
    ): SupportOrderChangeAuthorizationConsumption {
        require(command.storeId == storeId)
        require(command.action == action)
        require(command.revisionNumber > 0 && command.targetVersion >= 0)
        require(command.payloadDigest.matches(SHA_256))
        requireConfirmationBinding(command)
        uses[command.executionId]?.let { previous ->
            require(previous == command)
            return SupportOrderChangeAuthorizationConsumption.ALREADY_APPLIED
        }
        check(now.isBefore(expiresAt))
        check(successfulUses < maxSuccessfulUses)
        uses[command.executionId] = command
        return SupportOrderChangeAuthorizationConsumption.APPLIED
    }

    private fun requireConfirmationBinding(command: ConsumeSupportOrderChangeAuthorizationCommand) {
        if (type != SupportOrderChangeAuthorizationType.CONFIRMATION) return
        require(command.requestId == requestId)
        require(command.revisionNumber == revisionNumber)
        require(command.payloadDigest == payloadDigest)
        require(command.targetVersion == targetVersion)
    }

    companion object {
        const val INITIAL_POLICY_VERSION = "support-order-change-policy/2026-08-12/v1"
        val SHA_256 = Regex("^[0-9a-f]{64}$")

        fun confirmation(
            id: UUID,
            storeId: UUID,
            action: SupportActionType,
            requestId: UUID,
            revisionNumber: Int,
            payloadDigest: String,
            targetVersion: Long,
            requestExpiresAt: Instant,
            authorizedByActorId: UUID,
            authorizedAt: Instant,
            costResponsibility: SupportOrderChangeCostResponsibility,
        ): SupportOrderChangeAuthorization =
            SupportOrderChangeAuthorization(
                id,
                storeId,
                action,
                SupportOrderChangeAuthorizationType.CONFIRMATION,
                INITIAL_POLICY_VERSION,
                requestId,
                revisionNumber,
                payloadDigest,
                targetVersion,
                authorizedByActorId,
                authorizedAt,
                requestExpiresAt,
                1,
                costResponsibility,
            )

        fun delegation(
            id: UUID,
            storeId: UUID,
            action: SupportActionType,
            policyVersion: String,
            authorizedByActorId: UUID,
            authorizedAt: Instant,
            costResponsibility: SupportOrderChangeCostResponsibility,
        ): SupportOrderChangeAuthorization {
            require(policyVersion == INITIAL_POLICY_VERSION)
            val (ttl, budget) =
                when (action) {
                    SupportActionType.ORDER_CANCELLATION -> Duration.ofMinutes(10) to 1
                    SupportActionType.PICKUP_RESCHEDULE -> Duration.ofMinutes(30) to 3
                    SupportActionType.POST_ACCEPTANCE_RESOLUTION -> throw IllegalArgumentException("Unsupported delegated action")
                }
            return SupportOrderChangeAuthorization(
                id,
                storeId,
                action,
                SupportOrderChangeAuthorizationType.DELEGATION,
                policyVersion,
                null,
                null,
                null,
                null,
                authorizedByActorId,
                authorizedAt,
                authorizedAt.plus(ttl),
                budget,
                costResponsibility,
            )
        }
    }
}
