package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.loyalty.api.ListPointTransactionsCommand
import io.github.kdh949.beanflow.loyalty.api.PointAccountQueryOperations
import io.github.kdh949.beanflow.loyalty.api.PointAccountReadActorType
import io.github.kdh949.beanflow.loyalty.api.PointAccountView
import io.github.kdh949.beanflow.loyalty.api.PointTransactionPage
import io.github.kdh949.beanflow.loyalty.api.PointTransactionView
import io.github.kdh949.beanflow.loyalty.api.PointTransactionViewType
import io.github.kdh949.beanflow.loyalty.api.ReadPointAccountCommand
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.PersistenceException
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.UUID

internal data class PreparedPointTransactionPage(
    val limit: Int,
    val after: PointTransactionSort?,
    val cursorScope: SignedCursorScope<PointTransactionSort>,
    val cursorExpiresAt: Instant,
)

@Service
internal class PointAccountQueryService(
    private val customerReads: CustomerPointAccountReadTransaction,
    private val supportReads: SupportPointAccountReadTransaction,
    private val paging: PointTransactionPaging,
    private val metrics: PointAccountReadMetrics,
    private val observation: PointAccountReadObservation,
) : PointAccountQueryOperations {
    override fun get(command: ReadPointAccountCommand): PointAccountView =
        observation.completeTransaction(PointAccountReadOperation.SUMMARY, command.actor.type) {
            when (command.actor.type) {
                PointAccountReadActorType.CUSTOMER -> customerReads.get(command)
                PointAccountReadActorType.PLATFORM_OPERATOR -> supportReads.get(command)
            }
        }

    override fun listTransactions(command: ListPointTransactionsCommand): PointTransactionPage {
        val prepared =
            try {
                paging.prepare(command)
            } catch (failure: DomainFailure) {
                metrics.record(PointAccountReadOperation.TRANSACTIONS, command.actor.type, failure.toOutcome())
                throw failure
            }
        return observation.completeTransaction(PointAccountReadOperation.TRANSACTIONS, command.actor.type) {
            when (command.actor.type) {
                PointAccountReadActorType.CUSTOMER -> customerReads.listTransactions(command, prepared)
                PointAccountReadActorType.PLATFORM_OPERATOR -> supportReads.listTransactions(command, prepared)
            }
        }
    }
}

@Component
internal class CustomerPointAccountReadTransaction(
    private val workflow: PointAccountReadWorkflow,
    private val observation: PointAccountReadObservation,
) {
    @Transactional(readOnly = true)
    fun get(command: ReadPointAccountCommand): PointAccountView =
        observation.read(PointAccountReadOperation.SUMMARY, PointAccountReadActorType.CUSTOMER) {
            workflow.get(command.accountId, command.actor.actorId)
        }

    @Transactional(readOnly = true)
    fun listTransactions(
        command: ListPointTransactionsCommand,
        prepared: PreparedPointTransactionPage,
    ): PointTransactionPage =
        observation.read(PointAccountReadOperation.TRANSACTIONS, PointAccountReadActorType.CUSTOMER, PointTransactionPage::items) {
            workflow.listTransactions(command.accountId, command.actor.actorId, prepared)
        }
}

