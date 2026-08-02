package io.github.kdh949.beanflow.eventing.api

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class EventEnvelope(
    val eventId: UUID,
    val eventType: String,
    val aggregateId: UUID,
    val aggregateVersion: Long,
    val occurredAt: Instant,
    val payloadVersion: Int,
    val correlationId: String,
    val causationId: String,
)

enum class OrderRejectionActorType {
    STORE_OWNER,
    STORE_STAFF,
    SYSTEM_TIMEOUT,
}

data class OrderRejectedV1(
    val envelope: EventEnvelope,
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val actorId: String,
    val actorType: OrderRejectionActorType,
    val reason: String,
    val rejectedAt: Instant,
    val policyVersion: Long,
    val policyMode: String,
    val policyValidityDays: Int,
    val paymentRequired: Boolean,
    val couponRequired: Boolean,
    val pointsRequired: Boolean,
)

data class StoreAcceptanceWarningRequestedV1(
    val envelope: EventEnvelope,
    val orderId: UUID,
    val storeId: UUID,
    val acceptanceDeadlineAt: Instant,
)

data class OrderAcceptedV1(
    val envelope: EventEnvelope,
    val orderId: UUID,
    val storeId: UUID,
    val acceptedAt: Instant,
)

data class OrderReadyV1(
    val envelope: EventEnvelope,
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val readyAt: Instant,
)

data class OrderCompletedV1(
    val envelope: EventEnvelope,
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val completedAt: Instant,
)

data class OrderCompletedV2(
    val envelope: EventEnvelope,
    val orderId: UUID,
    val customerId: UUID,
    val storeId: UUID,
    val completedAt: Instant,
    val settlementDate: LocalDate,
    val currency: String,
    val grossPaidKrw: Long,
    val feeRateBps: Int,
    val feeKrw: Long,
    val couponCostKrw: Long,
    val pointCostKrw: Long,
    val benefitCostKrw: Long,
    val netSettlementKrw: Long,
    val completionSource: String,
)
