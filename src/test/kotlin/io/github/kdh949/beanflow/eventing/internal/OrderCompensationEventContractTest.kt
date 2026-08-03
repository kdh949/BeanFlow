package io.github.kdh949.beanflow.eventing.internal

import io.github.kdh949.beanflow.eventing.api.BenefitRestorationPolicySnapshotV1
import io.github.kdh949.beanflow.eventing.api.OrderCancelledV1
import io.github.kdh949.beanflow.eventing.api.OrderRejectedV1
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.reflect.full.memberProperties

internal class OrderCompensationEventContractTest {
    @Test
    fun `OrderCancelledV1 has the minimized frozen field set`() {
        val fields = OrderCancelledV1::class.memberProperties.map { it.name }.toSet()

        assertThat(fields)
            .containsExactlyInAnyOrder(
                "envelope",
                "orderId",
                "cancelledAt",
                "couponRequired",
                "pointsRequired",
                "couponPolicy",
                "pointsPolicy",
            )
        assertThat(fields)
            .doesNotContain("reasonCode", "detail", "customerId", "storeId", "paymentId")
    }

    @Test
    fun `OrderRejectedV1 carries two benefit policy snapshots without legacy singleton fields`() {
        val fields = OrderRejectedV1::class.memberProperties.map { it.name }.toSet()
        assertThat(fields)
            .contains("couponPolicy", "pointsPolicy")
            .doesNotContain("policyVersion", "policyMode", "policyValidityDays")
        assertThat(BenefitRestorationPolicySnapshotV1::class.memberProperties.map { it.name }.toSet())
            .containsExactlyInAnyOrder("policyVersionId", "mode", "compensationValidityDays")
    }
}
