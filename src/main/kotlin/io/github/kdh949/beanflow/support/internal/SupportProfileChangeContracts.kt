package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.delivery.api.PreparedCourierProfileChange
import io.github.kdh949.beanflow.identity.api.PreparedCustomerProfileChange
import io.github.kdh949.beanflow.merchant.api.PreparedStoreProfileChange
import io.github.kdh949.beanflow.support.internal.domain.ProfileChangePurpose
import io.github.kdh949.beanflow.support.internal.domain.ProfileRiskClass
import io.github.kdh949.beanflow.support.internal.domain.SupportProfileChangeState
import io.github.kdh949.beanflow.support.internal.domain.SupportProfileNotificationState
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal sealed interface SupportProfileChangePayload {
    val purpose: ProfileChangePurpose

    fun canonicalFields(): List<String?>

    data class CustomerDisplayName(
        val displayName: String,
    ) : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.CUSTOMER_DISPLAY_NAME

        override fun canonicalFields() = listOf(displayName)

        override fun toString() = "CustomerDisplayName(<redacted>)"
    }

    data class CustomerLegalName(
        val legalName: String,
    ) : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.CUSTOMER_LEGAL_NAME_TYPO

        override fun canonicalFields() = listOf(legalName)

        override fun toString() = "CustomerLegalName(<redacted>)"
    }

    data class CustomerPrimaryPhone(
        val primaryPhone: String,
    ) : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.CUSTOMER_PRIMARY_PHONE

        override fun canonicalFields() = listOf(primaryPhone)

        override fun toString() = "CustomerPrimaryPhone(<redacted>)"
    }

    data object CustomerCredentialReset : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.CUSTOMER_CREDENTIAL_RESET

        override fun canonicalFields() = emptyList<String>()
    }

    data class StorePublicProfile(
        val displayName: String?,
        val publicPhone: String?,
        val description: String?,
        val pickupInstructions: String?,
    ) : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.STORE_PUBLIC_PROFILE

        override fun canonicalFields() = listOf(displayName, publicPhone, description, pickupInstructions)

        override fun toString() = "StorePublicProfile(<redacted>)"
    }

    data class StoreOperationsContact(
        val phone: String?,
        val email: String?,
    ) : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.STORE_OPERATIONS_CONTACT

        override fun canonicalFields() = listOf(phone, email)

        override fun toString() = "StoreOperationsContact(<redacted>)"
    }

    data class StoreRepresentative(
        val representativeName: String,
    ) : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.STORE_REPRESENTATIVE

        override fun canonicalFields() = listOf(representativeName)

        override fun toString() = "StoreRepresentative(<redacted>)"
    }

    data class StoreSettlementAccount(
        val accountReference: String,
    ) : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.STORE_SETTLEMENT_ACCOUNT

        override fun canonicalFields() = listOf(accountReference)

        override fun toString() = "StoreSettlementAccount(<redacted>)"
    }

    data object StoreAccessReregistration : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.STORE_ACCESS_REREGISTRATION

        override fun canonicalFields() = emptyList<String>()
    }

    data class CourierDisplayName(
        val displayName: String,
    ) : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.COURIER_DISPLAY_NAME

        override fun canonicalFields() = listOf(displayName)

        override fun toString() = "CourierDisplayName(<redacted>)"
    }

    data class CourierRelayContact(
        val phone: String?,
        val email: String?,
    ) : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.COURIER_RELAY_CONTACT

        override fun canonicalFields() = listOf(phone, email)

        override fun toString() = "CourierRelayContact(<redacted>)"
    }

    data class CourierProviderIdentity(
        val providerReference: String,
    ) : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.COURIER_PROVIDER_IDENTITY

        override fun canonicalFields() = listOf(providerReference)

        override fun toString() = "CourierProviderIdentity(<redacted>)"
    }

    data class CourierPayoutReference(
        val payoutReference: String,
    ) : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.COURIER_PAYOUT_REFERENCE

        override fun canonicalFields() = listOf(payoutReference)

        override fun toString() = "CourierPayoutReference(<redacted>)"
    }

    data object CourierProviderReregistration : SupportProfileChangePayload {
        override val purpose = ProfileChangePurpose.COURIER_PROVIDER_REREGISTRATION

        override fun canonicalFields() = emptyList<String>()
    }
}

internal sealed interface PreparedOwnerProfileChange {
    data class Customer(
        val value: PreparedCustomerProfileChange,
    ) : PreparedOwnerProfileChange

    data class Store(
        val value: PreparedStoreProfileChange,
    ) : PreparedOwnerProfileChange

    data class Courier(
        val value: PreparedCourierProfileChange,
    ) : PreparedOwnerProfileChange
}

