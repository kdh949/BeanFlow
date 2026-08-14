package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Service
internal class PublicOrderReferenceService(
    private val orders: OrderJpaRepository,
    private val cancellations: CustomerCancellationService,
    private val storeOrders: StoreOrderTransitionService,
    private val storeAccess: StoreAccessOperations,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) {
    fun cancelCustomerOrder(
        customerId: UUID,
        rawReference: String,
        idempotencyKey: String,
        request: CustomerCancellationRequest,
    ): CustomerCancellationHttpResult {
        val resolved = resolveCustomer(customerId, rawReference)
        val result = cancellations.cancel(customerId, resolved.orderId, idempotencyKey, request)
        val stored = objectMapper.readValue(result.body, CustomerCancellationResponse::class.java)
        return CustomerCancellationHttpResult(
            status = result.status,
            body =
                objectMapper.writeValueAsString(
                    PublicCustomerCancellationResponse(
                        publicReference = resolved.reference.value,
                        orderState = stored.orderState,
                        reasonCode = stored.reasonCode,
                        paymentRecovery = stored.paymentRecovery,
                        cancelledAt = stored.cancelledAt,
                        correlationId = stored.correlationId,
                    ),
                ),
        )
    }

    fun getStoreOrder(
        actor: StoreTransitionActor,
        storeId: UUID,
        rawReference: String,
    ): PublicStoreOrderResult {
        val resolved = resolveStore(storeId, rawReference)
        return storeOrders.get(actor, resolved.orderId).toPublicResponse()
    }

    fun transitionStoreOrder(
        actor: StoreTransitionActor,
        storeId: UUID,
        rawReference: String,
        idempotencyKey: String,
        request: StoreOrderTransitionRequest,
    ): StoreTransitionHttpResult {
        val resolved = resolveStore(storeId, rawReference)
        val result = storeOrders.transition(actor, resolved.orderId, idempotencyKey, request)
        val stored = objectMapper.readValue(result.body, StoreOrderResult::class.java)
        return StoreTransitionHttpResult(result.status, objectMapper.writeValueAsString(stored.toPublicResponse()))
    }

    fun transitionStoreOrderBoard(
        actor: StoreTransitionActor,
        storeId: UUID,
        rawReference: String,
        idempotencyKey: String,
        request: StoreOrderActionRequest,
    ): StoreTransitionHttpResult {
        storeAccess.requireOrderManagementAccess(actor.actorId, storeId, actor.roles)
        val resolved = resolveStore(storeId, rawReference)
        return storeOrders.transitionBoard(actor, resolved.orderId, idempotencyKey, request)
    }

    @Transactional(readOnly = true)
    fun resolveCustomer(
        customerId: UUID,
        rawReference: String,
    ): ResolvedPublicOrder {
        val reference = PublicOrderReference.parse(rawReference)
        val order = orders.findByPublicReferenceAndCustomerId(reference.value, customerId)
        if (order != null) return ResolvedPublicOrder(order.id, reference)
        failLookup(reference, "customer")
    }

    @Transactional(readOnly = true)
    fun resolveStore(
        storeId: UUID,
        rawReference: String,
    ): ResolvedPublicOrder {
        val reference = PublicOrderReference.parse(rawReference)
        val order = orders.findByPublicReferenceAndStoreId(reference.value, storeId)
        if (order != null) return ResolvedPublicOrder(order.id, reference)
        failLookup(reference, "store")
    }

    private fun failLookup(
        reference: PublicOrderReference,
        scope: String,
    ): Nothing {
        val exists = orders.existsByPublicReference(reference.value)
        val outcome = if (exists) "forbidden" else "not_found"
        meterRegistry.counter("beanflow.order.public_reference.lookup.count", "scope", scope, "outcome", outcome).increment()
        if (exists) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Order is outside the requested ownership scope")
        }
        throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
    }
}

internal data class ResolvedPublicOrder(
    val orderId: UUID,
    val reference: PublicOrderReference,
)
