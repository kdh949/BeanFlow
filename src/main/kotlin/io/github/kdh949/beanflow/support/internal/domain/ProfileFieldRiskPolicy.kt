package io.github.kdh949.beanflow.support.internal.domain

internal enum class ProfileOwnerType {
    CUSTOMER,
    STORE,
    EXTERNAL_COURIER,
}

internal enum class ProfileRiskClass {
    R0,
    R1,
    R2,
    R3,
    R4,
}

internal enum class SupportProfileField(
    val owner: ProfileOwnerType,
    val risk: ProfileRiskClass,
) {
    CUSTOMER_ID(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R0),
    CUSTOMER_PROFILE_VERSION(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R0),
    CUSTOMER_DISPLAY_NAME(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R1),
    CUSTOMER_LEGAL_NAME(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R2),
    CUSTOMER_PRIMARY_PHONE(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R3),
    CUSTOMER_CREDENTIAL_SECRET(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R4),

    STORE_ID(ProfileOwnerType.STORE, ProfileRiskClass.R0),
    STORE_PROFILE_VERSION(ProfileOwnerType.STORE, ProfileRiskClass.R0),
    STORE_PUBLIC_DISPLAY_NAME(ProfileOwnerType.STORE, ProfileRiskClass.R1),
    STORE_PUBLIC_PHONE(ProfileOwnerType.STORE, ProfileRiskClass.R1),
    STORE_PUBLIC_DESCRIPTION(ProfileOwnerType.STORE, ProfileRiskClass.R1),
    STORE_PICKUP_INSTRUCTIONS(ProfileOwnerType.STORE, ProfileRiskClass.R1),
    STORE_OPERATIONS_PHONE(ProfileOwnerType.STORE, ProfileRiskClass.R2),
    STORE_OPERATIONS_EMAIL(ProfileOwnerType.STORE, ProfileRiskClass.R2),
    STORE_LEGAL_REPRESENTATIVE(ProfileOwnerType.STORE, ProfileRiskClass.R3),
    STORE_SETTLEMENT_ACCOUNT_REFERENCE(ProfileOwnerType.STORE, ProfileRiskClass.R3),
    STORE_ACCESS_SECRET(ProfileOwnerType.STORE, ProfileRiskClass.R4),

    EXTERNAL_COURIER_ID(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R0),
    EXTERNAL_COURIER_PROFILE_VERSION(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R0),
    COURIER_DISPLAY_NAME(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R1),
    COURIER_RELAY_PHONE(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R2),
    COURIER_RELAY_EMAIL(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R2),
    COURIER_PROVIDER_IDENTITY_REFERENCE(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R3),
    COURIER_PAYOUT_REFERENCE(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R3),
    COURIER_PROVIDER_SECRET(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R4),
}

internal enum class ProfileChangePurpose {
    CUSTOMER_DISPLAY_NAME,
    CUSTOMER_LEGAL_NAME_TYPO,
    CUSTOMER_PRIMARY_PHONE,
    CUSTOMER_CREDENTIAL_RESET,
    STORE_PUBLIC_PROFILE,
    STORE_OPERATIONS_CONTACT,
    STORE_REPRESENTATIVE,
    STORE_SETTLEMENT_ACCOUNT,
    STORE_ACCESS_REREGISTRATION,
    COURIER_DISPLAY_NAME,
    COURIER_RELAY_CONTACT,
    COURIER_PROVIDER_IDENTITY,
    COURIER_PAYOUT_REFERENCE,
    COURIER_PROVIDER_REREGISTRATION,
}

internal data class ProfileChangeDescriptor(
    val owner: ProfileOwnerType,
    val risk: ProfileRiskClass,
    val requiresDualApproval: Boolean,
)

internal fun ProfileChangePurpose.descriptor(): ProfileChangeDescriptor =
    when (this) {
        ProfileChangePurpose.CUSTOMER_DISPLAY_NAME -> {
            descriptor(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R1)
        }

        ProfileChangePurpose.CUSTOMER_LEGAL_NAME_TYPO -> {
            descriptor(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R2)
        }

        ProfileChangePurpose.CUSTOMER_PRIMARY_PHONE -> {
            descriptor(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R3)
        }

        ProfileChangePurpose.CUSTOMER_CREDENTIAL_RESET -> {
            descriptor(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R4)
        }

        ProfileChangePurpose.STORE_PUBLIC_PROFILE -> {
            descriptor(ProfileOwnerType.STORE, ProfileRiskClass.R1)
        }

        ProfileChangePurpose.STORE_OPERATIONS_CONTACT -> {
            descriptor(ProfileOwnerType.STORE, ProfileRiskClass.R2)
        }

        ProfileChangePurpose.STORE_REPRESENTATIVE -> {
            descriptor(ProfileOwnerType.STORE, ProfileRiskClass.R3)
        }

        ProfileChangePurpose.STORE_SETTLEMENT_ACCOUNT -> {
            descriptor(ProfileOwnerType.STORE, ProfileRiskClass.R3)
        }

        ProfileChangePurpose.STORE_ACCESS_REREGISTRATION -> {
            descriptor(ProfileOwnerType.STORE, ProfileRiskClass.R4)
        }

        ProfileChangePurpose.COURIER_DISPLAY_NAME -> {
            descriptor(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R1)
        }

        ProfileChangePurpose.COURIER_RELAY_CONTACT -> {
            descriptor(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R2)
        }

        ProfileChangePurpose.COURIER_PROVIDER_IDENTITY -> {
            descriptor(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R3)
        }

        ProfileChangePurpose.COURIER_PAYOUT_REFERENCE -> {
            descriptor(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R3)
        }

        ProfileChangePurpose.COURIER_PROVIDER_REREGISTRATION -> {
            descriptor(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R4)
        }
    }

