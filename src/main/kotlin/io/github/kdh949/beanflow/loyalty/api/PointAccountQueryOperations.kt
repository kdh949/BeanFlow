package io.github.kdh949.beanflow.loyalty.api

import java.time.Instant
import java.util.UUID

enum class PointAccountReadActorType {
    CUSTOMER,
    PLATFORM_OPERATOR,
}

data class PointAccountReadActor(
    val actorId: UUID,
    val type: PointAccountReadActorType,
)

data class ReadPointAccountCommand(
    val actor: PointAccountReadActor,
    val accountId: UUID,
    val accessReason: String?,
    val now: Instant,
)

data class ListPointTransactionsCommand(
    val actor: PointAccountReadActor,
    val accountId: UUID,
    val accessReason: String?,
    val cursor: String?,
    val limit: Int?,
    val now: Instant,
)

data class PointTransactionPage(
    val items: List<PointTransactionView>,
    val nextCursor: String?,
)

interface PointAccountQueryOperations {
    fun get(command: ReadPointAccountCommand): PointAccountView

    fun listTransactions(command: ListPointTransactionsCommand): PointTransactionPage
}
