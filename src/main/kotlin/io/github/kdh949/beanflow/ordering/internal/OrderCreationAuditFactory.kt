package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.loyalty.api.PointReservationResult
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.ordering.api.CreateOrderCommand
import io.github.kdh949.beanflow.ordering.internal.domain.Order
import io.github.kdh949.beanflow.promotion.api.CouponReservationQuote
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
internal class OrderCreationAuditFactory {
    fun create(
        command: CreateOrderCommand,
        order: Order,
        pickupReservationId: UUID,
        stockReservationIds: List<UUID>,
        coupon: CouponReservationQuote?,
        points: PointReservationResult?,
        benefit: BenefitOnlyConfirmation?,
        occurredAt: Instant,
        correlationId: String,
    ): List<AppendAuditRecordCommand> {
        val source = OrderCreationTransaction.createAuditSource(order.id)
        val records =
            mutableListOf(
                audit(
                    command,
                    "ORDER_CREATED",
                    "ORDER",
                    order.id,
                    occurredAt,
                    correlationId,
                    source,
                    after = mapOf("state" to order.state.name, "payableKrw" to order.payableKrw.toString()),
                ),
                audit(
                    command,
                    "PICKUP_RESERVED",
                    "PICKUP_RESERVATION",
                    pickupReservationId,
                    occurredAt,
                    correlationId,
                    source,
                    after = mapOf("state" to "RESERVED"),
                ),
            )
        stockReservationIds.forEach {
            records +=
                audit(
                    command,
                    "STOCK_RESERVED",
                    "STOCK_RESERVATION",
                    it,
                    occurredAt,
                    correlationId,
                    source,
                    after = mapOf("state" to "RESERVED"),
                )
        }
        coupon?.let {
            records +=
                audit(
                    command,
                    "COUPON_RESERVED",
                    "COUPON_RESERVATION",
                    it.reservationId,
                    occurredAt,
                    correlationId,
                    source,
                    after = mapOf("state" to "RESERVED", "discountKrw" to it.discountKrw.toString()),
                )
        }
        points?.let {
            records +=
                audit(
                    command,
                    "POINTS_RESERVED",
                    "POINT_RESERVATION",
                    it.reservationId,
                    occurredAt,
                    correlationId,
                    source,
                    after = mapOf("state" to "RESERVED", "amountKrw" to command.pointsToUseKrw.toString()),
                )
        }
        benefit?.let {
            records +=
                audit(
                    command,
                    "BENEFIT_ONLY_PAYMENT_APPROVED",
                    "PAYMENT",
                    it.payment.paymentId,
                    occurredAt,
                    correlationId,
                    source,
                    after = mapOf("approvalState" to "APPROVED", "approvedAmountKrw" to "0"),
                )
            it.pickup.targetIds.forEach { id ->
                records += confirmation(command, "PICKUP", id, occurredAt, correlationId, source)
            }
            it.stock.targetIds.forEach { id ->
                records += confirmation(command, "STOCK", id, occurredAt, correlationId, source)
            }
            it.coupon?.targetIds?.forEach { id ->
                records += confirmation(command, "COUPON", id, occurredAt, correlationId, source, "USED")
            }
            it.points.targetIds.forEach { id ->
                records += confirmation(command, "POINTS", id, occurredAt, correlationId, source, "USED")
            }
        }
        return records
    }

    private fun confirmation(
        command: CreateOrderCommand,
        owner: String,
        targetId: UUID,
        occurredAt: Instant,
        correlationId: String,
        source: String,
        afterState: String = "CONFIRMED",
    ): AppendAuditRecordCommand =
        audit(
            command,
            "${owner}_CONFIRMED",
            if (owner == "POINTS") "POINT_RESERVATION" else "${owner}_RESERVATION",
            targetId,
            occurredAt,
            correlationId,
            source,
            before = mapOf("state" to "RESERVED"),
            after = mapOf("state" to afterState),
        )

    private fun audit(
        command: CreateOrderCommand,
        action: String,
        targetType: String,
        targetId: UUID,
        occurredAt: Instant,
        correlationId: String,
        source: String,
        after: Map<String, String>,
        before: Map<String, String> = emptyMap(),
    ) = AppendAuditRecordCommand(
        actorId = command.customerId.toString(),
        actorType = AuditActorType.CUSTOMER,
        category = AuditCategory.ORDER_AND_FULFILLMENT,
        action = action,
        targetType = targetType,
        targetId = targetId,
        occurredAt = occurredAt,
        reason = "CUSTOMER_ORDER_CREATION",
        beforeSummary = before,
        afterSummary = after,
        correlationId = correlationId,
        sourceReference = source,
    )
}
