package io.github.kdh949.beanflow.identity.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
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
import io.github.kdh949.beanflow.shared.api.PersonalDataMasker
import jakarta.persistence.PersistenceException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionException
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

internal enum class CustomerSearchReasonCode { POINT_ACCOUNT_INVESTIGATION }

internal data class OperationsCustomerSearchRequest(
    @field:Size(max = 100)
    val loginId: String,
    val reasonCode: CustomerSearchReasonCode,
) {
    @JsonAnySetter
    fun rejectUnknownField(
        @Suppress("UNUSED_PARAMETER") name: String,
        @Suppress("UNUSED_PARAMETER") value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown customer search field")

    override fun toString(): String = "OperationsCustomerSearchRequest(loginId=<redacted>, reasonCode=$reasonCode)"
}

internal data class OperationsCustomerSearchItem(
    val customerId: UUID,
    val maskedLoginId: String,
    val maskedDisplayName: String,
)

internal data class OperationsCustomerSearchResult(
    val items: List<OperationsCustomerSearchItem>,
)

@RestController
@RequestMapping("/api/v1/operations/customer-searches")
internal class OperationsCustomerSearchController(
    private val service: OperationsCustomerSearchService,
) {
    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun search(
        actor: OperatorActor,
        @Valid @RequestBody request: OperationsCustomerSearchRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<OperationsCustomerSearchResult> {
        if (servletRequest.parameterMap.isNotEmpty()) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Customer search does not accept query parameters")
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.search(actor.actorId, request))
    }
}

@Service
internal class OperationsCustomerSearchService(
    private val transaction: OperationsCustomerSearchTransaction,
) {
    fun search(
        actorId: UUID,
        request: OperationsCustomerSearchRequest,
    ): OperationsCustomerSearchResult =
        try {
            transaction.search(actorId, request)
        } catch (_: DataAccessException) {
            unavailable()
        } catch (_: TransactionException) {
            unavailable()
        } catch (_: PersistenceException) {
            unavailable()
        }

    private fun unavailable(): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer search is unavailable")
}

@Service
internal class OperationsCustomerSearchTransaction(
    private val permissions: OperatorPermissionAuthorization,
    private val passwords: CustomerPasswordSecurity,
    private val repository: OperationsCustomerSearchRepository,
    private val audits: AuditRecordOperations,
    private val identifiers: IdentifierSource,
    private val correlationIds: CorrelationIdSource,
    private val clock: Clock,
) {
    @Transactional
    fun search(
        actorId: UUID,
        request: OperationsCustomerSearchRequest,
    ): OperationsCustomerSearchResult {
        permissions.requireActive(actorId, OperatorPermission.CUSTOMER_ACCOUNT_SEARCH)
        val loginId = passwords.validateLoginId(request.loginId)
        val items = repository.findExact(loginId)
        val searchId = identifiers.next()
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.PII_ACCESS,
                    action = "CUSTOMER_ACCOUNT_SEARCHED",
                    targetType = "CUSTOMER_ACCOUNT_SEARCH",
                    targetId = searchId,
                    occurredAt = clock.instant(),
                    reason = request.reasonCode.name,
                    afterSummary = mapOf("matchedCount" to items.size.toString()),
                    correlationId = correlationIds.currentOrCreate(),
                    sourceReference = "customer-account-search:$searchId",
                ),
            ),
        )
        return OperationsCustomerSearchResult(items)
    }
}

@Repository
internal class OperationsCustomerSearchRepository(
    private val jdbc: JdbcTemplate,
) {
    fun findExact(loginId: String): List<OperationsCustomerSearchItem> =
        jdbc.query(
            "SELECT id, login_id, display_name FROM identity_customer_account WHERE login_id = ?",
            { row, _ ->
                OperationsCustomerSearchItem(
                    customerId = row.getObject("id", UUID::class.java),
                    maskedLoginId = row.getString("login_id").take(1) + "***",
                    maskedDisplayName = maskStoredLabel(row.getString("display_name")),
                )
            },
            loginId,
        )

    private fun maskStoredLabel(label: String): String =
        try {
            PersonalDataMasker.maskDisplayLabel(label)
        } catch (_: DomainFailure) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer display projection is invalid")
        }
}
