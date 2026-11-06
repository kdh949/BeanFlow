package io.github.kdh949.beanflow.loyalty.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.loyalty.api.ListPointTransactionsCommand
import io.github.kdh949.beanflow.loyalty.api.PointAccountQueryOperations
import io.github.kdh949.beanflow.loyalty.api.PointAccountReadActor
import io.github.kdh949.beanflow.loyalty.api.PointAccountReadActorType
import io.github.kdh949.beanflow.loyalty.api.PointTransactionViewType
import io.github.kdh949.beanflow.shared.api.CustomerOrderDisplayQuery
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

internal data class CustomerPointOrderContextResponse(
    val publicReference: String,
    val storeName: String,
    val firstMenuName: String,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class CustomerPointTransactionResponse(
    val transactionId: UUID,
    val type: PointTransactionViewType,
    val amountKrw: Long,
    val occurredAt: Instant,
    val sourceReference: String,
    val orderContext: CustomerPointOrderContextResponse?,
)

internal data class CustomerPointTransactionPageResponse(
    val items: List<CustomerPointTransactionResponse>,
    val page: PointAccountPageInfoResponse,
)

/** Enriches the customer page only; shared operator/adjustment representations remain unchanged. */
@Component
internal class CustomerPointTransactionReader(
    private val accounts: CustomerPointAccountLocator,
    private val queries: PointAccountQueryOperations,
    private val repository: PointAccountQueryRepository,
    private val orderDisplays: CustomerOrderDisplayQuery,
) {
    @Transactional(readOnly = true)
    fun read(
        customerId: UUID,
        cursor: String?,
        limit: Int?,
        now: Instant,
    ): CustomerPointTransactionPageResponse {
        val accountId = accounts.locate(customerId)
        val page =
            queries.listTransactions(
                ListPointTransactionsCommand(
                    actor = PointAccountReadActor(customerId, PointAccountReadActorType.CUSTOMER),
                    accountId = accountId,
                    accessReason = null,
                    cursor = cursor,
                    limit = limit,
                    now = now,
                ),
            )
        val bindings =
            try {
                repository.findAccrualOrders(
                    accountId,
                    page.items
                        .filter {
                            it.type == PointTransactionViewType.ACCRUAL
                        }.mapTo(mutableSetOf()) { it.transactionId },
                )
            } catch (failure: DataAccessException) {
                throw DomainFailure(
                    FailureCode.DEPENDENCY_UNAVAILABLE,
                    "Point accrual context lookup is unavailable",
                ).also { it.initCause(failure) }
            }
        val displays = orderDisplays.find(customerId, bindings.values.toSet()).associateBy { it.orderId }
        if (displays.keys != bindings.values.toSet()) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Point accrual order context is unavailable")
        }
        return CustomerPointTransactionPageResponse(
            items =
                page.items.map { transaction ->
                    val display = bindings[transaction.transactionId]?.let { displays.getValue(it) }
                    CustomerPointTransactionResponse(
                        transaction.transactionId,
                        transaction.type,
                        transaction.amountKrw,
                        transaction.occurredAt,
                        transaction.sourceReference,
                        display?.let { CustomerPointOrderContextResponse(it.publicReference, it.storeName, it.firstMenuName) },
                    )
                },
            page = PointAccountPageInfoResponse(page.nextCursor),
        )
    }
}
