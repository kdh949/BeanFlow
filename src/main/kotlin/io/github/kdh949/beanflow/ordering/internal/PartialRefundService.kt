package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.identity.api.StoreAccessOperations
import io.github.kdh949.beanflow.identity.api.StoreActorRole
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointOperations
import io.github.kdh949.beanflow.loyalty.api.PartialRefundPointSourceAllocation
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationPolicyOperations
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitRestorationTrigger
import io.github.kdh949.beanflow.operations.api.ExpiredBenefitType
import io.github.kdh949.beanflow.ordering.api.OrderRefundSnapshotOperations
import io.github.kdh949.beanflow.ordering.api.RefundableOrderLineSnapshot
import io.github.kdh949.beanflow.payment.api.CreatePartialRefundPaymentCommand
import io.github.kdh949.beanflow.payment.api.LockPartialRefundPaymentCommand
import io.github.kdh949.beanflow.payment.api.PartialRefundAuditActorType
import io.github.kdh949.beanflow.payment.api.PartialRefundIssuerType
import io.github.kdh949.beanflow.payment.api.PartialRefundLineRequestSnapshot
import io.github.kdh949.beanflow.payment.api.PartialRefundPaymentLock
import io.github.kdh949.beanflow.payment.api.PartialRefundPaymentOperations
import io.github.kdh949.beanflow.payment.api.PartialRefundPointRequestSnapshot
import io.github.kdh949.beanflow.payment.api.PartialRefundRestorationPolicyMode
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal data class PartialRefundLineInput(
    val orderLineId: UUID,
    val quantity: Long,
)

internal enum class PartialRefundActorType {
    STORE_OWNER,
    STORE_STAFF,
    PLATFORM_OPERATOR,
}

internal data class PartialRefundActor(
    val actorId: UUID,
    val actorTypes: Set<PartialRefundActorType>,
)

internal data class PartialRefundCommand(
    val paymentId: UUID,
    val actor: PartialRefundActor,
    val idempotencyKey: String,
    val lines: List<PartialRefundLineInput>?,
    val reason: String,
)

internal data class PartialRefundHttpResult(
    val status: Int,
    val body: String,
)

internal data class PreparedPartialRefund(
    val refundId: UUID,
    val cashAmountKrw: Long,
    val existingResponse: PartialRefundHttpResult? = null,
    val inProgress: Boolean = false,
)

private data class RequestedLineAllocation(
    val line: RefundableOrderLineSnapshot,
    val firstUnitIndex: Long,
    val quantity: Long,
    val grossKrw: Long,
    val couponKrw: Long,
    val pointsKrw: Long,
    val cashKrw: Long,
)

private data class UnitAllocation(
    val grossKrw: Long,
    val couponKrw: Long,
    val pointsKrw: Long,
) {
    val cashKrw: Long = grossKrw - couponKrw - pointsKrw
}

@Service
internal class PartialRefundService(
    private val preparation: PartialRefundPreparationTransaction,
    private val paymentOperations: PartialRefundPaymentOperations,
    private val clock: Clock,
) {
    fun create(command: PartialRefundCommand): PartialRefundHttpResult {
        val prepared = preparation.prepare(command, clock.instant())
        prepared.existingResponse?.let { return it }
        if (prepared.inProgress) {
            throw DomainFailure(
                FailureCode.IDEMPOTENCY_REQUEST_IN_PROGRESS,
                "The same partial Refund request is still in progress",
                retryAfterSeconds = 1,
            )
        }
        if (prepared.cashAmountKrw > 0) {
            paymentOperations.executeProvider(prepared.refundId, clock.instant())
        }
        val response = paymentOperations.recordOrReplayResponse(prepared.refundId, clock.instant())
        return PartialRefundHttpResult(response.status, response.body)
    }
}

