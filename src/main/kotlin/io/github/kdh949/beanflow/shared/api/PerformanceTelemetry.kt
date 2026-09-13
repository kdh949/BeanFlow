package io.github.kdh949.beanflow.shared.api

import java.time.Duration

enum class PerformanceOperation(
    val tagValue: String,
) {
    ORDER_CREATE("order_create"),
    ORDER_QUOTE("order_quote"),
    PAYMENT_CONFIRM("payment_confirm"),
    STORE_TRANSITION("store_transition"),
}

enum class PerformanceStage(
    val tagValue: String,
) {
    IDEMPOTENCY_REGISTER("idempotency_register"),
    TRANSACTION_PROXY("transaction_proxy"),
    QUOTE_CALCULATION("quote_calculation"),
    QUOTE_REVALIDATION("quote_revalidation"),
    WORKFLOW("workflow"),
    PICKUP_RESERVATION("pickup_reservation"),
    COUPON_RESERVATION("coupon_reservation"),
    POINT_RESERVATION("point_reservation"),
    PICKUP_SEQUENCE("pickup_sequence"),
    SNAPSHOT_PERSISTENCE("snapshot_persistence"),
    PAYMENT_PREPARE("payment_prepare"),
    PROVIDER_CALL("provider_call"),
    RESULT_APPLY("result_apply"),
    TRANSITION_PREPARE("transition_prepare"),
    TRANSITION_APPLY("transition_apply"),
}

interface PerformancePhaseTelemetry {
    fun <T> observe(
        operation: PerformanceOperation,
        stage: PerformanceStage,
        block: () -> T,
    ): T
}

enum class WorkerOwner(
    val tagValue: String,
    val refreshSeconds: Long,
) {
    EVENT_PUBLICATION("event_publication", 10),
    PAYMENT_RECONCILIATION("payment_reconciliation", 5),
    RESERVATION_EXPIRY("reservation_expiry", 30),
    ACCEPTANCE_TIMEOUT("acceptance_timeout", 1),
    REJECTION_REFUND("rejection_refund", 5),
    PARTIAL_REFUND_PROVIDER("partial_refund_provider", 5),
    PARTIAL_REFUND_RESTORATION("partial_refund_restoration", 5),
    REFUND_POINT_RECOVERY("refund_point_recovery", 5),
    NOTIFICATION("notification", 5),
}

interface WorkerRun {
    fun dataReadSucceeded()

    fun claimed(count: Int = 1)

    fun completed(count: Int = 1)

    fun completedAfter(
        duration: Duration,
        count: Int = 1,
    )

    fun failed(count: Int = 1)

    fun failedAfter(
        duration: Duration,
        count: Int = 1,
    )

    fun claimLag(duration: Duration)
}

enum class WorkerItemOutcome(
    val tagValue: String,
) {
    COMPLETED("completed"),
    FAILED("failed"),
}

interface WorkerTelemetry {
    fun <T> observe(
        owner: WorkerOwner,
        block: (WorkerRun) -> T,
    ): T

    fun recordClaimed(
        owner: WorkerOwner,
        count: Int = 1,
    )

    fun recordOutcome(
        owner: WorkerOwner,
        outcome: WorkerItemOutcome,
        duration: Duration,
        count: Int = 1,
    )
}
