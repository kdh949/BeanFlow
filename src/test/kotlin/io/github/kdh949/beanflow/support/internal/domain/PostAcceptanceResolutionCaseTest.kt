package io.github.kdh949.beanflow.support.internal.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PostAcceptanceResolutionCaseTest {
    @Test
    fun `resolution plan accepts only post-acceptance order facts and exact financial shapes`() {
        assertThrows<IllegalArgumentException> { resolution(triggerState = "ACCEPTED") }
        assertThrows<IllegalArgumentException> {
            resolution(
                plan =
                    plan(
                        outcome = PostAcceptanceResolutionOutcome.NO_MONETARY_RESOLUTION,
                        cashRefundKrw = 1,
                    ),
            )
        }
        assertThrows<IllegalArgumentException> {
            resolution(
                plan =
                    plan(
                        responsibility = PostAcceptanceResolutionResponsibility.PLATFORM,
                        settlementAdjustmentKrw = -1,
                    ),
            )
        }
    }

    @Test
    fun `undetermined responsibility progresses customer value but blocks settlement attribution`() {
        val resolution =
            resolution(
                plan =
                    plan(
                        responsibility = PostAcceptanceResolutionResponsibility.UNDETERMINED,
                        restorePoints = true,
                        restoreCoupon = true,
                    ),
            )

        assertEquals(PostAcceptanceResolutionStepState.PENDING, resolution.step(PostAcceptanceResolutionStepType.PAYMENT_REFUND).state)
        assertEquals(PostAcceptanceResolutionStepState.PENDING, resolution.step(PostAcceptanceResolutionStepType.POINT_RESTORATION).state)
        assertEquals(PostAcceptanceResolutionStepState.PENDING, resolution.step(PostAcceptanceResolutionStepType.COUPON_RESTORATION).state)
        assertEquals(PostAcceptanceResolutionStepState.BLOCKED, resolution.step(PostAcceptanceResolutionStepType.SETTLEMENT_ADJUSTMENT).state)

        succeed(resolution, PostAcceptanceResolutionStepType.PAYMENT_REFUND, "refund-1", 1)
        succeed(resolution, PostAcceptanceResolutionStepType.POINT_RESTORATION, "points-1", 2)
        succeed(resolution, PostAcceptanceResolutionStepType.COUPON_RESTORATION, "coupon-1", 3)

        assertEquals(PostAcceptanceResolutionState.PARTIALLY_RESOLVED, resolution.state)
        assertFalse(resolution.isFinanciallyResolved())
    }

    @Test
    fun `provider unknown remains reconciling and cannot be reported as resolved`() {
        val resolution = resolution()
        val claim = resolution.claim(PostAcceptanceResolutionStepType.PAYMENT_REFUND, CLAIM_TOKEN, NOW, Duration.ofMinutes(1))

        resolution.recordUnknown(
            PostAcceptanceResolutionStepType.PAYMENT_REFUND,
            claim.claimToken,
            "PROVIDER_TIMEOUT",
            NOW.plusSeconds(1),
            NOW.plusSeconds(31),
        )

        assertEquals(PostAcceptanceResolutionStepState.UNKNOWN, resolution.step(PostAcceptanceResolutionStepType.PAYMENT_REFUND).state)
        assertEquals(PostAcceptanceResolutionState.RECONCILING, resolution.state)
        assertFalse(resolution.isFinanciallyResolved())

        val lookup =
            resolution.claim(
                PostAcceptanceResolutionStepType.PAYMENT_REFUND,
                LOOKUP_TOKEN,
                NOW.plusSeconds(31),
                Duration.ofMinutes(1),
            )
        assertTrue(lookup.reconciliation)
        assertEquals(PostAcceptanceResolutionStepState.RECONCILING, resolution.step(PostAcceptanceResolutionStepType.PAYMENT_REFUND).state)
    }

    @Test
    fun `partial owner success is preserved when a later required step needs manual review`() {
        val resolution =
            resolution(
                plan =
                    plan(
                        responsibility = PostAcceptanceResolutionResponsibility.PLATFORM,
                        restorePoints = true,
                    ),
            )

        succeed(resolution, PostAcceptanceResolutionStepType.PAYMENT_REFUND, "refund-1", 1)
        manualReview(resolution, PostAcceptanceResolutionStepType.POINT_RESTORATION, "SOURCE_MISSING", 2)

        assertEquals(PostAcceptanceResolutionStepState.SUCCEEDED, resolution.step(PostAcceptanceResolutionStepType.PAYMENT_REFUND).state)
        assertEquals(PostAcceptanceResolutionState.PARTIALLY_RESOLVED, resolution.state)
        assertThrows<IllegalStateException> {
            resolution.claim(PostAcceptanceResolutionStepType.PAYMENT_REFUND, UUID.randomUUID(), NOW.plusSeconds(3), Duration.ofMinutes(1))
        }
    }

    @Test
    fun `same owner result replays but a different result conflicts`() {
        val resolution = resolution()
        val claim = resolution.claim(PostAcceptanceResolutionStepType.PAYMENT_REFUND, CLAIM_TOKEN, NOW, Duration.ofMinutes(1))

        val first =
            resolution.recordSuccess(
                PostAcceptanceResolutionStepType.PAYMENT_REFUND,
                claim.claimToken,
                "refund-1",
                NOW.plusSeconds(1),
            )
        val replay =
            resolution.recordSuccess(
                PostAcceptanceResolutionStepType.PAYMENT_REFUND,
                claim.claimToken,
                "refund-1",
                NOW.plusSeconds(2),
            )

        assertFalse(first.replayed)
        assertTrue(replay.replayed)
        assertThrows<IllegalStateException> {
            resolution.recordSuccess(
                PostAcceptanceResolutionStepType.PAYMENT_REFUND,
                claim.claimToken,
                "refund-2",
                NOW.plusSeconds(2),
            )
        }
    }

    @Test
    fun `notification is independent from financial completion`() {
        val resolution = resolution()

        succeed(resolution, PostAcceptanceResolutionStepType.PAYMENT_REFUND, "refund-1", 1)
        assertEquals(PostAcceptanceResolutionState.RESOLVED, resolution.state)
        assertEquals(PostAcceptanceResolutionStepState.PENDING, resolution.step(PostAcceptanceResolutionStepType.CUSTOMER_NOTIFICATION).state)

        manualReview(resolution, PostAcceptanceResolutionStepType.CUSTOMER_NOTIFICATION, "DELIVERY_EXHAUSTED", 2)
        assertEquals(PostAcceptanceResolutionState.RESOLVED, resolution.state)
        assertTrue(resolution.isFinanciallyResolved())
    }

    @Test
    fun `expired processing claim becomes unknown and is reconciled instead of reissued`() {
        val resolution = resolution()
        resolution.claim(PostAcceptanceResolutionStepType.PAYMENT_REFUND, CLAIM_TOKEN, NOW, Duration.ofMinutes(1))

        assertThrows<IllegalStateException> {
            resolution.recoverExpiredClaim(PostAcceptanceResolutionStepType.PAYMENT_REFUND, NOW.plusSeconds(59))
        }

        resolution.recoverExpiredClaim(PostAcceptanceResolutionStepType.PAYMENT_REFUND, NOW.plusSeconds(60))
        assertEquals(PostAcceptanceResolutionStepState.UNKNOWN, resolution.step(PostAcceptanceResolutionStepType.PAYMENT_REFUND).state)
        assertEquals(PostAcceptanceResolutionState.RECONCILING, resolution.state)
    }

    @Test
    fun `execution start makes a no monetary plan terminal so notification can proceed`() {
        val resolution =
            resolution(
                plan =
                    plan(
                        outcome = PostAcceptanceResolutionOutcome.NO_MONETARY_RESOLUTION,
                        cashRefundKrw = 0,
                    ),
            )

        resolution.start(NOW.plusSeconds(1))

        assertEquals(PostAcceptanceResolutionState.RESOLVED, resolution.state)
        assertEquals(PostAcceptanceResolutionStepState.PENDING, resolution.step(PostAcceptanceResolutionStepType.CUSTOMER_NOTIFICATION).state)
    }

    @Test
    fun `operator can explicitly move a payment manual review into reconciliation`() {
        val resolution = resolution()
        manualReview(resolution, PostAcceptanceResolutionStepType.PAYMENT_REFUND, "PROVIDER_REVIEW", 1)

        resolution.scheduleManualReconciliation(PostAcceptanceResolutionStepType.PAYMENT_REFUND, NOW.plusSeconds(3))
        val claim =
            resolution.claim(
                PostAcceptanceResolutionStepType.PAYMENT_REFUND,
                LOOKUP_TOKEN,
                NOW.plusSeconds(3),
                Duration.ofMinutes(1),
            )

        assertTrue(claim.reconciliation)
        assertEquals(PostAcceptanceResolutionState.RECONCILING, resolution.state)
    }

    private fun succeed(
        resolution: PostAcceptanceResolutionCase,
        type: PostAcceptanceResolutionStepType,
        reference: String,
        second: Long,
    ) {
        val token = UUID.nameUUIDFromBytes("$type-$second".toByteArray())
        val claim = resolution.claim(type, token, NOW.plusSeconds(second), Duration.ofMinutes(1))
        resolution.recordSuccess(type, claim.claimToken, reference, NOW.plusSeconds(second + 1))
    }

    private fun manualReview(
        resolution: PostAcceptanceResolutionCase,
        type: PostAcceptanceResolutionStepType,
        code: String,
        second: Long,
    ) {
        val token = UUID.nameUUIDFromBytes("$type-$second".toByteArray())
        val claim = resolution.claim(type, token, NOW.plusSeconds(second), Duration.ofMinutes(1))
        resolution.recordManualReview(type, claim.claimToken, code, NOW.plusSeconds(second + 1))
    }

    private fun resolution(
        triggerState: String = "PREPARING",
        plan: PostAcceptanceResolutionPlan = plan(),
    ): PostAcceptanceResolutionCase =
        PostAcceptanceResolutionCase.plan(
            id = RESOLUTION_ID,
            supportCaseId = CASE_ID,
            supportActionRequestId = REQUEST_ID,
            supportActionRevisionId = REVISION_ID,
            revisionNumber = 1,
            actionPayloadDigest = DIGEST,
            orderId = ORDER_ID,
            triggerOrderState = triggerState,
            triggerOrderVersion = 7,
            requesterActorId = REQUESTER_ID,
            executorActorId = EXECUTOR_ID,
            plan = plan,
            createdAt = NOW,
        )

    private fun plan(
        outcome: PostAcceptanceResolutionOutcome = PostAcceptanceResolutionOutcome.FULL_REFUND,
        responsibility: PostAcceptanceResolutionResponsibility = PostAcceptanceResolutionResponsibility.PLATFORM,
        cashRefundKrw: Long = 3_000,
        restorePoints: Boolean = false,
        restoreCoupon: Boolean = false,
        settlementAdjustmentKrw: Long? = null,
    ) = PostAcceptanceResolutionPlan(
        outcome = outcome,
        responsibility = responsibility,
        cashRefundKrw = cashRefundKrw,
        restorePoints = restorePoints,
        restoreCoupon = restoreCoupon,
        settlementAdjustmentKrw = settlementAdjustmentKrw,
        evidenceDigest = DIGEST,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-12T02:00:00Z")
        val RESOLUTION_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000001")
        val CASE_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000002")
        val REQUEST_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000003")
        val REVISION_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000004")
        val ORDER_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000005")
        val REQUESTER_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000006")
        val EXECUTOR_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000007")
        val CLAIM_TOKEN: UUID = UUID.fromString("80000000-0000-0000-0000-000000000008")
        val LOOKUP_TOKEN: UUID = UUID.fromString("80000000-0000-0000-0000-000000000009")
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