@Service
internal class PartialRefundPreparationTransaction(
    private val paymentOperations: PartialRefundPaymentOperations,
    private val orderSnapshots: OrderRefundSnapshotOperations,
    private val storeAccess: StoreAccessOperations,
    private val pointOperations: PartialRefundPointOperations,
    private val policyOperations: ExpiredBenefitRestorationPolicyOperations,
) {
    @Transactional
    internal fun prepare(
        command: PartialRefundCommand,
        now: Instant,
    ): PreparedPartialRefund {
        validate(command)
        val payloadHash = payloadHash(command)
        val order = orderSnapshots.lockRefundableSnapshot(paymentOperations.orderId(command.paymentId))
        authorize(command.actor, order.storeId)
        val locked =
            paymentOperations.lock(
                LockPartialRefundPaymentCommand(
                    paymentId = command.paymentId,
                    actorId = command.actor.actorId,
                    idempotencyKey = command.idempotencyKey,
                    payloadHash = payloadHash,
                ),
            )
        if (locked is PartialRefundPaymentLock.Replay) {
            return PreparedPartialRefund(
                refundId = locked.refundId,
                cashAmountKrw = 0,
                existingResponse = locked.response?.let { PartialRefundHttpResult(it.status, it.body) },
                inProgress = locked.inProgress,
            )
        }
        locked as PartialRefundPaymentLock.Ready
        if (locked.orderId != order.orderId) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Payment does not belong to the locked order")
        }
        val requestedLines = requestedLines(command.lines, order.lines, locked.consumedQuantityByOrderLine)
        if (requestedLines.isEmpty()) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Payment has no remaining refundable OrderLine units")
        }
        val cashRequested = requestedLines.sumOf { it.cashKrw }
        val pointsRequested = requestedLines.sumOf { it.pointsKrw }
        if (cashRequested + pointsRequested <= 0) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Selected units contain no refundable cash or points")
        }
        if (Math.addExact(locked.succeededRefundAmountKrw, cashRequested) > locked.approvedAmountKrw) {
            fail(FailureCode.ORDER_STATE_CONFLICT, "Refund request would exceed the approved payment amount")
        }
        val pointSource = pointOperations.lockSourceSnapshot(order.orderId)
        if (pointsRequested > 0 && pointSource.pointReservationId == null) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point source snapshot is missing")
        }
        val policy =
            policyOperations.current(
                ExpiredBenefitRestorationTrigger.PARTIAL_REFUND,
                ExpiredBenefitType.POINTS,
            )
        val pointRequests =
            pointRequests(
                requestedLines,
                pointSource.allocations,
                locked.consumedPointsByReservationAllocation,
            )
        val prepared =
            paymentOperations.create(
                CreatePartialRefundPaymentCommand(
                    paymentId = command.paymentId,
                    orderId = order.orderId,
                    actorId = command.actor.actorId,
                    auditActorType = command.actor.auditType(),
                    idempotencyKey = command.idempotencyKey,
                    payloadHash = payloadHash,
                    reason = command.reason.trim(),
                    policyVersionId = policy.policyVersion,
                    policyMode = PartialRefundRestorationPolicyMode.valueOf(policy.mode.name),
                    compensationValidityDays = policy.compensationValidityDays,
                    lineRequests = requestedLines.map { it.toPaymentSnapshot() },
                    pointRequests = pointRequests,
                    now = now,
                ),
            )
        return PreparedPartialRefund(prepared.refundId, prepared.cashAmountKrw)
    }

    private fun requestedLines(
        commandLines: List<PartialRefundLineInput>?,
        allLines: List<RefundableOrderLineSnapshot>,
        consumed: Map<UUID, Long>,
    ): List<RequestedLineAllocation> {
        val inputById = commandLines?.associateBy { it.orderLineId }
        if (commandLines != null && inputById!!.size != commandLines.size) {
            fail(FailureCode.INVALID_REQUEST, "Refund OrderLine IDs must be unique")
        }
        if (inputById != null && inputById.keys.any { id -> allLines.none { it.orderLineId == id } }) {
            fail(FailureCode.INVALID_REQUEST, "Refund contains an OrderLine from another order")
        }
        return allLines.mapNotNull { line ->
            val first = consumed[line.orderLineId] ?: 0L
            if (first > line.quantity) {
                fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Successful Refund quantity exceeds OrderLine snapshot")
            }
            val remaining = line.quantity - first
            val requested = inputById?.get(line.orderLineId)?.quantity ?: remaining.takeIf { inputById == null } ?: 0L
            if (requested == 0L) return@mapNotNull null
            if (requested < 1 || requested > remaining) {
                fail(FailureCode.ORDER_STATE_CONFLICT, "Refund quantity exceeds remaining OrderLine units")
            }
            val units = allocateUnits(line).subList(first.toInt(), Math.addExact(first, requested).toInt())
            RequestedLineAllocation(
                line = line,
                firstUnitIndex = first,
                quantity = requested,
                grossKrw = units.sumOf { it.grossKrw },
                couponKrw = units.sumOf { it.couponKrw },
                pointsKrw = units.sumOf { it.pointsKrw },
                cashKrw = units.sumOf { it.cashKrw },
            )
        }
    }

    private fun allocateUnits(line: RefundableOrderLineSnapshot): List<UnitAllocation> {
        if (line.quantity > Int.MAX_VALUE) fail(FailureCode.INVALID_REQUEST, "OrderLine quantity is too large")
        if (Math.multiplyExact(line.unitPriceKrw, line.quantity) != line.grossKrw) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "OrderLine gross snapshot is inconsistent")
        }
        val quantity = line.quantity.toInt()
        val couponBase = line.couponDiscountKrw / quantity
        val couponRemainder = (line.couponDiscountKrw % quantity).toInt()
        val coupons = List(quantity) { index -> couponBase + if (index < couponRemainder) 1 else 0 }
        val balances = coupons.map { line.unitPriceKrw - it }
        val balanceTotal = balances.sum()
        if (balanceTotal < line.pointsAppliedKrw) {
            fail(FailureCode.DEPENDENCY_UNAVAILABLE, "OrderLine points exceed its post-coupon balance")
        }
        val points = LongArray(quantity)
        if (line.pointsAppliedKrw > 0) {
            balances.forEachIndexed { index, balance ->
                points[index] = Math.multiplyExact(line.pointsAppliedKrw, balance) / balanceTotal
            }
            var remainder = line.pointsAppliedKrw - points.sum()
            balances.indices.sortedWith(compareByDescending<Int> { balances[it] }.thenBy { it }).forEach { index ->
                if (remainder > 0 && points[index] < balances[index]) {
                    points[index]++
                    remainder--
                }
            }
            if (remainder != 0L) fail(FailureCode.DEPENDENCY_UNAVAILABLE, "OrderLine point remainder did not tie out")
        }
        return balances.indices
            .map { index -> UnitAllocation(line.unitPriceKrw, coupons[index], points[index]) }
            .also { units ->
                if (units.sumOf { it.couponKrw } != line.couponDiscountKrw ||
                    units.sumOf { it.pointsKrw } != line.pointsAppliedKrw ||
                    units.sumOf { it.cashKrw } != line.cashPayableKrw
                ) {
                    fail(FailureCode.DEPENDENCY_UNAVAILABLE, "OrderLine unit allocation did not tie out")
                }
            }
    }

    private fun pointRequests(
        lines: List<RequestedLineAllocation>,
        sources: List<PartialRefundPointSourceAllocation>,
        consumed: Map<UUID, Long>,
    ): List<PartialRefundPointRequestSnapshot> {
        val remainingBySource =
            sources
                .associate { source ->
                    val remaining = source.amountKrw - (consumed[source.pointReservationAllocationId] ?: 0L)
                    if (remaining < 0) fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Point allocation consumption is negative")
                    source.pointReservationAllocationId to remaining
                }.toMutableMap()
        val requests = mutableListOf<PartialRefundPointRequestSnapshot>()
        var sourceIndex = 0
        lines.forEach { line ->
            var remainingLinePoints = line.pointsKrw
            while (remainingLinePoints > 0) {
                while (sourceIndex < sources.size &&
                    remainingBySource.getValue(sources[sourceIndex].pointReservationAllocationId) == 0L
                ) {
                    sourceIndex++
                }
                if (sourceIndex >= sources.size) {
                    fail(FailureCode.DEPENDENCY_UNAVAILABLE, "Original PointReservation allocation is insufficient")
                }
                val source = sources[sourceIndex]
                val sourceRemaining = remainingBySource.getValue(source.pointReservationAllocationId)
                val amount = minOf(remainingLinePoints, sourceRemaining)
                requests +=
                    PartialRefundPointRequestSnapshot(
                        orderLineId = line.line.orderLineId,
                        pointReservationAllocationId = source.pointReservationAllocationId,
                        originalPointLotId = source.pointLotId,
                        issuerType = PartialRefundIssuerType.valueOf(source.issuerType.name),
                        issuerReference = source.issuerReference,
                        requestedAmountKrw = amount,
                    )
                remainingLinePoints -= amount
                remainingBySource[source.pointReservationAllocationId] = sourceRemaining - amount
            }
        }
        return requests
    }

    private fun RequestedLineAllocation.toPaymentSnapshot() =
        PartialRefundLineRequestSnapshot(
            orderLineId = line.orderLineId,
            lineSequence = line.lineSequence,
            firstUnitIndex = firstUnitIndex,
            quantity = quantity,
            originalQuantity = line.quantity,
            grossKrw = grossKrw,
            couponAttributionKrw = couponKrw,
            pointsRestorationKrw = pointsKrw,
            cashRefundKrw = cashKrw,
        )

    private fun authorize(
        actor: PartialRefundActor,
        storeId: UUID,
    ) {
        if (PartialRefundActorType.PLATFORM_OPERATOR in actor.actorTypes) return
        val roles =
            buildSet {
                if (PartialRefundActorType.STORE_OWNER in actor.actorTypes) add(StoreActorRole.OWNER)
                if (PartialRefundActorType.STORE_STAFF in actor.actorTypes) add(StoreActorRole.STAFF)
            }
        if (roles.isEmpty()) fail(FailureCode.ACCESS_DENIED, "Store or platform-operator role is required")
        storeAccess.requireOrderManagementAccess(actor.actorId, storeId, roles)
    }

    private fun validate(command: PartialRefundCommand) {
        if (command.idempotencyKey.length !in 8..128 || command.idempotencyKey.any(Char::isISOControl)) {
            fail(FailureCode.INVALID_REQUEST, "Idempotency-Key is invalid")
        }
        if (command.reason.trim().length !in 1..500 || command.reason.any(Char::isISOControl)) {
            fail(FailureCode.INVALID_REQUEST, "Refund reason must contain between 1 and 500 characters")
        }
        if (command.lines?.isEmpty() == true || command.lines?.any { it.quantity < 1 } == true) {
            fail(FailureCode.INVALID_REQUEST, "Refund line quantity must be positive")
        }
    }

    private fun payloadHash(command: PartialRefundCommand): String {
        val linePart =
            command.lines
                ?.sortedBy { it.orderLineId }
                ?.joinToString(",") { "${it.orderLineId}:${it.quantity}" } ?: "FULL"
        val canonical = "${command.paymentId}|$linePart|${command.reason.trim()}"
        return MessageDigest
            .getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun PartialRefundActor.auditType(): PartialRefundAuditActorType =
        when {
            PartialRefundActorType.PLATFORM_OPERATOR in actorTypes -> PartialRefundAuditActorType.PLATFORM_OPERATOR
            PartialRefundActorType.STORE_OWNER in actorTypes -> PartialRefundAuditActorType.STORE_OWNER
            else -> PartialRefundAuditActorType.STORE_STAFF
        }

    private fun fail(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)
}
