package io.github.kdh949.beanflow.support.internal.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProfileFieldRiskPolicyTest {
    @Test
    fun `every initial field has one owner and risk class`() {
        assertThat(SupportProfileField.entries.groupBy { it.owner }.mapValues { (_, fields) -> fields.map { it.risk }.toSet() })
            .containsEntry(ProfileOwnerType.CUSTOMER, ProfileRiskClass.entries.toSet())
            .containsEntry(ProfileOwnerType.STORE, ProfileRiskClass.entries.toSet())
            .containsEntry(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.entries.toSet())
    }

    @Test
    fun `change purposes expose the accepted initial mapping`() {
        assertThat(ProfileChangePurpose.CUSTOMER_DISPLAY_NAME.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R1, false))
        assertThat(ProfileChangePurpose.CUSTOMER_LEGAL_NAME_TYPO.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R2, false))
        assertThat(ProfileChangePurpose.CUSTOMER_PRIMARY_PHONE.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R3, true))
        assertThat(ProfileChangePurpose.STORE_PUBLIC_PROFILE.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.STORE, ProfileRiskClass.R1, false))
        assertThat(ProfileChangePurpose.STORE_OPERATIONS_CONTACT.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.STORE, ProfileRiskClass.R2, false))
        assertThat(ProfileChangePurpose.STORE_REPRESENTATIVE.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.STORE, ProfileRiskClass.R3, true))
        assertThat(ProfileChangePurpose.STORE_SETTLEMENT_ACCOUNT.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.STORE, ProfileRiskClass.R3, true))
        assertThat(ProfileChangePurpose.COURIER_DISPLAY_NAME.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R1, false))
        assertThat(ProfileChangePurpose.COURIER_RELAY_CONTACT.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R2, false))
        assertThat(ProfileChangePurpose.COURIER_PROVIDER_IDENTITY.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R3, true))
        assertThat(ProfileChangePurpose.COURIER_PAYOUT_REFERENCE.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R3, true))
    }

    @Test
    fun `R4 supports reset or re-registration intents and never direct field change`() {
        assertThat(ProfileChangePurpose.CUSTOMER_CREDENTIAL_RESET.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.CUSTOMER, ProfileRiskClass.R4, true))
        assertThat(ProfileChangePurpose.STORE_ACCESS_REREGISTRATION.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.STORE, ProfileRiskClass.R4, true))
        assertThat(ProfileChangePurpose.COURIER_PROVIDER_REREGISTRATION.descriptor())
            .isEqualTo(ProfileChangeDescriptor(ProfileOwnerType.EXTERNAL_COURIER, ProfileRiskClass.R4, true))

        SupportProfileField.entries.filter { it.risk == ProfileRiskClass.R4 }.forEach { secret ->
            assertThatThrownBy { ProfileFieldRiskPolicy.requireDirectlyChangeable(secret) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("secret")
        }
    }

    @Test
    fun `R0 fields have no change purpose`() {
        SupportProfileField.entries.filter { it.risk == ProfileRiskClass.R0 }.forEach { systemField ->
            assertThat(ProfileFieldRiskPolicy.purposeFor(systemField)).isNull()
            assertThatThrownBy { ProfileFieldRiskPolicy.requireDirectlyChangeable(systemField) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("immutable")
        }
    }
}
