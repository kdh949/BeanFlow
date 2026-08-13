package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.api.OrderCancellationCause
import io.github.kdh949.beanflow.ordering.api.ReservationExpiryUseCase
import io.github.kdh949.beanflow.ordering.internal.domain.OrderState
import io.github.kdh949.beanflow.payment.api.CustomerCancellationPaymentOperations
import io.github.kdh949.beanflow.payment.api.ProjectCustomerCancellationPaymentCommand
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.persistence.PersistenceException
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
internal class CustomerOrderQueryService(
    private val paging: CustomerOrderPaging,
    private val reads: CustomerOrderReadTransaction,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    fun list(
        customerId: UUID,
        status: CustomerOrderStatusFilter?,
        from: java.time.LocalDate?,
        to: java.time.LocalDate?,
        cursor: String?,
        limit: Int?,
    ): CustomerOrderPageResponse =
        observed(LIST) {
            val now = clock.instant()
            val prepared = paging.prepare(CustomerOrderListCriteria(customerId, status, from, to, cursor, limit, now))
            reads.list(prepared, now)
        }

    fun detail(
        customerId: UUID,
        rawReference: String,
    ): CustomerOrderDetailResponse =
        observed(DETAIL) {
            reads.detail(customerId, PublicOrderReference.parse(rawReference), clock.instant())
        }

    private fun <T> observed(
        operation: String,
        action: () -> T,
    ): T {
        val sample = Timer.start(meterRegistry)
        return try {
            action()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            dependency(failure)
        } catch (failure: PersistenceException) {
            dependency(failure)
        } catch (failure: TransactionException) {
            dependency(failure)
        } finally {
            sample.stop(meterRegistry.timer("beanflow.customer.order.query.duration", "operation", operation))
        }
    }

    private fun dependency(cause: RuntimeException): Nothing =
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer order read dependency is unavailable").also {
            it.initCause(cause)
        }

    private companion object {
        const val LIST = "list"
        const val DETAIL = "detail"
    }
}