@Component
internal class SupportPointAccountReadTransaction(
    private val workflow: PointAccountReadWorkflow,
    private val observation: PointAccountReadObservation,
    private val authorization: OperatorPermissionAuthorization,
    private val audits: AuditRecordOperations,
    private val correlationIds: CorrelationIdSource,
    private val identifiers: IdentifierSource,
) {
    @Transactional
    fun get(command: ReadPointAccountCommand): PointAccountView =
        observation.read(PointAccountReadOperation.SUMMARY, PointAccountReadActorType.PLATFORM_OPERATOR) {
            val reason = normalizePointAccountAccessReason(command.accessReason)
            authorization.requireActive(command.actor.actorId, OperatorPermission.POINT_ACCOUNT_READ)
            workflow.get(command.accountId, expectedCustomerId = null).also {
                audit(command, reason, PointAccountReadOperation.SUMMARY)
            }
        }

    @Transactional
    fun listTransactions(
        command: ListPointTransactionsCommand,
        prepared: PreparedPointTransactionPage,
    ): PointTransactionPage =
        observation.read(
            PointAccountReadOperation.TRANSACTIONS,
            PointAccountReadActorType.PLATFORM_OPERATOR,
            PointTransactionPage::items,
        ) {
            val reason = normalizePointAccountAccessReason(command.accessReason)
            authorization.requireActive(command.actor.actorId, OperatorPermission.POINT_ACCOUNT_READ)
            workflow.listTransactions(command.accountId, expectedCustomerId = null, prepared).also {
                audit(command, reason, PointAccountReadOperation.TRANSACTIONS)
            }
        }

    private fun audit(
        command: ReadPointAccountCommand,
        reason: String,
        operation: PointAccountReadOperation,
    ) {
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = command.actor.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    action = "POINT_ACCOUNT_READ",
                    targetType = "POINT_ACCOUNT",
                    targetId = command.accountId,
                    occurredAt = command.now,
                    reason = reason,
                    afterSummary = mapOf("operation" to operation.name),
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = "point-account-read:${identifiers.next()}",
                ),
            ),
        )
    }

    private fun audit(
        command: ListPointTransactionsCommand,
        reason: String,
        operation: PointAccountReadOperation,
    ) {
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = command.actor.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    action = "POINT_ACCOUNT_READ",
                    targetType = "POINT_ACCOUNT",
                    targetId = command.accountId,
                    occurredAt = command.now,
                    reason = reason,
                    afterSummary = mapOf("operation" to operation.name),
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = "point-account-read:${identifiers.next()}",
                ),
            ),
        )
    }
}

@Component
internal class PointAccountReadWorkflow(
    private val repository: PointAccountQueryRepository,
    private val signedCursorCodec: SignedCursorCodec,
) {
    fun get(
        accountId: UUID,
        expectedCustomerId: UUID?,
    ): PointAccountView = account(accountId, expectedCustomerId).toView()

    fun listTransactions(
        accountId: UUID,
        expectedCustomerId: UUID?,
        prepared: PreparedPointTransactionPage,
    ): PointTransactionPage {
        account(accountId, expectedCustomerId)
        val fetched = repository.findTransactions(accountId, prepared.after, prepared.limit + 1)
        val items = fetched.take(prepared.limit).map(PointTransactionProjection::toPublicView)
        val nextCursor =
            if (fetched.size > prepared.limit) {
                val last = fetched[prepared.limit - 1]
                signedCursorCodec.issue(
                    prepared.cursorScope,
                    PointTransactionSort(last.occurredAt, last.transactionId),
                    prepared.cursorExpiresAt,
                )
            } else {
                null
            }
        return PointTransactionPage(items, nextCursor)
    }

    private fun account(
        accountId: UUID,
        expectedCustomerId: UUID?,
    ): PointAccountSummaryProjection {
        val account = repository.findAccount(accountId) ?: notFound()
        if (expectedCustomerId != null && account.customerId != expectedCustomerId) accessDenied()
        return account
    }

    private fun PointAccountSummaryProjection.toView() =
        PointAccountView(
            accountId = accountId,
            availablePointsKrw = availablePointsKrw,
            recoveryPendingKrw = recoveryPendingKrw,
        )

    private fun accessDenied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Point account ownership is required")

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Point account was not found")

    private fun dependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)
}

