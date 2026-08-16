package io.github.kdh949.beanflow.ordering.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationTrigger
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
import io.github.kdh949.beanflow.ordering.api.OrderRefundSnapshotOperations
import io.github.kdh949.beanflow.ordering.api.RefundableOrderSnapshot
import io.github.kdh949.beanflow.payment.api.PartialRefundPaymentOperations
import io.github.kdh949.beanflow.payment.api.PartialRefundPreviewPayment
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.util.UUID

internal data class MerchantRefundPreviewQuery(
    val actorId: UUID,
    val storeId: UUID,
    val orderReference: String,
    val lines: List<PartialRefundLineSelection>,
)

internal data class MerchantRefundCommand(
    val actorId: UUID,
    val storeId: UUID,
    val orderReference: String,
    val idempotencyKey: String,
    val lines: List<PartialRefundLineSelection>,
    val previewVersion: String,
    val reason: String,
)

internal data class MerchantRefundPreviewLineResponse(
    val lineSequence: Int,
    val menuName: String,
    val selectedQuantity: Long,
    val remainingQuantity: Long,
    val grossAttributionKrw: Long,
    val couponAttributionKrw: Long,
    val pointsRestorationKrw: Long,
    val cashRefundKrw: Long,
)

internal data class MerchantRefundPreviewTotalsResponse(
    val grossAttributionKrw: Long,
    val couponAttributionKrw: Long,
    val pointsRestorationKrw: Long,
    val cashRefundKrw: Long,
    val currency: String,
)

internal data class MerchantRefundPreviewResponse(
    val orderReference: String,
    val lines: List<MerchantRefundPreviewLineResponse>,
    val totals: MerchantRefundPreviewTotalsResponse,
    val previewVersion: String,
)

/**
 * Merchant-facing Refund representation. It is the stored Refund response
 * projected onto the public contract, so it carries no internal identifier:
 * no `refundId`, `paymentId` or `orderLineId`. Operators track a refund by
 * re-reading the preview of their own order.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class MerchantRefundResponse(
    val orderReference: String,
    val state: String,
    val cashRefundRequestedKrw: Long,
    val cashRefundedKrw: Long?,
    val pointsRestorationRequestedKrw: Long,
    val pointsRestorationState: String,
    val pointsRestoredKrw: Long?,
    val currency: String,
    val createdAt: String,
    val updatedAt: String,
    val correlationId: String,
)

/**
 * Store-scoped refund facade of [ADR-108](docs/adr/ADR-108-merchant-partial-refund-preview.md).
 *
 * The preview is a read-only projection that reserves nothing. Execution reuses
 * the existing preparation transaction, Provider call and result transaction,
 * and only adds the sequence-to-OrderLine translation and the previewVersion
 * re-check that happen under the refund locks.
 */
