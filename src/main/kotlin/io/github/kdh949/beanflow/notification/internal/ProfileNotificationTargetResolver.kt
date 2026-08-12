package io.github.kdh949.beanflow.notification.internal

import io.github.kdh949.beanflow.delivery.api.ExternalCourierSupportProfileChangeOperations
import io.github.kdh949.beanflow.identity.api.CustomerSupportProfileChangeOperations
import io.github.kdh949.beanflow.merchant.api.StoreSupportProfileChangeOperations
import io.github.kdh949.beanflow.notification.api.ProfileNotificationOwnerType
import io.github.kdh949.beanflow.notification.internal.domain.NotificationLogicalChannel
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ProfileNotificationChannel
import io.github.kdh949.beanflow.shared.api.ProfileNotificationTargetKind
import io.github.kdh949.beanflow.shared.api.ResolvedProfileNotificationTarget
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
internal class ProfileNotificationTargetResolver(
    private val customers: CustomerSupportProfileChangeOperations,
    private val stores: StoreSupportProfileChangeOperations,
    private val couriers: ExternalCourierSupportProfileChangeOperations,
    private val objectMapper: ObjectMapper,
) {
    fun resolve(
        targetId: UUID,
        logicalChannel: NotificationLogicalChannel,
        payloadJson: String,
    ): ResolvedProfileNotificationTarget {
        val payload = objectMapper.readTree(payloadJson)
        val owner = enumValue<ProfileNotificationOwnerType>(payload.path("ownerType").asText())
        val expectedKind = enumValue<ProfileNotificationTargetKind>(payload.path("targetKind").asText())
        val expectedChannel = enumValue<ProfileNotificationChannel>(payload.path("channel").asText())
        val resolved =
            when (owner) {
                ProfileNotificationOwnerType.CUSTOMER -> customers.resolveNotificationTarget(targetId)
                ProfileNotificationOwnerType.STORE -> stores.resolveNotificationTarget(targetId)
                ProfileNotificationOwnerType.EXTERNAL_COURIER -> couriers.resolveNotificationTarget(targetId)
            }
        if (resolved.targetId != targetId || resolved.kind != expectedKind || resolved.channel != expectedChannel ||
            logicalChannel != resolved.kind.logicalChannel()
        ) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Profile notification owner snapshot binding is inconsistent")
        }
        return resolved
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().singleOrNull { it.name == value }
            ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Profile notification payload binding is invalid")
}

internal fun ProfileNotificationTargetKind.logicalChannel(): NotificationLogicalChannel =
    when (this) {
        ProfileNotificationTargetKind.OLD -> NotificationLogicalChannel.PROFILE_OLD
        ProfileNotificationTargetKind.NEW -> NotificationLogicalChannel.PROFILE_NEW
        ProfileNotificationTargetKind.CURRENT -> NotificationLogicalChannel.PROFILE_CURRENT
    }