private fun descriptor(
    owner: ProfileOwnerType,
    risk: ProfileRiskClass,
): ProfileChangeDescriptor = ProfileChangeDescriptor(owner, risk, risk == ProfileRiskClass.R3 || risk == ProfileRiskClass.R4)

internal object ProfileFieldRiskPolicy {
    fun purposeFor(field: SupportProfileField): ProfileChangePurpose? =
        when (field) {
            SupportProfileField.CUSTOMER_ID,
            SupportProfileField.CUSTOMER_PROFILE_VERSION,
            SupportProfileField.STORE_ID,
            SupportProfileField.STORE_PROFILE_VERSION,
            SupportProfileField.EXTERNAL_COURIER_ID,
            SupportProfileField.EXTERNAL_COURIER_PROFILE_VERSION,
            -> null

            SupportProfileField.CUSTOMER_DISPLAY_NAME -> ProfileChangePurpose.CUSTOMER_DISPLAY_NAME

            SupportProfileField.CUSTOMER_LEGAL_NAME -> ProfileChangePurpose.CUSTOMER_LEGAL_NAME_TYPO

            SupportProfileField.CUSTOMER_PRIMARY_PHONE -> ProfileChangePurpose.CUSTOMER_PRIMARY_PHONE

            SupportProfileField.CUSTOMER_CREDENTIAL_SECRET -> ProfileChangePurpose.CUSTOMER_CREDENTIAL_RESET

            SupportProfileField.STORE_PUBLIC_DISPLAY_NAME,
            SupportProfileField.STORE_PUBLIC_PHONE,
            SupportProfileField.STORE_PUBLIC_DESCRIPTION,
            SupportProfileField.STORE_PICKUP_INSTRUCTIONS,
            -> ProfileChangePurpose.STORE_PUBLIC_PROFILE

            SupportProfileField.STORE_OPERATIONS_PHONE,
            SupportProfileField.STORE_OPERATIONS_EMAIL,
            -> ProfileChangePurpose.STORE_OPERATIONS_CONTACT

            SupportProfileField.STORE_LEGAL_REPRESENTATIVE -> ProfileChangePurpose.STORE_REPRESENTATIVE

            SupportProfileField.STORE_SETTLEMENT_ACCOUNT_REFERENCE -> ProfileChangePurpose.STORE_SETTLEMENT_ACCOUNT

            SupportProfileField.STORE_ACCESS_SECRET -> ProfileChangePurpose.STORE_ACCESS_REREGISTRATION

            SupportProfileField.COURIER_DISPLAY_NAME -> ProfileChangePurpose.COURIER_DISPLAY_NAME

            SupportProfileField.COURIER_RELAY_PHONE,
            SupportProfileField.COURIER_RELAY_EMAIL,
            -> ProfileChangePurpose.COURIER_RELAY_CONTACT

            SupportProfileField.COURIER_PROVIDER_IDENTITY_REFERENCE -> ProfileChangePurpose.COURIER_PROVIDER_IDENTITY

            SupportProfileField.COURIER_PAYOUT_REFERENCE -> ProfileChangePurpose.COURIER_PAYOUT_REFERENCE

            SupportProfileField.COURIER_PROVIDER_SECRET -> ProfileChangePurpose.COURIER_PROVIDER_REREGISTRATION
        }

    fun requireDirectlyChangeable(field: SupportProfileField): ProfileChangePurpose {
        require(field.risk != ProfileRiskClass.R0) { "R0 system field is immutable" }
        require(field.risk != ProfileRiskClass.R4) { "R4 secret is reset-only" }
        require(field.risk != ProfileRiskClass.R3) { "R3 field requires exact dual approval" }
        return requireNotNull(purposeFor(field))
    }
}