@Component
internal class CustomerOrderReadTransaction(
    private val repository: CustomerOrderQueryRepository,
    private val expiry: ReservationExpiryUseCase,
    private val orders: OrderJpaRepository,
    private val signedCursorCodec: SignedCursorCodec,
    private val cancellationPayments: CustomerCancellationPaymentOperations,
    private val correlationIds: CorrelationIdSource,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun list(
        prepared: PreparedCustomerOrderPage,
        now: Instant,
    ): CustomerOrderPageResponse {
        val fetched = repository.findCandidates(prepared)
        val candidates = fetched.take(prepared.limit)
        materializeDue(candidates, now)
        val ids = candidates.map { it.orderId }
        val headers = repository.findHeaders(ids, prepared).associateBy { it.orderId }
        val lines = repository.findLinesForList(ids).groupBy { it.orderId }
        val items =
            candidates.mapNotNull { candidate ->
                headers[candidate.orderId]?.toSummary(lines[candidate.orderId].orEmpty(), now)
            }
        val nextCursor =
            if (fetched.size > prepared.limit) {
                val boundary = candidates.last()
                signedCursorCodec.issue(
                    prepared.cursorScope,
                    CustomerOrderSort(boundary.createdAt, boundary.orderId),
                    prepared.cursorExpiresAt,
                )
            } else {
                null
            }
        return CustomerOrderPageResponse(items, CustomerOrderPageInfoResponse(nextCursor))
    }

    @Transactional
    fun detail(
        customerId: UUID,
        reference: PublicOrderReference,
        now: Instant,
    ): CustomerOrderDetailResponse {
        val candidate = repository.findByReference(reference.value) ?: notFound()
        if (candidate.customerId != customerId) accessDenied()
        materializeDue(listOf(candidate), now)
        val header = repository.findDetailHeader(candidate.orderId, customerId) ?: notFound()
        val lines = repository.findDetailLines(candidate.orderId)
        return header.toDetail(lines, now)
    }

    private fun materializeDue(
        candidates: List<CustomerOrderCandidateProjection>,
        now: Instant,
    ) {
        val due =
            candidates
                .filter { candidate ->
                    parseState(candidate.state) == OrderState.PENDING_PAYMENT &&
                        (candidate.reservationExpiresAt ?: dependency("Pending-payment order has no reservation deadline")) <= now
                }.sortedBy { it.orderId.toString() }
        due.forEach { expiry.expireIfDue(it.orderId, now) }
        if (due.isNotEmpty()) orders.flush()
    }

    private fun CustomerOrderHeaderProjection.toSummary(
        lines: List<CustomerOrderLineProjection>,
        now: Instant,
    ): CustomerOrderSummaryResponse {
        validateHeader()
        return CustomerOrderSummaryResponse(
            orderReference = publicReference,
            pickupNumber = "A-$pickupSequence",
            storeName = storeName,
            status = state,
            orderedAt = createdAt,
            pickupWindowStart = pickupWindowStart,
            pickupWindowEnd = pickupWindowEnd,
            totalAmountKrw = subtotalKrw,
            currency = currency,
            itemSummary = CustomerOrderPresentationPolicy.itemSummary(lines.sortedBy { it.lineSequence }.map { it.menuName }),
            allowedActions = CustomerOrderPresentationPolicy.allowedActions(actionFacts(), now),
        )
    }

    private fun CustomerOrderHeaderProjection.toDetail(
        lineProjections: List<CustomerOrderLineProjection>,
        now: Instant,
    ): CustomerOrderDetailResponse {
        validateHeader()
        val lines =
            lineProjections.sortedBy { it.lineSequence }.map { line ->
                val optionNames =
                    try {
                        objectMapper.readValue(line.optionNamesJson, Array<String>::class.java).toList()
                    } catch (failure: RuntimeException) {
                        dependency("Order option snapshot cannot be read", failure)
                    }
                if (line.menuName.isBlank() || line.quantity <= 0 || line.grossKrw < 0 || optionNames.any { it.isBlank() }) {
                    dependency("Order line projection is invalid")
                }
                CustomerOrderLineResponse(
                    lineSequence = line.lineSequence,
                    menuName = line.menuName,
                    optionNames = optionNames,
                    quantity = line.quantity,
                    lineTotalKrw = line.grossKrw,
                )
            }
        if (lines.isEmpty()) dependency("Order has no displayable line")
        val paymentRecovery =
            if (cancellationCause == OrderCancellationCause.CUSTOMER_REQUEST.name) {
                cancellationPayments
                    .project(
                        ProjectCustomerCancellationPaymentCommand(
                            orderId = orderId,
                            cancellationOrderVersion = version,
                            paymentExpected = paidAt != null,
                            correlationId = correlationIds.currentOrCreate(),
                            now = now,
                        ),
                    ).let {
                        CancellationRefundRecoverySummary(
                            state = it.state,
                            noticeCode = it.noticeCode,
                            approvedAmountKrw = it.approvedAmountKrw,
                            succeededRefundAmountBeforeCancellationKrw = it.succeededRefundAmountBeforeCancellationKrw,
                            cancellationRequestedRefundAmountKrw = it.cancellationRequestedRefundAmountKrw,
                            remainingRefundableAmountKrw = it.remainingRefundableAmountKrw,
                            lastUpdatedAt = it.lastUpdatedAt,
                        )
                    }
            } else {
                null
            }
        return CustomerOrderDetailResponse(
            orderReference = publicReference,
            pickupNumber = "A-$pickupSequence",
            storeName = storeName,
            status = state,
            orderedAt = createdAt,
            pickupWindowStart = pickupWindowStart,
            pickupWindowEnd = pickupWindowEnd,
            totalAmountKrw = subtotalKrw,
            currency = currency,
            lines = lines,
            allowedActions = CustomerOrderPresentationPolicy.allowedActions(actionFacts(), now),
            paymentRecovery = paymentRecovery,
        )
    }

    private fun CustomerOrderHeaderProjection.actionFacts() =
        CustomerOrderActionFacts(
            state = parseState(state),
            reservationExpiresAt = reservationExpiresAt,
            acceptanceDeadlineAt = acceptanceDeadlineAt,
            cancellationCause = cancellationCause?.let(::parseCancellationCause),
        )

    private fun CustomerOrderHeaderProjection.validateHeader() {
        if (
            pickupSequence <= 0 || storeName.isBlank() || subtotalKrw < 0 || currency != "KRW" ||
            !pickupWindowEnd.isAfter(pickupWindowStart)
        ) {
            dependency("Customer order projection is invalid")
        }
        parseState(state)
    }

    private fun parseState(raw: String): OrderState =
        try {
            OrderState.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            dependency("Customer order state is unsupported")
        }

    private fun parseCancellationCause(raw: String): OrderCancellationCause =
        try {
            OrderCancellationCause.valueOf(raw)
        } catch (_: IllegalArgumentException) {
            dependency("Customer order cancellation cause is unsupported")
        }

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Order was not found")

    private fun accessDenied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Order is outside the requested ownership scope")

    private fun dependency(
        message: String,
        cause: RuntimeException? = null,
    ): Nothing =
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message).also {
            if (cause != null) it.initCause(cause)
        }
}
