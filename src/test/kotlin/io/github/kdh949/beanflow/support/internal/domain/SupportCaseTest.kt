package io.github.kdh949.beanflow.support.internal.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SupportCaseTest {
    private val openedAt = Instant.parse("2026-08-11T00:00:00Z")
    private val caseId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val operatorId = UUID.fromString("20000000-0000-0000-0000-000000000001")

    @Test
    fun `initial transition matrix is enforced by the aggregate`() {
        val supportCase = openCase()

        supportCase.transitionTo(SupportCaseState.IN_PROGRESS, operatorId, openedAt.plusSeconds(1))
        supportCase.transitionTo(SupportCaseState.WAITING, operatorId, openedAt.plusSeconds(2))
        supportCase.transitionTo(SupportCaseState.IN_PROGRESS, operatorId, openedAt.plusSeconds(3))
        supportCase.transitionTo(SupportCaseState.RESOLVED, operatorId, openedAt.plusSeconds(4))
        supportCase.transitionTo(SupportCaseState.CLOSED, operatorId, openedAt.plusSeconds(5))

        assertThat(supportCase.state).isEqualTo(SupportCaseState.CLOSED)
        assertThat(supportCase.version).isEqualTo(5)
        assertThat(supportCase.closedAt).isEqualTo(openedAt.plusSeconds(5))
    }

    @Test
    fun `aggregate rejects transitions outside the accepted initial matrix`() {
        val supportCase = openCase()

        assertThatThrownBy {
            supportCase.transitionTo(SupportCaseState.RESOLVED, operatorId, openedAt.plusSeconds(1))
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `closed case rejects assignment interaction note and subject link mutations`() {
        val supportCase = resolvedAndClosedCase()

        assertThatThrownBy { supportCase.assign(UUID.randomUUID(), operatorId, openedAt.plusSeconds(6)) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { supportCase.requireOpenFor(SupportCaseMutation.INTERACTION) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { supportCase.requireOpenFor(SupportCaseMutation.NOTE) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { supportCase.requireOpenFor(SupportCaseMutation.SUBJECT_LINK) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `other category requires a structured detail`() {
        assertThatThrownBy {
            SupportCase.open(
                id = caseId,
                requesterType = SupportRequesterType.CUSTOMER,
                requesterReference = "customer-reference-001",
                category = SupportInquiryCategory.OTHER,
                priority = SupportCasePriority.NORMAL,
                assigneeId = operatorId,
                reason = "  ",
                openedAt = openedAt,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun resolvedAndClosedCase(): SupportCase =
        openCase().also {
            it.transitionTo(SupportCaseState.IN_PROGRESS, operatorId, openedAt.plusSeconds(1))
            it.transitionTo(SupportCaseState.RESOLVED, operatorId, openedAt.plusSeconds(2))
            it.transitionTo(SupportCaseState.CLOSED, operatorId, openedAt.plusSeconds(3))
        }

    private fun openCase(): SupportCase =
        SupportCase.open(
            id = caseId,
            requesterType = SupportRequesterType.CUSTOMER,
            requesterReference = "customer-reference-001",
            category = SupportInquiryCategory.ORDER_STATUS,
            priority = SupportCasePriority.NORMAL,
            assigneeId = operatorId,
            reason = "ORDER_STATUS_INQUIRY",
            openedAt = openedAt,
        )
}
