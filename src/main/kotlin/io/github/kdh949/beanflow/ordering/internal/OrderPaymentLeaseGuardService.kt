package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.OrderPaymentLeaseGuard
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
internal class OrderPaymentLeaseGuardService(
	private val expiryUseCase: ReservationExpiryUseCase,
	private val orderRepository: OrderJpaRepository,
) : OrderPaymentLeaseGuard {

	override fun requireEligible(customerId: UUID, orderId: UUID, now: Instant) {
		assertOwnership(customerId, orderId)
		expiryUseCase.expireIfDue(orderId, now)
		assertPendingPayment(orderId)
	}

	@Transactional(readOnly = true)
	fun assertOwnership(customerId: UUID, orderId: UUID) {
		val order = find(orderId)
		if (order.customerId != customerId) {
			throw DomainFailure(FailureCode.ACCESS_DENIED, "Order belongs to another customer")
		}
	}

	@Transactional(readOnly = true)
	fun assertPendingPayment(orderId: UUID) {
		if (find(orderId).state != OrderState.PENDING_PAYMENT) {
			throw DomainFailure(FailureCode.ORDER_STATE_CONFLICT, "Order is not eligible for payment")
		}
	}

	private fun find(orderId: UUID): OrderEntity =
		orderRepository.findById(orderId).orElse(null)
			?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")
}
