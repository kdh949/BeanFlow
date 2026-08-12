package io.github.kdh949.beanflow.support.internal.domain

import io.github.kdh949.beanflow.shared.api.OwnerProfileChangeResult
import io.github.kdh949.beanflow.shared.api.OwnerProfileNotificationTarget
import io.github.kdh949.beanflow.shared.api.ProfileNotificationChannel
import io.github.kdh949.beanflow.shared.api.ProfileNotificationTargetKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

internal class SupportProfileChangeTest {
    @Test
    fun `R3 and R4 require action request while R1 and R2 execute directly`() {
        val direct = direct(ProfileChangePurpose.CUSTOMER_DISPLAY_NAME)
        val pending = pending(ProfileChangePurpose.CUSTOMER_PRIMARY_PHONE)

        assertEquals(SupportProfileChangeState.EXECUTED, direct.state)
        assertEquals(null, direct.actionRequestId)
        assertEquals(SupportProfileChangeState.AWAITING_APPROVAL, pending.state)
        assertEquals(ACTION_REQUEST_ID, pending.actionRequestId)

        assertThrows<IllegalArgumentException> {
            SupportProfileChange.pending(
                CHANGE_ID,
                CASE_ID,
                SUBJECT_ID,
                ProfileChangePurpose.CUSTOMER_DISPLAY_NAME,
                ACTOR_ID,
                SESSION_ID,
                2,
                DIGEST,
                ACTION_REQUEST_ID,
                NOW,
            )
        }
        assertThrows<IllegalArgumentException> {
            SupportProfileChange.direct(
                CHANGE_ID,
                CASE_ID,
                SUBJECT_ID,
                ProfileChangePurpose.CUSTOMER_CREDENTIAL_RESET,
                ACTOR_ID,
                SESSION_ID,
                2,
                DIGEST,
                result(),
                NOW,
            )
        }
    }

    @Test
    fun `approved change binds exact executor owner version and one terminal outcome`() {
        val change = pending(ProfileChangePurpose.STORE_REPRESENTATIVE)

        assertThrows<IllegalArgumentException> { change.markReady(OTHER_ACTOR_ID, NOW.plusSeconds(1)) }
        change.markReady(ACTOR_ID, NOW.plusSeconds(1))
        assertThrows<IllegalStateException> {
            change.complete(ACTOR_ID, result(previousVersion = 3), NOW.plusSeconds(2))
        }

        change.complete(ACTOR_ID, result(), NOW.plusSeconds(2))

        assertEquals(SupportProfileChangeState.EXECUTED, change.state)
        assertEquals(OWNER_CHANGE_ID, change.ownerChangeId)
        assertEquals(3, change.currentProfileVersion)
        assertEquals(SupportProfileNotificationState.PENDING, change.notificationState)
        assertThrows<IllegalStateException> { change.complete(ACTOR_ID, result(), NOW.plusSeconds(3)) }
    }

    @Test
    fun `notification failure remains explicit after profile execution`() {
        val change = direct(ProfileChangePurpose.CUSTOMER_DISPLAY_NAME)

        change.notificationFailed("PROVIDER_UNAVAILABLE", false, NOW.plusSeconds(1))
        assertEquals(SupportProfileNotificationState.RETRY_SCHEDULED, change.notificationState)
        assertEquals("PROVIDER_UNAVAILABLE", change.notificationFailureCode)

        change.notificationFailed("ATTEMPTS_EXHAUSTED", true, NOW.plusSeconds(2))
        assertEquals(SupportProfileNotificationState.MANUAL_REVIEW, change.notificationState)
    }

    private fun direct(purpose: ProfileChangePurpose) =
        SupportProfileChange.direct(
            CHANGE_ID,
            CASE_ID,
            SUBJECT_ID,
            purpose,
            ACTOR_ID,
            SESSION_ID,
            2,
            DIGEST,
            result(),
            NOW,
        )

    private fun pending(purpose: ProfileChangePurpose) =
        SupportProfileChange.pending(
            CHANGE_ID,
            CASE_ID,
            SUBJECT_ID,
            purpose,
            ACTOR_ID,
            SESSION_ID,
            2,
            DIGEST,
            ACTION_REQUEST_ID,
            NOW,
        )

    private fun result(previousVersion: Long = 2) =
        OwnerProfileChangeResult(
            OWNER_CHANGE_ID,
            previousVersion,
            previousVersion + 1,
            "masked-before",
            "masked-after",
            listOf(
                OwnerProfileNotificationTarget(
                    NOTIFICATION_TARGET_ID,
                    ProfileNotificationTargetKind.CURRENT,
                    ProfileNotificationChannel.EMAIL,
                    "m***@e***.com",
                ),
            ),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-13T01:00:00Z")
        val CHANGE_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000001")
        val CASE_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000002")
        val SUBJECT_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000003")
        val ACTOR_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000004")
        val OTHER_ACTOR_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000005")
        val SESSION_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000006")
        val ACTION_REQUEST_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000007")
        val OWNER_CHANGE_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000008")
        val NOTIFICATION_TARGET_ID: UUID = UUID.fromString("80000000-0000-0000-0000-000000000009")
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
