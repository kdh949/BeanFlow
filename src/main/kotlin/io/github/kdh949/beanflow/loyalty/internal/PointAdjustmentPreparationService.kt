package io.github.kdh949.beanflow.loyalty.internal

import io.github.kdh949.beanflow.loyalty.api.ApplyPointAdjustmentCommand
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentCustomerLabel
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentCustomerLabelQuery
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentIssuer
import io.github.kdh949.beanflow.loyalty.api.PointAdjustmentResult
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import jakarta.persistence.PersistenceException
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

internal data class PointAdjustmentPreparationView(
    val preparationId: UUID,
    val accountId: UUID,
    val customer: PointAdjustmentCustomerLabel,
    val request: PointAdjustmentRequest,
    val state: PointAdjustmentPreparationState,
    val canExecute: Boolean,
    val result: PointAdjustmentResult?,
)

internal data class CurrentPointAdjustmentPreparation(
    val preparation: PointAdjustmentPreparationView?,
)

@Service
internal class PointAdjustmentPreparationService(
    private val transaction: PointAdjustmentPreparationTransaction,
) {
    fun current(actor: UUID) = boundary { transaction.current(actor) }

    fun prepare(
        actor: UUID,
        account: UUID,
        request: PointAdjustmentRequest,
    ) = boundary { transaction.prepare(actor, account, request) }

    fun dismiss(
        actor: UUID,
        id: UUID,
        expected: PointAdjustmentPreparationState,
    ) = boundary { transaction.dismiss(actor, id, expected) }

    private fun <T> boundary(block: () -> T): T =
        try {
            block()
        } catch (_: DataAccessException) {
            unavailable()
        } catch (_: PersistenceException) {
            unavailable()
        } catch (_: TransactionException) {
            unavailable()
        }

    private fun unavailable(): Nothing =
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Point adjustment preparation is unavailable")
}

