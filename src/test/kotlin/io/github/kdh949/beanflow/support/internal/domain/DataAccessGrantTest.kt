package io.github.kdh949.beanflow.support.internal.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class DataAccessGrantTest {
    private val requestedAt = Instant.parse("2026-08-12T00:00:00Z")
    private val requesterId = UUID.fromString("43000000-0000-0000-0000-000000000001")
    private val approverId = UUID.fromString("43000000-0000-0000-0000-000000000002")

    @Test
    fun `basic display grant activates without approval and has a ten minute three reveal budget`() {
        val grant = grant(setOf(SupportPersonalDataField.CUSTOMER_DISPLAY_NAME))

        grant.qualify(VerificationLevel.BASIC, requestedAt.plusSeconds(1))

        assertThat(grant.state).isEqualTo(DataAccessGrantState.ACTIVE)
        assertThat(grant.expiresAt).isEqualTo(requestedAt.plusSeconds(1).plusSeconds(10 * 60))
        repeat(3) { index ->
            grant.reserveReveal(
                setOf(SupportPersonalDataField.CUSTOMER_DISPLAY_NAME),
                binding(),
                requestedAt.plusSeconds(index.toLong() + 2),
            )
        }
        assertThat(grant.state).isEqualTo(DataAccessGrantState.CONSUMED)
    }

    @Test
    fun `sensitive grant requires enhanced verification and a distinct approver`() {
        val grant = grant(setOf(SupportPersonalDataField.CUSTOMER_PRIMARY_PHONE))

        assertThatThrownBy { grant.qualify(VerificationLevel.BASIC, requestedAt.plusSeconds(1)) }
            .isInstanceOf(IllegalStateException::class.java)
        grant.qualify(VerificationLevel.ENHANCED, requestedAt.plusSeconds(1))
        assertThat(grant.state).isEqualTo(DataAccessGrantState.APPROVAL_PENDING)
        assertThatThrownBy { grant.approve(requesterId, requestedAt.plusSeconds(2)) }
            .isInstanceOf(IllegalArgumentException::class.java)

        grant.approve(approverId, requestedAt.plusSeconds(2))

        assertThat(grant.state).isEqualTo(DataAccessGrantState.ACTIVE)
        assertThat(grant.expiresAt).isEqualTo(requestedAt.plusSeconds(2).plusSeconds(5 * 60))
        grant.reserveReveal(setOf(SupportPersonalDataField.CUSTOMER_PRIMARY_PHONE), binding(), requestedAt.plusSeconds(3))
        assertThat(grant.state).isEqualTo(DataAccessGrantState.CONSUMED)
    }

    @Test
    fun `grant rejects fields outside its scope and a different case subject or purpose`() {
        val grant = grant(setOf(SupportPersonalDataField.CUSTOMER_DISPLAY_NAME))
        grant.qualify(VerificationLevel.BASIC, requestedAt.plusSeconds(1))

        assertThatThrownBy {
            grant.reserveReveal(
                setOf(SupportPersonalDataField.CUSTOMER_PRIMARY_EMAIL),
                binding(),
                requestedAt.plusSeconds(2),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            grant.reserveReveal(
                setOf(SupportPersonalDataField.CUSTOMER_DISPLAY_NAME),
                binding(caseId = UUID.randomUUID()),
                requestedAt.plusSeconds(2),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            grant.reserveReveal(
                setOf(SupportPersonalDataField.CUSTOMER_DISPLAY_NAME),
                binding(subjectId = UUID.randomUUID()),
                requestedAt.plusSeconds(2),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            grant.reserveReveal(
                setOf(SupportPersonalDataField.CUSTOMER_DISPLAY_NAME),
                binding(purpose = VerificationPurpose.CONTACT_CONFIRMATION),
                requestedAt.plusSeconds(2),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `grant expiry is inclusive and does not restore consumed budget`() {
        val grant = grant(setOf(SupportPersonalDataField.CUSTOMER_PRIMARY_EMAIL))
        grant.qualify(VerificationLevel.ENHANCED, requestedAt.plusSeconds(1))
        grant.approve(approverId, requestedAt.plusSeconds(2))

        assertThatThrownBy {
            grant.reserveReveal(
                setOf(SupportPersonalDataField.CUSTOMER_PRIMARY_EMAIL),
                binding(),
                requestedAt.plusSeconds(2).plusSeconds(5 * 60),
            )
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(grant.state).isEqualTo(DataAccessGrantState.EXPIRED)
    }

    @Test
    fun `field vocabulary is closed by subject type`() {
        assertThatThrownBy {
            grant(setOf(SupportPersonalDataField.STORE_SUPPORT_PHONE))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun grant(fields: Set<SupportPersonalDataField>): DataAccessGrant =
        DataAccessGrant.request(
            id = UUID.fromString("43000000-0000-0000-0000-000000000003"),
            caseId = BINDING_CASE_ID,
            subjectLinkId = BINDING_LINK_ID,
            subjectType = VerificationSubjectType.CUSTOMER,
            subjectId = BINDING_SUBJECT_ID,
            requesterId = requesterId,
            purpose = VerificationPurpose.CASE_RESOLUTION,
            fields = fields,
            reasonCode = DataAccessReasonCode.CASE_HANDLING,
            requestedAt = requestedAt,
        )

    private fun binding(
        caseId: UUID = BINDING_CASE_ID,
        subjectId: UUID = BINDING_SUBJECT_ID,
        purpose: VerificationPurpose = VerificationPurpose.CASE_RESOLUTION,
    ): DataAccessBinding = DataAccessBinding(caseId, BINDING_LINK_ID, subjectId, purpose)

    private companion object {
        val BINDING_CASE_ID: UUID = UUID.fromString("43000000-0000-0000-0000-000000000004")
        val BINDING_LINK_ID: UUID = UUID.fromString("43000000-0000-0000-0000-000000000005")
        val BINDING_SUBJECT_ID: UUID = UUID.fromString("43000000-0000-0000-0000-000000000006")
    }
}
