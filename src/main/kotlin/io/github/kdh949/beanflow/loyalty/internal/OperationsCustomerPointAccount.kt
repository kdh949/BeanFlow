package io.github.kdh949.beanflow.loyalty.internal

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
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.persistence.PersistenceException
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

internal data class OperationsCustomerPointAccountResponse(
    val customerId: UUID,
    val accountId: UUID,
)

@RestController
@RequestMapping("/api/v1/operations/customers/{customerId}/point-account")
internal class OperationsCustomerPointAccountController(
    private val service: OperationsCustomerPointAccountService,
) {
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun resolve(
        actor: OperatorActor,
        @PathVariable customerId: UUID,
        @RequestHeader("X-Access-Reason") reason: String,
    ): ResponseEntity<OperationsCustomerPointAccountResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.resolve(actor.actorId, customerId, reason))
}

/** Commit failures must not return an unaudited customer-account mapping. */
@Service
internal class OperationsCustomerPointAccountService(
    private val transaction: OperationsCustomerPointAccountTransaction,
) {
    fun resolve(
        actorId: UUID,
        customerId: UUID,
        reason: String,
    ): OperationsCustomerPointAccountResponse =
        try {
            transaction.resolve(actorId, customerId, reason)
        } catch (_: DataAccessException) {
            unavailable()
        } catch (_: TransactionException) {
            unavailable()
        } catch (_: PersistenceException) {
            unavailable()
        }

    private fun unavailable(): Nothing =
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer point account lookup is unavailable")
}

@Service
internal class OperationsCustomerPointAccountTransaction(
    private val permissions: OperatorPermissionAuthorization,
    private val accounts: CustomerPointAccountLocator,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val correlationIds: CorrelationIdSource,
    private val clock: Clock,
) {
    @Transactional
    fun resolve(
        actorId: UUID,
        customerId: UUID,
        reason: String,
    ): OperationsCustomerPointAccountResponse {
        permissions.requireActive(actorId, OperatorPermission.POINT_ACCOUNT_READ)
        if (reason != "POINT_ACCOUNT_INVESTIGATION") {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "A supported point account access reason is required")
        }
        val accountId = accounts.locate(customerId)
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.PII_ACCESS,
                    action = "POINT_ACCOUNT_RESOLVED",
                    targetType = "POINT_ACCOUNT",
                    targetId = accountId,
                    occurredAt = clock.instant(),
                    reason = reason,
                    afterSummary = mapOf("customerId" to customerId.toString()),
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = "point-account-resolved:${identifiers.next()}",
                ),
            ),
        )
        return OperationsCustomerPointAccountResponse(customerId, accountId)
    }
}
