package io.github.kdh949.beanflow.support.internal.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class VerificationSessionTest {
    private val startedAt = Instant.parse("2026-08-12T00:00:00Z")
    private val sessionId = UUID.fromString("41000000-0000-0000-0000-000000000001")
    private val caseId = UUID.fromString("41000000-0000-0000-0000-000000000002")
    private val subjectLinkId = UUID.fromString("41000000-0000-0000-0000-000000000003")
    private val subjectId = UUID.fromString("41000000-0000-0000-0000-000000000004")
    private val actorId = UUID.fromString("41000000-0000-0000-0000-000000000005")

    @Test
    fun `basic verification requires one registered channel and remains bound to its case subject and purpose`() {
        val session = session(VerificationLevel.BASIC)

        assertThat(session.recordVerifiedChannel(VerificationChannel.REGISTERED_PHONE, startedAt.plusSeconds(30)))
            .isEqualTo(VerificationState.VERIFIED)
        assertThat(session.achievedLevel).isEqualTo(VerificationLevel.BASIC)
        assertThat(session.matches(caseId, subjectLinkId, subjectId, VerificationPurpose.CASE_RESOLUTION)).isTrue()
        assertThat(session.matches(UUID.randomUUID(), subjectLinkId, subjectId, VerificationPurpose.CASE_RESOLUTION)).isFalse()
        assertThat(session.matches(caseId, subjectLinkId, UUID.randomUUID(), VerificationPurpose.CASE_RESOLUTION)).isFalse()
        assertThat(session.matches(caseId, subjectLinkId, subjectId, VerificationPurpose.CONTACT_CONFIRMATION)).isFalse()
    }

    @Test
    fun `enhanced verification needs two distinct registered channel types`() {
        val session = session(VerificationLevel.ENHANCED)

        assertThat(session.recordVerifiedChannel(VerificationChannel.REGISTERED_PHONE, startedAt.plusSeconds(10)))
            .isEqualTo(VerificationState.PENDING)
        assertThat(session.recordVerifiedChannel(VerificationChannel.REGISTERED_PHONE, startedAt.plusSeconds(20)))
            .isEqualTo(VerificationState.PENDING)
        assertThat(session.recordVerifiedChannel(VerificationChannel.REGISTERED_EMAIL, startedAt.plusSeconds(30)))
            .isEqualTo(VerificationState.VERIFIED)
        assertThat(session.achievedLevel).isEqualTo(VerificationLevel.ENHANCED)
    }

    @Test
    fun `fifth invalid attempt locks the exact binding for thirty minutes`() {
        val session = session(VerificationLevel.BASIC)

        repeat(4) { index ->
            assertThat(session.recordInvalidAttempt(startedAt.plusSeconds(index.toLong() + 1))).isNull()
        }
        val lockedUntil = session.recordInvalidAttempt(startedAt.plusSeconds(5))

        assertThat(session.state).isEqualTo(VerificationState.LOCKED)
        assertThat(lockedUntil).isEqualTo(startedAt.plusSeconds(5).plusSeconds(30 * 60))
        assertThat(session.invalidAttempts).isEqualTo(5)
    }

    @Test
    fun `session expiry is inclusive and terminal sessions reject replay`() {
        val session = session(VerificationLevel.BASIC)

        assertThatThrownBy {
            session.recordVerifiedChannel(VerificationChannel.REGISTERED_PHONE, startedAt.plusSeconds(15 * 60))
        }.isInstanceOf(IllegalStateException::class.java)
        assertThat(session.state).isEqualTo(VerificationState.EXPIRED)

        assertThatThrownBy {
            session.recordVerifiedChannel(VerificationChannel.REGISTERED_EMAIL, startedAt.plusSeconds(15 * 60 + 1))
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `verified session can satisfy basic but basic cannot satisfy enhanced`() {
        val basic = session(VerificationLevel.BASIC)
        basic.recordVerifiedChannel(VerificationChannel.IN_APP, startedAt.plusSeconds(1))

        assertThat(basic.satisfies(VerificationLevel.BASIC, startedAt.plusSeconds(2))).isTrue()
        assertThat(basic.satisfies(VerificationLevel.ENHANCED, startedAt.plusSeconds(2))).isFalse()
    }

    @Test
    fun `support action scope is accepted only for case resolution purpose`() {
        val actionSession = session(VerificationLevel.BASIC, VerificationActionScope.SUPPORT_ACTION)

        assertThat(actionSession.actionScope).isEqualTo(VerificationActionScope.SUPPORT_ACTION)
        assertThatThrownBy {
            VerificationSession.start(
                id = UUID.randomUUID(),
                caseId = caseId,
                subjectLinkId = subjectLinkId,
                subjectType = VerificationSubjectType.CUSTOMER,
                subjectId = subjectId,
                actorId = actorId,
                purpose = VerificationPurpose.CONTACT_CONFIRMATION,
                actionScope = VerificationActionScope.SUPPORT_ACTION,
                requestedLevel = VerificationLevel.BASIC,
                startedAt = startedAt,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun session(
        level: VerificationLevel,
        actionScope: VerificationActionScope = VerificationActionScope.PERSONAL_DATA_REVEAL,
    ): VerificationSession =
        VerificationSession.start(
            id = sessionId,
            caseId = caseId,
            subjectLinkId = subjectLinkId,
            subjectType = VerificationSubjectType.CUSTOMER,
            subjectId = subjectId,
            actorId = actorId,
            purpose = VerificationPurpose.CASE_RESOLUTION,
            actionScope = actionScope,
            requestedLevel = level,
            startedAt = startedAt,
        )
}