@Service
internal class PointAdjustmentPreparationTransaction(
    private val records: PointAdjustmentPreparationRepository,
    private val accounts: PointAccountJpaRepository,
    private val labels: PointAdjustmentCustomerLabelQuery,
    private val permissions: OperatorPermissionAuthorization,
    private val adjustments: PointAdjustmentService,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val correlations: CorrelationIdSource,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional
    fun current(actor: UUID): CurrentPointAdjustmentPreparation {
        permissions.requireActive(actor, OperatorPermission.POINT_ACCOUNT_READ)
        val record = records.findOpen(actor) ?: return CurrentPointAdjustmentPreparation(null)
        permissions.requireActive(actor, OperatorPermission.CUSTOMER_ACCOUNT_SEARCH)
        val result = view(record)
        audit(record, "POINT_ADJUSTMENT_PREPARATION_READ", AuditCategory.PII_ACCESS)
        return CurrentPointAdjustmentPreparation(result)
    }

    @Transactional
    fun prepare(
        actor: UUID,
        accountId: UUID,
        request: PointAdjustmentRequest,
    ): PointAdjustmentPreparationView {
        accounts.findLockedById(accountId) ?: missing()
        permissions.requireActive(actor, OperatorPermission.POINT_ACCOUNT_READ)
        permissions.requireActive(actor, OperatorPermission.CUSTOMER_ACCOUNT_SEARCH)
        permissions.requireActive(actor, OperatorPermission.POINT_ADJUSTMENT)
        val id = identifiers.next()
        val normalized =
            adjustments.normalize(
                ApplyPointAdjustmentCommand(
                    actor,
                    accountId,
                    id.toString(),
                    request.amountKrw,
                    request.issuer?.let { PointAdjustmentIssuer(it.issuerType, it.issuerReference) },
                    request.expiresAt,
                    request.reason,
                    request.evidenceReferences,
                    correlations.currentOrCreate(),
                    clock.instant(),
                ),
            )
        val hash = CanonicalPointAdjustmentPayload.hash(normalized)
        records.findOpen(actor, true)?.let {
            if (it.accountId != accountId || it.payloadHash != hash) conflict()
            return view(it)
        }
        if (normalized.amountKrw > 0 && normalized.expiresAt?.isAfter(normalized.now) != true) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Credit preparation requires a future expiry")
        }
        val body =
            PointAdjustmentRequest(
                normalized.amountKrw,
                normalized.issuer?.let { PointAdjustmentIssuerRequest(it.issuerType, it.issuerReference) },
                normalized.expiresAt,
                normalized.reason,
                normalized.evidenceReferences,
            )
        val record =
            PointAdjustmentPreparation(
                id,
                actor,
                accountId,
                mapper.writeValueAsString(body),
                hash,
                PointAdjustmentPreparationState.PREPARED,
                null,
                normalized.now,
                null,
            )
        val result = view(record)
        records.insert(record)
        audit(record, "POINT_ADJUSTMENT_PREPARED", AuditCategory.OPERATIONS_POLICY)
        return result
    }

    @Transactional
    fun dismiss(
        actor: UUID,
        id: UUID,
        expected: PointAdjustmentPreparationState,
    ) {
        val observed = records.find(actor, id) ?: missing()
        accounts.findLockedById(observed.accountId) ?: missing()
        permissions.requireActive(actor, OperatorPermission.POINT_ACCOUNT_READ)
        val record = records.find(actor, id, true) ?: missing()
        if (expected == PointAdjustmentPreparationState.CANCELLED) conflict()
        if (record.dismissedAt != null) {
            if (record.state == expected || (
                    expected == PointAdjustmentPreparationState.PREPARED &&
                        record.state == PointAdjustmentPreparationState.CANCELLED
                )
            ) {
                return
            }
            conflict()
        }
        if (record.state != expected) conflict()
        val now = clock.instant()
        records.dismiss(record, now)
        val dismissed =
            record.copy(
                state =
                    if (record.state ==
                        PointAdjustmentPreparationState.PREPARED
                    ) {
                        PointAdjustmentPreparationState.CANCELLED
                    } else {
                        record.state
                    },
                dismissedAt = now,
            )
        audit(dismissed, "POINT_ADJUSTMENT_PREPARATION_DISMISSED", AuditCategory.OPERATIONS_POLICY)
    }

    private fun view(record: PointAdjustmentPreparation): PointAdjustmentPreparationView {
        val account = accounts.findById(record.accountId).orElse(null) ?: missing()
        val customer =
            labels.find(account.customerId)
                ?: throw DomainFailure(FailureCode.POINT_ACCOUNT_INTEGRITY_FAILURE, "Prepared customer identity is unavailable")
        return PointAdjustmentPreparationView(
            record.id,
            record.accountId,
            customer,
            mapper.readValue(record.requestBody, PointAdjustmentRequest::class.java),
            record.state,
            record.state == PointAdjustmentPreparationState.PREPARED &&
                permissions.hasActive(record.actorId, OperatorPermission.POINT_ADJUSTMENT),
            record.responseBody?.let { mapper.readValue(it, PointAdjustmentResult::class.java) },
        )
    }

    private fun audit(
        record: PointAdjustmentPreparation,
        action: String,
        category: AuditCategory,
    ) {
        val event = identifiers.next()
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = record.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = category,
                    action = action,
                    targetType = "POINT_ADJUSTMENT_PREPARATION",
                    targetId = record.id,
                    occurredAt = clock.instant(),
                    reason = "POINT_ACCOUNT_INVESTIGATION",
                    afterSummary = mapOf("state" to record.state.name),
                    correlationId = correlations.currentOrCreate(),
                    sourceReference = "point-preparation:$event",
                ),
            ),
        )
    }

    private fun missing(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Point adjustment preparation was not found")

    private fun conflict(): Nothing =
        throw DomainFailure(FailureCode.RESOURCE_STATE_CONFLICT, "An unacknowledged point adjustment exists or changed")
}
