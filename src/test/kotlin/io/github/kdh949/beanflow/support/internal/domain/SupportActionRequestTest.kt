package io.github.kdh949.beanflow.support.internal.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SupportActionRequestTest {
    @Test
    fun `route determines the first review state without client supplied decision`() {
        assertEquals(SupportActionRequestState.READY_FOR_EXECUTION, request(SupportActionApprovalRoute.NONE).state)
        assertEquals(SupportActionRequestState.AWAITING_SUPPORT_MANAGER, request(SupportActionApprovalRoute.SUPPORT_MANAGER).state)
        assertEquals(SupportActionRequestState.AWAITING_OPERATIONS, request(SupportActionApprovalRoute.OPERATIONS).state)
        assertEquals(
            SupportActionRequestState.AWAITING_SUPPORT_MANAGER,
            request(SupportActionApprovalRoute.SUPPORT_MANAGER_THEN_OPERATIONS).state,
        )
    }

    @Test
    fun `requester and execution candidate cannot approve their own revision`() {
        val request = request(SupportActionApprovalRoute.SUPPORT_MANAGER)

        assertThrows<IllegalArgumentException> {
            request.decideSupportManager(REQUESTER, 1, SupportApprovalDecision.APPROVE, NOW.plusSeconds(1))
        }
        assertEquals(SupportActionRequestState.AWAITING_SUPPORT_MANAGER, request.state)
        assertNull(request.supportApproverActorId)
    }

    @Test
    fun `manager and operations reviewers are distinct and neither can execute`() {
        val request = request(SupportActionApprovalRoute.SUPPORT_MANAGER_THEN_OPERATIONS)

        val manager = request.decideSupportManager(MANAGER, 1, SupportApprovalDecision.APPROVE, NOW.plusSeconds(1))

        assertEquals(SupportApprovalStepType.SUPPORT_MANAGER, manager.stepType)
        assertEquals(SupportActionRequestState.AWAITING_OPERATIONS, request.state)
        assertThrows<IllegalArgumentException> {
            request.decideOperations(MANAGER, 1, OperationsInvestigationDecision.APPROVE, NOW.plusSeconds(2))
        }
        assertThrows<IllegalArgumentException> {
            request.decideOperations(REQUESTER, 1, OperationsInvestigationDecision.APPROVE, NOW.plusSeconds(2))
        }

        val operations = request.decideOperations(OPERATIONS, 1, OperationsInvestigationDecision.APPROVE, NOW.plusSeconds(2))

        assertEquals(SupportApprovalStepType.OPERATIONS, operations.stepType)
        assertEquals(SupportActionRequestState.READY_FOR_EXECUTION, request.state)
        assertThrows<IllegalArgumentException> {
            request.reassignExecutor(MANAGER, NOW.plusSeconds(3))
        }
        assertThrows<IllegalArgumentException> {
            request.reassignExecutor(OPERATIONS, NOW.plusSeconds(3))
        }
    }

    @Test
    fun `approval expiry uses a closed exact boundary`() {
        val request = request(SupportActionApprovalRoute.SUPPORT_MANAGER)

        assertThrows<IllegalStateException> {
            request.decideSupportManager(MANAGER, 1, SupportApprovalDecision.APPROVE, EXPIRY)
        }

        val expiry = request.expire(EXPIRY)
        assertEquals(SupportActionRequestState.AWAITING_SUPPORT_MANAGER, expiry.previousState)
        assertEquals(SupportActionRequestState.EXPIRED, request.state)
        assertEquals(SupportApprovalStepState.EXPIRED, expiry.stepState)
    }

    @Test
    fun `material change creates a new revision and stales every unused step`() {
        val request = request(SupportActionApprovalRoute.SUPPORT_MANAGER_THEN_OPERATIONS)
        request.decideSupportManager(MANAGER, 1, SupportApprovalDecision.APPROVE, NOW.plusSeconds(1))

        val change = request.revise(revision(2, PAYLOAD_DIGEST_2), REQUESTER, NOW.plusSeconds(2))

        assertEquals(2, request.currentRevision.revisionNumber)
        assertEquals(SupportActionRequestState.AWAITING_SUPPORT_MANAGER, request.state)
        assertEquals(setOf(SupportApprovalStepType.SUPPORT_MANAGER, SupportApprovalStepType.OPERATIONS), change.staleStepTypes)
        assertNull(request.supportApproverActorId)
        assertNull(request.operationsApproverActorId)
        assertThrows<IllegalStateException> {
            request.decideSupportManager(MANAGER, 1, SupportApprovalDecision.APPROVE, NOW.plusSeconds(3))
        }
    }

    @Test
    fun `operations return requires a new requester revision before review resumes`() {
        val request = request(SupportActionApprovalRoute.OPERATIONS)

        request.decideOperations(OPERATIONS, 1, OperationsInvestigationDecision.RETURN_FOR_REVISION, NOW.plusSeconds(1))

        assertEquals(SupportActionRequestState.REVISION_REQUIRED, request.state)
        assertThrows<IllegalStateException> {
            request.decideOperations(OTHER_OPERATIONS, 1, OperationsInvestigationDecision.APPROVE, NOW.plusSeconds(2))
        }

        request.revise(revision(2, PAYLOAD_DIGEST_2), REQUESTER, NOW.plusSeconds(2))
        assertEquals(SupportActionRequestState.AWAITING_OPERATIONS, request.state)
    }

    @Test
    fun `a decided approval step is one time`() {
        val request = request(SupportActionApprovalRoute.SUPPORT_MANAGER)
        request.decideSupportManager(MANAGER, 1, SupportApprovalDecision.APPROVE, NOW.plusSeconds(1))

        assertThrows<IllegalStateException> {
            request.decideSupportManager(OTHER_MANAGER, 1, SupportApprovalDecision.APPROVE, NOW.plusSeconds(2))
        }
    }

    private fun request(route: SupportActionApprovalRoute): SupportActionRequest =
        SupportActionRequest.open(
            id = REQUEST_ID,
            supportCaseId = CASE_ID,
            requesterActorId = REQUESTER,
            executorActorId = REQUESTER,
            route = route,
            revision = revision(1, PAYLOAD_DIGEST_1),
        )

    private fun revision(
        number: Int,
        payloadDigest: String,
    ) = SupportActionRevision(
        id = UUID.nameUUIDFromBytes("revision-$number".toByteArray()),
        revisionNumber = number,
        action = SupportActionType.ORDER_CANCELLATION,
        targetId = ORDER_ID,
        actionPayloadDigest = payloadDigest,
        verificationSessionId = SESSION_ID,
        policyVersion = SupportActionPolicy.POLICY_VERSION,
        targetVersion = 7,
        amountKrw = null,
        reason = "Customer requested cancellation",
        evidenceDigest = EVIDENCE_DIGEST,
        expiresAt = EXPIRY,
        createdByActorId = REQUESTER,
        createdAt = NOW,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-12T01:00:00Z")
        val EXPIRY: Instant = NOW.plusSeconds(900)
        val REQUEST_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val CASE_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000002")
        val ORDER_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000003")
        val SESSION_ID: UUID = UUID.fromString("10000000-0000-0000-0000-000000000004")
        val REQUESTER: UUID = UUID.fromString("10000000-0000-0000-0000-000000000005")
        val MANAGER: UUID = UUID.fromString("10000000-0000-0000-0000-000000000006")
        val OTHER_MANAGER: UUID = UUID.fromString("10000000-0000-0000-0000-000000000007")
        val OPERATIONS: UUID = UUID.fromString("10000000-0000-0000-0000-000000000008")
        val OTHER_OPERATIONS: UUID = UUID.fromString("10000000-0000-0000-0000-000000000009")
        const val PAYLOAD_DIGEST_1 = "1111111111111111111111111111111111111111111111111111111111111111"
        const val PAYLOAD_DIGEST_2 = "2222222222222222222222222222222222222222222222222222222222222222"
        const val EVIDENCE_DIGEST = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
    }
}
