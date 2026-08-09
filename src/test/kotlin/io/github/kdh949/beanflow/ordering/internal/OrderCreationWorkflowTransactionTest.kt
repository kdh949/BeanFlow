package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import io.github.kdh949.beanflow.ordering.api.CreateOrderLineCommand
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.IllegalTransactionStateException
import java.util.UUID

@Import(TestcontainersConfiguration::class)
@SpringBootTest
internal class OrderCreationWorkflowTransactionTest
    @Autowired
    constructor(
        private val workflow: OrderCreationWorkflow,
    ) {
        @Test
        fun `shared order creation workflow cannot open or run outside an existing transaction`() {
            val command =
                CreateOrderCommand(
                    customerId = UUID.randomUUID(),
                    storeId = UUID.randomUUID(),
                    pickupSlotId = UUID.randomUUID(),
                    lines = listOf(CreateOrderLineCommand(UUID.randomUUID(), emptyList(), 1)),
                    couponIssuanceId = null,
                    pointsToUseKrw = 0,
                )

            assertThatThrownBy { workflow.create(UUID.randomUUID(), command) }
                .isInstanceOf(IllegalTransactionStateException::class.java)
        }
    }
