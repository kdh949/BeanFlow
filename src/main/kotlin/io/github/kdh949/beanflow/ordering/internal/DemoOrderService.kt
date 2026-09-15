package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import io.github.kdh949.beanflow.ordering.api.CreateOrderLineCommand
import io.github.kdh949.beanflow.ordering.api.DemoOrderOperations
import io.github.kdh949.beanflow.ordering.api.DemoOrderSnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@ConditionalOnProperty(name = ["beanflow.demo.enabled"], havingValue = "true")
@Transactional(propagation = Propagation.MANDATORY)
internal class DemoOrderService(
    private val workflow: OrderCreationWorkflow,
    private val quotes: OrderQuoteCoordinator,
    private val queries: CustomerOrderQueryService,
    private val repository: OrderJpaRepository,
) : DemoOrderOperations {
    override fun createSample(
        customerId: UUID,
        storeId: UUID,
        menuId: UUID,
        slotId: UUID,
    ): DemoOrderSnapshot {
        val command =
            CreateOrderCommand(
                customerId,
                storeId,
                slotId,
                listOf(CreateOrderLineCommand(menuId, emptyList(), 1)),
                null,
                4500,
            )
        val prepared = quotes.lockForOrderCreation(command)
        val order = workflow.create(UUID.randomUUID(), command, preparedQuote = prepared).order
        check(order.state.name == "PAID")
        return DemoOrderSnapshot(
            order.displayIdentity.publicReference,
            order.state.name,
            order.displayIdentity.pickupNumber,
            order.displayIdentity.pickupWindowStart,
            order.acceptanceDeadlineAt,
        )
    }

    override fun inspect(
        customerId: UUID,
        storeId: UUID,
        reference: String,
    ): DemoOrderSnapshot {
        val order = queries.detail(customerId, reference)
        if (order.storeId != storeId) throw DomainFailure(FailureCode.ACCESS_DENIED, "Order is outside demo workspace")
        return DemoOrderSnapshot(
            order.orderReference,
            order.status,
            order.pickupNumber,
            order.pickupWindowStart,
            repository.findByPublicReferenceAndCustomerId(reference, customerId)?.acceptanceDeadlineAt,
        )
    }
}