@Service
internal class MerchantRefundService(
    private val orderReferences: PublicOrderReferenceService,
    private val storeAccess: StoreAccessOperations,
    private val orderSnapshots: OrderRefundSnapshotOperations,
    private val paymentOperations: PartialRefundPaymentOperations,
    private val policyOperations: ExpiredBenefitRestorationPolicyOperations,
    private val partialRefunds: PartialRefundService,
    private val objectMapper: ObjectMapper,
    private val metrics: MerchantRefundMetrics,
) {
    @Transactional(readOnly = true)
    fun preview(query: MerchantRefundPreviewQuery): MerchantRefundPreviewResponse {
        storeAccess.requireOrderManagementAccess(query.actorId, query.storeId, REFUND_ROLES)
        val resolved = orderReferences.resolveStore(query.storeId, query.orderReference)
        val order = orderSnapshots.readRefundableSnapshot(resolved.orderId)
        val payment = paymentOperations.previewSnapshot(order.orderId)
        if (payment.unresolvedRefundCount > 0) {
            metrics.recordPreview(MerchantRefundPreviewOutcome.UNRESOLVED)
            throw DomainFailure(
                FailureCode.REFUND_OUTCOME_UNRESOLVED,
                "Payment has an unresolved Refund",
            )
        }
        val selectedByLineSequence = query.lines.associate { it.lineSequence to it.quantity }
        if (selectedByLineSequence.size != query.lines.size) {
            metrics.recordPreview(MerchantRefundPreviewOutcome.INVALID_INPUT)
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Refund line sequences must be unique")
        }
        val unknown = selectedByLineSequence.keys - order.lines.map { it.lineSequence }.toSet()
        if (unknown.isNotEmpty()) {
            metrics.recordPreview(MerchantRefundPreviewOutcome.INVALID_INPUT)
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Refund contains an unknown line sequence")
        }
        val selectedByOrderLine =
            order.lines
                .filter { it.lineSequence in selectedByLineSequence }
                .associate { it.orderLineId to selectedByLineSequence.getValue(it.lineSequence) }
        val allocated =
            RefundAllocationCalculator
                .allocate(order.lines, payment.consumedQuantityByOrderLine, selectedByOrderLine)
                .associateBy { it.line.lineSequence }
        val lines =
            order.lines.map { line ->
                val allocation = allocated[line.lineSequence]
                MerchantRefundPreviewLineResponse(
                    lineSequence = line.lineSequence,
                    menuName = line.menuName,
                    selectedQuantity = allocation?.quantity ?: 0,
                    remainingQuantity =
                        RefundAllocationCalculator.remainingQuantity(line, payment.consumedQuantityByOrderLine),
                    grossAttributionKrw = allocation?.grossKrw ?: 0,
                    couponAttributionKrw = allocation?.couponKrw ?: 0,
                    pointsRestorationKrw = allocation?.pointsKrw ?: 0,
                    cashRefundKrw = allocation?.cashKrw ?: 0,
                )
            }
        metrics.recordPreview(MerchantRefundPreviewOutcome.SUCCEEDED)
        return MerchantRefundPreviewResponse(
            orderReference = resolved.reference.value,
            lines = lines,
            totals =
                MerchantRefundPreviewTotalsResponse(
                    grossAttributionKrw = lines.sumOf { it.grossAttributionKrw },
                    couponAttributionKrw = lines.sumOf { it.couponAttributionKrw },
                    pointsRestorationKrw = lines.sumOf { it.pointsRestorationKrw },
                    cashRefundKrw = lines.sumOf { it.cashRefundKrw },
                    currency = order.currency,
                ),
            previewVersion = previewVersion(order, payment),
        )
    }

    fun execute(command: MerchantRefundCommand): PartialRefundHttpResult {
        val resolved = resolvedOrder(command)
        val result =
            try {
                partialRefunds.create(
                    PartialRefundCommand(
                        paymentId = resolved.paymentId,
                        actor =
                            PartialRefundActor(
                                command.actorId,
                                setOf(PartialRefundActorType.STORE_OWNER, PartialRefundActorType.STORE_STAFF),
                            ),
                        idempotencyKey = command.idempotencyKey,
                        lines = null,
                        reason = command.reason,
                        merchantSelection =
                            MerchantRefundSelection(command.lines, command.previewVersion),
                    ),
                )
            } catch (failure: DomainFailure) {
                metrics.recordExecution(failure.code.name)
                throw failure
            }
        metrics.recordExecution("SUCCEEDED")
        // The merchant contract exposes no created Refund resource, so a definitive
        // outcome is 200 while an unresolved Provider outcome stays 202.
        val status = if (result.status == HttpStatus.CREATED.value()) HttpStatus.OK.value() else result.status
        return PartialRefundHttpResult(status, merchantBody(result.body, resolved.orderReference))
    }

    /**
     * Resolves the store-scoped order reference before the refund transaction so
     * that a foreign or unknown reference never reaches the payment lock. The
     * transaction itself re-reads and re-authorizes every input.
     */
    @Transactional(readOnly = true)
    fun resolvedOrder(command: MerchantRefundCommand): ResolvedMerchantRefundTarget {
        storeAccess.requireOrderManagementAccess(command.actorId, command.storeId, REFUND_ROLES)
        val resolved = orderReferences.resolveStore(command.storeId, command.orderReference)
        return ResolvedMerchantRefundTarget(
            orderReference = resolved.reference.value,
            paymentId = paymentOperations.previewSnapshot(resolved.orderId).paymentId,
        )
    }

    private fun previewVersion(
        order: RefundableOrderSnapshot,
        payment: PartialRefundPreviewPayment,
    ): String {
        val policy =
            policyOperations.currentUnlocked(
                ExpiredBenefitRestorationTrigger.PARTIAL_REFUND,
                ExpiredBenefitType.POINTS,
            )
        return RefundPreviewVersion.compute(
            RefundPreviewState(
                orderId = order.orderId,
                orderAggregateVersion = order.aggregateVersion,
                paymentId = payment.paymentId,
                paymentVersion = payment.paymentVersion,
                approvedAmountKrw = payment.approvedAmountKrw,
                succeededRefundAmountKrw = payment.succeededRefundAmountKrw,
                unresolvedRefundCount = payment.unresolvedRefundCount,
                restorationPolicyVersionId = policy.policyVersion,
                remainingByLineSequence =
                    order.lines.associate { line ->
                        line.lineSequence to
                            RefundAllocationCalculator.remainingQuantity(line, payment.consumedQuantityByOrderLine)
                    },
            ),
        )
    }

    /**
     * Reprojects the stored Refund response body onto the merchant contract.
     * The stored body stays the single idempotent source; this only drops the
     * internal payment identifier and names the order the way the operator does.
     */
    private fun merchantBody(
        storedBody: String,
        orderReference: String,
    ): String {
        val stored = objectMapper.readTree(storedBody)
        val response =
            MerchantRefundResponse(
                orderReference = orderReference,
                state = stored["state"].asText(),
                cashRefundRequestedKrw = stored["cashRefundRequestedKrw"].asLong(),
                cashRefundedKrw = stored.get("cashRefundedKrw")?.asLong(),
                pointsRestorationRequestedKrw = stored["pointsRestorationRequestedKrw"].asLong(),
                pointsRestorationState = stored["pointsRestorationState"].asText(),
                pointsRestoredKrw = stored.get("pointsRestoredKrw")?.asLong(),
                currency = stored["currency"].asText(),
                createdAt = stored["createdAt"].asText(),
                updatedAt = stored["updatedAt"].asText(),
                correlationId = stored["correlationId"].asText(),
            )
        return objectMapper.writeValueAsString(response)
    }

    private companion object {
        val REFUND_ROLES = setOf(StoreActorRole.OWNER, StoreActorRole.STAFF)
    }
}

internal data class ResolvedMerchantRefundTarget(
    val orderReference: String,
    val paymentId: UUID,
)

internal enum class MerchantRefundPreviewOutcome {
    SUCCEEDED,
    INVALID_INPUT,
    UNRESOLVED,
}

@Component
internal class MerchantRefundMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun recordPreview(outcome: MerchantRefundPreviewOutcome) {
        meterRegistry.counter("beanflow.refund.preview.count", "outcome", outcome.name).increment()
    }

    /** [outcome] is a server-owned failure code. Operator reasons are never used as tags. */
    fun recordExecution(outcome: String) {
        meterRegistry.counter("beanflow.refund.merchant_execution.count", "outcome", outcome).increment()
    }
}
