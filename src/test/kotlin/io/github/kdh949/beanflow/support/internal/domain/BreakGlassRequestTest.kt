package io.github.kdh949.beanflow.support.internal.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BreakGlassRequestTest {
    private val requestedAt = Instant.parse("2026-08-12T00:00:00Z")
    private val requesterId = UUID.fromString("46000000-0000-0000-0000-000000000001")
    private val approverId = UUID.fromString("46000000-0000-0000-0000-000000000002")
    private val reviewerId = UUID.fromString("46000000-0000-0000-0000-000000000003")

    @Test
    fun `break glass is a separate one-field two-minute one-reveal path`() {
        val request = request()

        assertThatThrownBy { request.approve(requesterId, requestedAt.plusSeconds(1)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        request.approve(approverId, requestedAt.plusSeconds(1))
        assertThat(request.state).isEqualTo(BreakGlassState.ACTIVE)
        assertThat(request.expiresAt).isEqualTo(requestedAt.plusSeconds(121))

        request.reserveReveal(requesterId, binding(), SupportPersonalDataField.CUSTOMER_PRIMARY_PHONE, requestedAt.plusSeconds(2))
        assertThat(request.state).isEqualTo(BreakGlassState.REVIEW_PENDING)
        assertThatThrownBy {
            request.reserveReveal(requesterId, binding(), SupportPersonalDataField.CUSTOMER_PRIMARY_PHONE, requestedAt.plusSeconds(3))
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `break glass rejects another actor field case subject and purpose`() {
        val request = request()
        request.approve(approverId, requestedAt.plusSeconds(1))

        assertThatThrownBy {
            request.reserveReveal(UUID.randomUUID(), binding(), SupportPersonalDataField.CUSTOMER_PRIMARY_PHONE, requestedAt.plusSeconds(2))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            request.reserveReveal(
                requesterId,
                binding(caseId = UUID.randomUUID()),
                SupportPersonalDataField.CUSTOMER_PRIMARY_PHONE,
                requestedAt.plusSeconds(2),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            request.reserveReveal(
                requesterId,
                binding(subjectId = UUID.randomUUID()),
                SupportPersonalDataField.CUSTOMER_PRIMARY_PHONE,
                requestedAt.plusSeconds(2),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            request.reserveReveal(
                requesterId,
                binding(purpose = VerificationPurpose.PRIVACY_INCIDENT),
                SupportPersonalDataField.CUSTOMER_PRIMARY_PHONE,
                requestedAt.plusSeconds(2),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            request.reserveReveal(requesterId, binding(), SupportPersonalDataField.CUSTOMER_PRIMARY_EMAIL, requestedAt.plusSeconds(2))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `post review actor must differ from requester and pre approver`() {
        val request = request()
        request.approve(approverId, requestedAt.plusSeconds(1))
        request.reserveReveal(requesterId, binding(), SupportPersonalDataField.CUSTOMER_PRIMARY_PHONE, requestedAt.plusSeconds(2))

        assertThatThrownBy { request.review(requesterId, BreakGlassReviewDecision.CONFIRMED, requestedAt.plusSeconds(3)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { request.review(approverId, BreakGlassReviewDecision.CONFIRMED, requestedAt.plusSeconds(3)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        request.review(reviewerId, BreakGlassReviewDecision.CONFIRMED, requestedAt.plusSeconds(3))

        assertThat(request.state).isEqualTo(BreakGlassState.REVIEWED)
        assertThat(request.reviewerId).isEqualTo(reviewerId)
    }

    @Test
    fun `break glass expiry is inclusive`() {
        val request = request()
        request.approve(approverId, requestedAt.plusSeconds(1))

        assertThatThrownBy {
            request.reserveReveal(
                requesterId,
                binding(),
                SupportPersonalDataField.CUSTOMER_PRIMARY_PHONE,
                requestedAt.plusSeconds(121),
            )
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(request.state).isEqualTo(BreakGlassState.EXPIRED)
    }

    private fun request(): BreakGlassRequest =
        BreakGlassRequest.request(
            id = UUID.fromString("46000000-0000-0000-0000-000000000004"),
            caseId = CASE_ID,
            subjectLinkId = LINK_ID,
            subjectType = VerificationSubjectType.CUSTOMER,
            subjectId = SUBJECT_ID,
            requesterId = requesterId,
            field = SupportPersonalDataField.CUSTOMER_PRIMARY_PHONE,
            purpose = VerificationPurpose.SAFETY_RESPONSE,
            reasonCode = BreakGlassReasonCode.IMMEDIATE_SAFETY,
            requestedAt = requestedAt,
        )

    private fun binding(
        caseId: UUID = CASE_ID,
        subjectId: UUID = SUBJECT_ID,
        purpose: VerificationPurpose = VerificationPurpose.SAFETY_RESPONSE,
    ): DataAccessBinding = DataAccessBinding(caseId, LINK_ID, subjectId, purpose)

    private companion object {
        val CASE_ID: UUID = UUID.fromString("46000000-0000-0000-0000-000000000005")
        val LINK_ID: UUID = UUID.fromString("46000000-0000-0000-0000-000000000006")
        val SUBJECT_ID: UUID = UUID.fromString("46000000-0000-0000-0000-000000000007")
    }
}
