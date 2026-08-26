package io.github.kdh949.beanflow.support.internal.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SupportCompensationRequestTest {
    private val now = Instant.parse("2026-08-12T12:00:00Z")
    private val actorId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val actionRequestId = UUID.fromString("10000000-0000-0000-0000-000000000002")

    @Test
    fun `approval route binds one exact action request before execution`() {
        val request = request(SupportActionApprovalRoute.OPERATIONS, actionRequestId)

        assertThat(request.state).isEqualTo(SupportCompensationRequestState.AWAITING_APPROVAL)
        assertThatThrownBy {
            request.completeBenefit(UUID.randomUUID(), actorId, DIGEST, 8, now.plusSeconds(1))
        }.isInstanceOf(IllegalStateException::class.java)

        request.markApprovalReady(actionRequestId, 0, now.plusSeconds(1))
        val change = request.completeBenefit(UUID.randomUUID(), actorId, DIGEST, 8, now.plusSeconds(2))

        assertThat(change.replayed).isFalse()
        assertThat(request.state).isEqualTo(SupportCompensationRequestState.BENEFIT_ISSUED)
    }

    @Test
    fun `one request can only terminate with one exact benefit`() {
        val request = request(SupportActionApprovalRoute.NONE, null)
        val benefitId = UUID.randomUUID()

        val first = request.completeBenefit(benefitId, actorId, DIGEST, 8, now.plusSeconds(1))
        val replay = request.completeBenefit(benefitId, actorId, DIGEST, 8, now.plusSeconds(2))

        assertThat(first.replayed).isFalse()
        assertThat(replay.replayed).isTrue()
        assertThatThrownBy {
            request.completeBenefit(UUID.randomUUID(), actorId, DIGEST, 8, now.plusSeconds(3))
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `stale payload target or executor cannot consume the request`() {
        val request = request(SupportActionApprovalRoute.NONE, null)

        assertThatThrownBy {
            request.completeBenefit(UUID.randomUUID(), UUID.randomUUID(), DIGEST, 8, now.plusSeconds(1))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            request.completeBenefit(UUID.randomUUID(), actorId, "b".repeat(64), 8, now.plusSeconds(1))
        }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy {
            request.completeBenefit(UUID.randomUUID(), actorId, DIGEST, 9, now.plusSeconds(1))
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `notification failure remains visible without reverting the issued benefit`() {
        val request = request(SupportActionApprovalRoute.NONE, null)
        request.completeBenefit(UUID.randomUUID(), actorId, DIGEST, 8, now.plusSeconds(1))

        request.markNotificationRetry("DEPENDENCY_UNAVAILABLE", now.plusSeconds(2))
        assertThat(request.state).isEqualTo(SupportCompensationRequestState.NOTIFICATION_RETRY)
        assertThat(request.notificationFailureCode).isEqualTo("DEPENDENCY_UNAVAILABLE")

        request.completeNotification(UUID.randomUUID(), now.plusSeconds(3))
        assertThat(request.state).isEqualTo(SupportCompensationRequestState.NOTIFICATION_ACCEPTED)
        assertThat(request.terminalBenefitId).isNotNull()
        assertThat(request.notificationFailureCode).isNull()
    }

    @Test
    fun `marketing opt out terminates notification without a delivery binding`() {
        val request = request(SupportActionApprovalRoute.NONE, null)
        request.completeBenefit(UUID.randomUUID(), actorId, DIGEST, 8, now.plusSeconds(1))

        request.skipNotification(now.plusSeconds(2))
        request.skipNotification(now.plusSeconds(3))

        assertThat(request.state).isEqualTo(SupportCompensationRequestState.NOTIFICATION_SKIPPED)
        assertThat(request.terminalBenefitId).isNotNull()
        assertThat(request.notificationDeliveryId).isNull()
        assertThat(request.notificationFailureCode).isNull()
    }

    @Test
    fun `coupon and action bindings are structurally exact`() {
        assertThatThrownBy {
            request(SupportActionApprovalRoute.SUPPORT_MANAGER, null)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            SupportCompensationRequest.open(
                id = UUID.randomUUID(),
                supportCaseId = UUID.randomUUID(),
                customerId = UUID.randomUUID(),
                incidentId = UUID.randomUUID(),
                orderId = UUID.randomUUID(),
                storeId = UUID.randomUUID(),
                requesterActorId = actorId,
                executorActorId = actorId,
                benefitType = SupportCompensationBenefitType.COUPON,
                amountKrw = 3_000,
                couponTemplateId = null,
                policyVersionId = SupportCompensationPolicyVersion.INITIAL_V1_ID,
                band = SupportCompensationBand.LOW,
                route = SupportActionApprovalRoute.NONE,
                verificationSessionId = UUID.randomUUID(),
                targetVersion = 8,
                costSnapshot = SupportCompensationCostSnapshot.platform(),
                payloadDigest = DIGEST,
                evidenceDigest = DIGEST,
                actionRequestId = null,
                createdAt = now,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun request(
        route: SupportActionApprovalRoute,
        approvalRequestId: UUID?,
    ): SupportCompensationRequest =
        SupportCompensationRequest.open(
            id = UUID.randomUUID(),
            supportCaseId = UUID.randomUUID(),
            customerId = UUID.randomUUID(),
            incidentId = UUID.randomUUID(),
            orderId = UUID.randomUUID(),
            storeId = UUID.randomUUID(),
            requesterActorId = actorId,
            executorActorId = actorId,
            benefitType = SupportCompensationBenefitType.POINT,
            amountKrw = 3_000,
            couponTemplateId = null,
            policyVersionId = SupportCompensationPolicyVersion.INITIAL_V1_ID,
            band = if (route == SupportActionApprovalRoute.NONE) SupportCompensationBand.LOW else SupportCompensationBand.HIGH,
            route = route,
            verificationSessionId = UUID.randomUUID(),
            targetVersion = 8,
            costSnapshot = SupportCompensationCostSnapshot.platform(),
            payloadDigest = DIGEST,
            evidenceDigest = DIGEST,
            actionRequestId = approvalRequestId,
            createdAt = now,
        )

    private companion object {
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
