package io.github.kdh949.beanflow.ordering.internal

import io.github.kdh949.beanflow.ordering.internal.domain.Order
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
internal class OrderSnapshotAssembler(
    private val objectMapper: ObjectMapper,
) {
    fun order(order: Order): OrderEntity =
        OrderEntity(
            id = order.id,
            customerId = order.customerId,
            storeId = order.storeId,
            pickupSlotId = order.pickupSlotId,
            publicReference = order.displayIdentity.publicReference,
            pickupBusinessDate = order.displayIdentity.pickupBusinessDate,
            pickupSequence = order.displayIdentity.pickupSequence,
            storeNameSnapshot = order.displayIdentity.storeName,
            pickupWindowStartSnapshot = order.displayIdentity.pickupWindowStart,
            pickupWindowEndSnapshot = order.displayIdentity.pickupWindowEnd,
            state = order.state,
            subtotalKrw = order.subtotalKrw,
            couponDiscountKrw = order.couponDiscountKrw,
            pointsAppliedKrw = order.pointsAppliedKrw,
            payableKrw = order.payableKrw,
            reservationExpiresAt = order.reservationExpiresAt,
            paidAtAtCreation = order.paidAt,
            acceptanceWarningAtAtCreation = order.acceptanceWarningAt,
            acceptanceDeadlineAtAtCreation = order.acceptanceDeadlineAt,
            createdAt = order.createdAt,
            updatedAt = order.createdAt,
        )

    fun lines(order: Order): List<OrderLineEntity> =
        order.lines.map { line ->
            OrderLineEntity(
                id = line.id,
                orderId = order.id,
                lineSequence = line.lineSequence,
                menuId = line.menuId,
                menuName = line.menuName,
                optionNamesJson = objectMapper.writeValueAsString(line.options.map { it.name }),
                optionSelectionSnapshotState = OptionSelectionSnapshotState.SNAPSHOTTED,
                normalizedOptionIds =
                    line.options
                        .map { it.optionId }
                        .distinct()
                        .sortedBy { it.toString() },
                sellableRequirementsJson = objectMapper.writeValueAsString(line.sellableUnitRequirements),
                unitPriceKrw = line.unitPriceKrw,
                quantity = line.quantity,
                grossKrw = line.grossKrw,
                couponDiscountKrw = line.couponDiscountKrw,
                pointsAppliedKrw = line.pointsAppliedKrw,
                cashPayableKrw = line.cashPayableKrw,
            )
        }

    fun pointAccrualLines(order: Order): List<OrderPointAccrualLineInput> =
        order.lines.map { line ->
            OrderPointAccrualLineInput(
                orderLineId = line.id,
                lineSequence = line.lineSequence,
                unitPriceKrw = line.unitPriceKrw,
                quantity = line.quantity,
                grossKrw = line.grossKrw,
                couponDiscountKrw = line.couponDiscountKrw,
                pointsAppliedKrw = line.pointsAppliedKrw,
                cashPayableKrw = line.cashPayableKrw,
            )
        }
}