internal fun PointTransactionProjection.toPublicView(): PointTransactionView {
    if (amountKrw <= 0 || sourceReference.isBlank()) pointTransactionDependency("Point transaction projection is invalid")
    val viewType =
        try {
            PointTransactionViewType.valueOf(type)
        } catch (_: IllegalArgumentException) {
            pointTransactionDependency("Point transaction type is not supported by the public contract")
        }
    val effect =
        try {
            PointBalanceEffect.valueOf(balanceEffect)
        } catch (_: IllegalArgumentException) {
            pointTransactionDependency("Point transaction balance effect is invalid")
        }
    if (!viewType.allows(effect)) pointTransactionDependency("Point transaction type and balance effect do not match")
    return PointTransactionView(
        transactionId = transactionId,
        type = viewType,
        amountKrw = effect.toSignedAmount(amountKrw),
        occurredAt = occurredAt,
        sourceReference = sourceReference,
    )
}

private fun PointTransactionViewType.allows(effect: PointBalanceEffect): Boolean =
    when (this) {
        PointTransactionViewType.ACCRUAL,
        PointTransactionViewType.RESTORE,
        PointTransactionViewType.COMPENSATION,
        -> effect == PointBalanceEffect.CREDIT

        PointTransactionViewType.USE,
        PointTransactionViewType.EXPIRATION,
        PointTransactionViewType.RECOVERY,
        -> effect == PointBalanceEffect.DEBIT

        PointTransactionViewType.RESTORE_SKIPPED_EXPIRED -> effect == PointBalanceEffect.NONE

        PointTransactionViewType.ADJUSTMENT -> effect == PointBalanceEffect.CREDIT || effect == PointBalanceEffect.DEBIT
    }

private fun PointBalanceEffect.toSignedAmount(amount: Long): Long =
    when (this) {
        PointBalanceEffect.CREDIT -> amount
        PointBalanceEffect.DEBIT -> -amount
        PointBalanceEffect.NONE -> 0
    }

private fun pointTransactionDependency(message: String): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message)

@Component
internal class PointTransactionPaging(
    private val signedCursorCodec: SignedCursorCodec,
) {
    fun prepare(command: ListPointTransactionsCommand): PreparedPointTransactionPage {
        val limit = command.limit ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_LIMIT) invalid("Point transaction limit must be between 1 and 100")
        if (command.cursor?.length ?: 0 > MAX_CURSOR_LENGTH) invalid("Point transaction cursor is too long")
        val scope =
            SignedCursorScope(
                endpoint = CURSOR_ENDPOINT,
                filterHash = sha256("$CURSOR_ENDPOINT|accountId=${command.accountId}"),
                sortAdapter = SORT_ADAPTER,
            )
        return PreparedPointTransactionPage(
            limit = limit,
            after = command.cursor?.let { signedCursorCodec.verify(it, scope).sort },
            cursorScope = scope,
            cursorExpiresAt = command.now.plus(CURSOR_TTL),
        )
    }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val MAX_CURSOR_LENGTH = 2048
        const val CURSOR_ENDPOINT = "point-account-transactions"
        val CURSOR_TTL: Duration = Duration.ofHours(24)
        val SORT_ADAPTER =
            object : CursorSortAdapter<PointTransactionSort> {
                override fun encode(sort: PointTransactionSort): List<String> =
                    listOf(sort.occurredAt.toString(), sort.transactionId.toString())

                override fun decode(values: List<String>): PointTransactionSort? {
                    if (values.size != 2) return null
                    return try {
                        val occurredAt = Instant.parse(values[0])
                        val transactionId = UUID.fromString(values[1])
                        if (occurredAt.toString() != values[0] || transactionId.toString() != values[1]) {
                            null
                        } else {
                            PointTransactionSort(occurredAt, transactionId)
                        }
                    } catch (_: DateTimeParseException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            }
    }
}

internal enum class PointAccountReadOperation {
    SUMMARY,
    TRANSACTIONS,
}

internal enum class PointAccountReadOutcome {
    SUCCEEDED,
    INVALID_INPUT,
    DENIED,
    NOT_FOUND,
    DEPENDENCY_UNAVAILABLE,
}