internal data class SubmitSupportProfileChangeCommand(
    val actorId: UUID,
    val caseId: UUID,
    val subjectId: UUID,
    val expectedProfileVersion: Long,
    val verificationSessionId: UUID,
    val reason: String,
    val evidenceDigest: String,
    val idempotencyKey: String,
    val payload: SupportProfileChangePayload,
) {
    override fun toString(): String =
        "SubmitSupportProfileChangeCommand(actorId=$actorId, caseId=$caseId, subjectId=$subjectId, " +
            "expectedProfileVersion=$expectedProfileVersion, verificationSessionId=$verificationSessionId, " +
            "purpose=${payload.purpose}, values=<redacted>)"
}

internal data class ReviseSupportProfileChangeCommand(
    val actorId: UUID,
    val profileChangeId: UUID,
    val expectedProfileChangeVersion: Long,
    val expectedActionRequestVersion: Long,
    val expectedProfileVersion: Long,
    val verificationSessionId: UUID,
    val reason: String,
    val evidenceDigest: String,
    val idempotencyKey: String,
    val payload: SupportProfileChangePayload,
) {
    override fun toString(): String = "ReviseSupportProfileChangeCommand(profileChangeId=$profileChangeId, values=<redacted>)"
}

internal data class ExecuteSupportProfileChangeCommand(
    val actorId: UUID,
    val profileChangeId: UUID,
    val revisionNumber: Int,
    val expectedActionRequestVersion: Long,
    val expectedProfileChangeVersion: Long,
    val expectedProfileVersion: Long,
    val idempotencyKey: String,
    val payload: SupportProfileChangePayload,
) {
    override fun toString(): String = "ExecuteSupportProfileChangeCommand(profileChangeId=$profileChangeId, values=<redacted>)"
}

internal data class RetrySupportProfileNotificationCommand(
    val actorId: UUID,
    val profileChangeId: UUID,
    val expectedProfileChangeVersion: Long,
    val idempotencyKey: String,
)

internal data class SupportProfileChangeNotificationResource(
    val targetKind: String,
    val channel: String,
    val state: SupportProfileNotificationState,
    val deliveryId: UUID?,
    val failureCode: String?,
    val attempts: Int,
)

internal data class SupportProfileChangeResource(
    val profileChangeId: UUID,
    val caseId: UUID,
    val subjectType: ProfileChangeSubjectType,
    val subjectId: UUID,
    val purpose: ProfileChangePurpose,
    val riskClass: ProfileRiskClass,
    val requesterActorId: UUID,
    val executorActorId: UUID,
    val verificationSessionId: UUID,
    val expectedProfileVersion: Long,
    val currentProfileVersion: Long?,
    val payloadDigest: String,
    val actionRequestId: UUID?,
    val state: SupportProfileChangeState,
    val notificationState: SupportProfileNotificationState,
    val notificationFailureCode: String?,
    val maskedBefore: String?,
    val maskedAfter: String?,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val notifications: List<SupportProfileChangeNotificationResource>,
)

internal object SupportProfilePayloadDigest {
    fun digest(
        subjectId: UUID,
        expectedVersion: Long,
        payload: SupportProfileChangePayload,
    ): String {
        require(expectedVersion >= 0)
        return framedDigest(
            buildList {
                add("support-profile-change:v2")
                add(payload.purpose.name)
                add(subjectId.toString())
                add(expectedVersion.toString())
                addAll(payload.canonicalFields())
            },
        )
    }

    fun idempotency(
        operation: String,
        actorId: UUID,
        caseId: UUID?,
        profileChangeId: UUID?,
        verificationSessionId: UUID?,
        payloadDigest: String?,
        expectedVersion: Long,
        reason: String?,
        evidenceDigest: String?,
    ): String =
        framedDigest(
            listOf(
                "support-profile-idempotency:v2",
                operation,
                actorId.toString(),
                caseId?.toString(),
                profileChangeId?.toString(),
                verificationSessionId?.toString(),
                payloadDigest,
                expectedVersion.toString(),
                reason,
                evidenceDigest,
            ),
        )

    private fun framedDigest(fields: List<String?>): String {
        val bytes =
            ByteArrayOutputStream().use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(fields.size)
                    fields.forEach { value ->
                        if (value == null) {
                            output.writeByte(NULL_FIELD)
                        } else {
                            val encoded = value.toByteArray(StandardCharsets.UTF_8)
                            output.writeByte(PRESENT_FIELD)
                            output.writeInt(encoded.size)
                            output.write(encoded)
                        }
                    }
                }
                buffer.toByteArray()
            }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private const val NULL_FIELD = 0
    private const val PRESENT_FIELD = 1
}
