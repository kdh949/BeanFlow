package io.github.kdh949.beanflow.support.internal.domain

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

class SupportOrderChangeAuthorizationTest {
    @Test
    fun `exact confirmation binds request revision payload target version and request expiry`() {
        val authorization =
            SupportOrderChangeAuthorization.confirmation(
                AUTHORIZATION_ID,
                STORE_ID,
                SupportActionType.ORDER_CANCELLATION,
                REQUEST_ID,
                3,
                PAYLOAD_DIGEST,
                9,
                NOW.plusSeconds(300),
                STORE_ACTOR_ID,
                NOW,
                SupportOrderChangeCostResponsibility.STORE,
            )

        assertThrows<IllegalArgumentException> {
            authorization.consume(command(action = SupportActionType.PICKUP_RESCHEDULE), NOW.plusSeconds(1))
        }
        assertThrows<IllegalArgumentException> {
            authorization.consume(command(payloadDigest = OTHER_PAYLOAD_DIGEST), NOW.plusSeconds(1))
        }
        assertThrows<IllegalStateException> { authorization.consume(command(), NOW.plusSeconds(300)) }

        val first = authorization.consume(command(), NOW.plusSeconds(1))
        val replay = authorization.consume(command(), NOW.plusSeconds(2))

        assertEquals(SupportOrderChangeAuthorizationConsumption.APPLIED, first)
        assertEquals(SupportOrderChangeAuthorizationConsumption.ALREADY_APPLIED, replay)
        assertEquals(1, authorization.successfulUses)
    }

    @Test
    fun `delegation has immutable action policy expiry and successful use budget`() {
        val authorization =
            SupportOrderChangeAuthorization.delegation(
                AUTHORIZATION_ID,
                STORE_ID,
                SupportActionType.PICKUP_RESCHEDULE,
                SupportOrderChangeAuthorization.INITIAL_POLICY_VERSION,
                STORE_ACTOR_ID,
                NOW,
                SupportOrderChangeCostResponsibility.STORE,
            )

        repeat(3) { index ->
            assertEquals(
                SupportOrderChangeAuthorizationConsumption.APPLIED,
                authorization.consume(
                    command(
                        executionId = UUID.nameUUIDFromBytes("execution-$index".toByteArray()),
                        action = SupportActionType.PICKUP_RESCHEDULE,
                        requestId = UUID.nameUUIDFromBytes("request-$index".toByteArray()),
                    ),
                    NOW.plusSeconds(index.toLong() + 1),
                ),
            )
        }
        assertThrows<IllegalStateException> {
            authorization.consume(
                command(
                    executionId = UUID.nameUUIDFromBytes("execution-4".toByteArray()),
                    action = SupportActionType.PICKUP_RESCHEDULE,
                    requestId = UUID.nameUUIDFromBytes("request-4".toByteArray()),
                ),
                NOW.plusSeconds(4),
            )
        }
        assertEquals(3, authorization.successfulUses)
    }

    private fun command(
        executionId: UUID = EXECUTION_ID,
        action: SupportActionType = SupportActionType.ORDER_CANCELLATION,
        requestId: UUID = REQUEST_ID,
        payloadDigest: String = PAYLOAD_DIGEST,
    ) = ConsumeSupportOrderChangeAuthorizationCommand(executionId, STORE_ID, action, requestId, 3, payloadDigest, 9)

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-12T01:00:00Z")
        val AUTHORIZATION_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
        val STORE_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")
        val REQUEST_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000003")
        val EXECUTION_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000004")
        val STORE_ACTOR_ID: UUID = UUID.fromString("20000000-0000-0000-0000-000000000005")
        const val PAYLOAD_DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_PAYLOAD_DIGEST = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