@Component
internal class PointAccountReadObservation(
    private val metrics: PointAccountReadMetrics,
) {
    private val completionOutcomeRecorded = ThreadLocal<Boolean>()

    fun <T> completeTransaction(
        operation: PointAccountReadOperation,
        actorType: PointAccountReadActorType,
        block: () -> T,
    ): T {
        completionOutcomeRecorded.set(false)
        return try {
            block()
        } catch (failure: TransactionException) {
            if (completionOutcomeRecorded.get() != true) {
                metrics.record(operation, actorType, PointAccountReadOutcome.DEPENDENCY_UNAVAILABLE)
            }
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Point account read transaction could not commit").also {
                it.initCause(failure)
            }
        } finally {
            completionOutcomeRecorded.remove()
        }
    }

    fun <T> read(
        operation: PointAccountReadOperation,
        actorType: PointAccountReadActorType,
        pageItems: ((T) -> List<*>)? = null,
        block: () -> T,
    ): T =
        try {
            block().also { result ->
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun afterCompletion(status: Int) {
                            if (completionOutcomeRecorded.get() != null) {
                                completionOutcomeRecorded.set(true)
                            }
                            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                                metrics.record(operation, actorType, PointAccountReadOutcome.SUCCEEDED, pageItems?.invoke(result)?.size)
                            } else {
                                metrics.record(operation, actorType, PointAccountReadOutcome.DEPENDENCY_UNAVAILABLE)
                            }
                        }
                    },
                )
            }
        } catch (failure: DomainFailure) {
            metrics.record(operation, actorType, failure.toOutcome())
            throw failure
        } catch (failure: DataAccessException) {
            metrics.record(operation, actorType, PointAccountReadOutcome.DEPENDENCY_UNAVAILABLE)
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Point account query persistence is unavailable").also {
                it.initCause(failure)
            }
        } catch (failure: PersistenceException) {
            metrics.record(operation, actorType, PointAccountReadOutcome.DEPENDENCY_UNAVAILABLE)
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Point account read transaction could not commit").also {
                it.initCause(failure)
            }
        }
}

@Component
internal class PointAccountReadMetrics(
    private val meterRegistry: MeterRegistry,
) {
    fun record(
        operation: PointAccountReadOperation,
        actorType: PointAccountReadActorType,
        outcome: PointAccountReadOutcome,
        pageSize: Int? = null,
    ) {
        val counter =
            when (operation) {
                PointAccountReadOperation.SUMMARY -> "beanflow.loyalty.point_account.read.count"
                PointAccountReadOperation.TRANSACTIONS -> "beanflow.loyalty.point_transaction.page.count"
            }
        meterRegistry.counter(counter, "actor_type", actorType.name, "outcome", outcome.name).increment()
        if (operation == PointAccountReadOperation.TRANSACTIONS && pageSize != null) {
            meterRegistry
                .summary("beanflow.loyalty.point_transaction.page.size", "actor_type", actorType.name)
                .record(pageSize.toDouble())
        }
    }
}

internal fun normalizePointAccountAccessReason(reason: String?): String {
    val normalized = reason?.trim().orEmpty()
    if (normalized.length !in 1..200 || reason?.hasControlCharacter() != false) {
        throw DomainFailure(FailureCode.INVALID_REQUEST, "Access reason must contain between 1 and 200 non-control characters")
    }
    return normalized
}

private fun String.hasControlCharacter(): Boolean = any { it.code < 0x20 || it.code == 0x7f }

private fun DomainFailure.toOutcome(): PointAccountReadOutcome =
    when (code) {
        FailureCode.INVALID_REQUEST -> PointAccountReadOutcome.INVALID_INPUT
        FailureCode.ACCESS_DENIED -> PointAccountReadOutcome.DENIED
        FailureCode.RESOURCE_NOT_FOUND -> PointAccountReadOutcome.NOT_FOUND
        else -> PointAccountReadOutcome.DEPENDENCY_UNAVAILABLE
    }
