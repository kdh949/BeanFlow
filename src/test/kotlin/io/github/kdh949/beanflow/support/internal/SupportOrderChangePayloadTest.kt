package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.ordering.api.CustomerCancellationReasonCode
import io.github.kdh949.beanflow.support.internal.domain.SupportActionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

internal class SupportOrderChangePayloadTest {
    @Test
    fun `typed order commands match browser canonical test vectors`() {
        val orderId = UUID.fromString("74000000-0000-4000-8000-000000000001")
        val slotId = UUID.fromString("74000000-0000-4000-8000-000000000002")
        val canonicalizer = SupportOrderChangePayloadCanonicalizer(SupportCommandPayloadCanonicalizer())
        val cancel =
            ExecuteSupportOrderChangeCommand(
                orderId,
                orderId,
                SupportActionType.ORDER_CANCELLATION,
                1,
                0,
                0,
                CustomerCancellationReasonCode.CHANGED_MIND,
                null,
                null,
                "payload-vector-001",
            )
        assertThat(
            canonicalizer.actionDigest(cancel, orderId),
        ).isEqualTo("a6751d0986f09a852783b5b3b49c58b1d49f4517cbfcccbfba3f27132b2542cf")
        assertThat(
            canonicalizer.actionDigest(
                cancel.copy(action = SupportActionType.PICKUP_RESCHEDULE, cancellationReasonCode = null, newPickupSlotId = slotId),
                orderId,
            ),
        ).isEqualTo("9d55ae151607f8deaae9e583267419677a22fd9a972829b151af3efc82a3435f")
    }
}
