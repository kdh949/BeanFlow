package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.operations.api.RetentionPolicyCategory
import io.github.kdh949.beanflow.operations.api.RetentionPolicyOperations
import io.github.kdh949.beanflow.ordering.api.CustomerSupportOrderOperations
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.github.kdh949.beanflow.support.internal.domain.CustomerInquiry
import io.github.kdh949.beanflow.support.internal.domain.CustomerInquiryCategory
import io.github.kdh949.beanflow.support.internal.domain.CustomerInquiryContent
import io.github.kdh949.beanflow.support.internal.domain.InquiryMessageAuthor
import io.github.kdh949.beanflow.support.internal.domain.SupportCasePriority
import io.github.kdh949.beanflow.support.internal.domain.SupportCaseState
import io.github.kdh949.beanflow.support.internal.domain.SupportInquiryCategory
import io.github.kdh949.beanflow.support.internal.domain.SupportRequesterType
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal enum class CustomerInquiryState { RECEIVED, OPEN, IN_PROGRESS, WAITING, RESOLVED, CLOSED }

internal data class CustomerInquirySummary(
    val inquiryId: UUID,
    val title: String,
    val category: CustomerInquiryCategory,
    val state: CustomerInquiryState,
    val orderReference: String?,
    val version: Long,
    val createdAt: Instant,
)

internal data class CustomerInquiryPage(
    val items: List<CustomerInquirySummary>,
    val nextCursor: String?,
)

internal data class CustomerInquiryDetail(
    val inquiry: CustomerInquirySummary,
    val messages: List<InquiryMessage>,
    val nextMessageCursor: String?,
    val canReply: Boolean,
)

internal data class SupportInquiryDetail(
    val detail: CustomerInquiryDetail,
    val caseId: UUID?,
    val caseVersion: Long?,
    val canClaim: Boolean,
    val canReply: Boolean,
)

internal data class InquiryCommandResult(
    val inquiryId: UUID,
    val messageId: UUID?,
)

internal data class InquiryClaimResult(
    val inquiryId: UUID,
    val caseId: UUID,
)

@Service
@Transactional
internal class CustomerInquiryService(
    private val inquiries: CustomerInquiryRepository,
    private val cases: SupportCaseJpaRepository,
    private val caseService: SupportCaseApplicationService,
    private val commands: SupportCaseCommandLock,
    private val orders: CustomerSupportOrderOperations,
    private val permissions: OperatorPermissionAuthorization,
    private val retention: RetentionPolicyOperations,
    private val audits: AuditRecordOperations,
    private val ids: IdentifierSource,
    private val cursors: SignedCursorCodec,
    private val clock: Clock,
    private val canonicalizer: SupportCommandPayloadCanonicalizer,
) {
    fun customerList(
        actor: UUID,
        cursor: String?,
    ): CustomerInquiryPage = boundary { list(actor, false, cursor, actor) }

    fun supportList(
        actor: UUID,
        unclaimed: Boolean,
        cursor: String?,
    ): CustomerInquiryPage =
        boundary {
            permissions.requireActive(actor, OperatorPermission.SUPPORT_CASE_READ)
            list(null, unclaimed, cursor, actor)
        }

    fun customerDetail(
        actor: UUID,
        id: UUID,
        cursor: String?,
    ): CustomerInquiryDetail =
        boundary {
            val inquiry = owned(actor, id)
            detail(inquiry, currentCase(inquiry), cursor, actor)
        }

    fun supportDetail(
        actor: UUID,
        id: UUID,
        cursor: String?,
    ): SupportInquiryDetail =
        boundary {
            permissions.requireActive(actor, OperatorPermission.SUPPORT_CASE_READ)
            val writable = permissions.hasActive(actor, OperatorPermission.SUPPORT_CASE_WRITE)
            val inquiry = required(id)
            val case = currentCase(inquiry)
            val detail = detail(inquiry, case, cursor, actor)
            SupportInquiryDetail(
                detail,
                case?.id,
                case?.version,
                writable && case == null,
                writable && case?.currentAssigneeId == actor && detail.canReply,
            )
        }

    fun create(
        actor: UUID,
        key: String,
        title: String,
        category: CustomerInquiryCategory,
        content: String,
        orderReference: String?,
        correlation: String,
    ): InquiryCommandResult =
        boundary {
            val normalizedTitle = CustomerInquiryContent.title(title)
            val normalizedContent = CustomerInquiryContent.message(content)
            val reference = orderReference?.trim()?.takeIf { it.isNotEmpty() }
            val operation = "CUSTOMER_INQUIRY_CREATE"
            lock(actor, operation, key)
            val hash = digest(listOf(normalizedTitle, category.name, normalizedContent, reference))
            replay(actor, operation, key, hash)?.let { return@boundary InquiryCommandResult(it.inquiryId, it.messageId) }
            val orderId = reference?.let { orders.resolveOwned(actor, it) }
            val now = clock.instant()
            val policy = retention.current(RetentionPolicyCategory.SUPPORT_CASE)
            val inquiry =
                CustomerInquiry(ids.next(), actor, normalizedTitle, category, orderId, reference, null, 0, now, now, policy.policyVersionId)
            inquiries.insert(inquiry)
            val messageId = ids.next()
            inquiries.message(inquiry.id, actor, InquiryMessage(messageId, InquiryMessageAuthor.CUSTOMER, normalizedContent, now))
            remember(actor, operation, key, hash, inquiry, messageId, correlation, false)
            InquiryCommandResult(inquiry.id, messageId)
        }

    fun claim(
        actor: UUID,
        id: UUID,
        key: String,
        expectedVersion: Long,
        correlation: String,
    ): InquiryClaimResult =
        boundary {
            permissions.requireActive(actor, OperatorPermission.SUPPORT_CASE_WRITE)
            val operation = "SUPPORT_INQUIRY_CLAIM"
            lock(actor, operation, key)
            val hash = digest(listOf(id.toString(), expectedVersion.toString()))
            replay(actor, operation, key, hash)?.let {
                val caseId = required(it.inquiryId).caseId ?: unavailable()
                return@boundary InquiryClaimResult(it.inquiryId, caseId)
            }
            val inquiry = required(id, true)
            if (inquiry.caseId != null || inquiry.version != expectedVersion) conflict()
            val case =
                caseService.create(
                    CreateSupportCaseCommand(
                        actor,
                        "inquiry:$id",
                        SupportRequesterType.CUSTOMER,
                        inquiry.customerId.toString(),
                        SupportInquiryCategory.valueOf(inquiry.category.name),
                        SupportCasePriority.NORMAL,
                        "inquiry:$id",
                        "고객 앱 문의 인수",
                        correlation,
                    ),
                )
            caseService.linkSubject(
                LinkSupportSubjectCommand(
                    actor,
                    case.caseId,
                    "inquiry-customer:$id",
                    SupportSubjectType.CUSTOMER,
                    inquiry.customerId,
                    SupportSubjectRelationship.REQUESTER,
                    "고객 앱 문의 요청자 연결",
                    correlation,
                ),
            )
            inquiry.orderId?.let {
                caseService.linkSubject(
                    LinkSupportSubjectCommand(
                        actor,
                        case.caseId,
                        "inquiry-order:$id",
                        SupportSubjectType.ORDER,
                        it,
                        SupportSubjectRelationship.RELATED_ORDER,
                        "고객 소유 문의 주문 연결",
                        correlation,
                    ),
                )
            }
            inquiry.claim(case.caseId, expectedVersion, clock.instant())
            inquiries.save(inquiry)
            remember(actor, operation, key, hash, inquiry, null, correlation, true)
            InquiryClaimResult(inquiry.id, case.caseId)
        }

    fun customerMessage(
        actor: UUID,
        id: UUID,
        key: String,
        expectedVersion: Long,
        content: String,
        correlation: String,
    ): InquiryCommandResult = append(actor, id, key, expectedVersion, null, content, correlation, false)

    fun supportMessage(
        actor: UUID,
        id: UUID,
        key: String,
        expectedVersion: Long,
        expectedCaseVersion: Long,
        content: String,
        correlation: String,
    ): InquiryCommandResult = append(actor, id, key, expectedVersion, expectedCaseVersion, content, correlation, true)

    private fun append(
        actor: UUID,
        id: UUID,
        key: String,
        version: Long,
        caseVersion: Long?,
        content: String,
        correlation: String,
        staff: Boolean,
    ): InquiryCommandResult =
        boundary {
            if (staff) permissions.requireActive(actor, OperatorPermission.SUPPORT_CASE_WRITE)
            val normalized = CustomerInquiryContent.message(content)
            val operation = if (staff) "SUPPORT_INQUIRY_MESSAGE" else "CUSTOMER_INQUIRY_MESSAGE"
            lock(actor, operation, key)
            val hash = digest(listOf(id.toString(), version.toString(), caseVersion?.toString(), normalized))
            replay(actor, operation, key, hash)?.let { return@boundary InquiryCommandResult(it.inquiryId, it.messageId) }
            val observed = if (staff) required(id) else owned(actor, id)
            val case = observed.caseId?.let { cases.findLockedById(it) ?: unavailable() }
            if (staff && (case == null || case.currentAssigneeId != actor)) denied()
            val inquiry = required(id, true)
            // A claim completed between the first read and lock. Refresh instead of taking Case after Inquiry.
            if (inquiry.caseId != observed.caseId) conflict()
            if (staff && case?.version != caseVersion) conflict()
            val now = clock.instant()
            inquiry.append(version, case?.state, now)
            val messageId = ids.next()
            inquiries.message(
                id,
                actor,
                InquiryMessage(messageId, if (staff) InquiryMessageAuthor.SUPPORT else InquiryMessageAuthor.CUSTOMER, normalized, now),
            )
            inquiries.save(inquiry)
            remember(actor, operation, key, hash, inquiry, messageId, correlation, staff)
            InquiryCommandResult(id, messageId)
        }

    private fun list(
        customer: UUID?,
        unclaimed: Boolean,
        cursor: String?,
        actor: UUID,
    ): CustomerInquiryPage {
        val scope = scope("support-inquiry-list", "$actor|$customer|$unclaimed")
        val page = inquiries.list(customer, unclaimed, cursor?.let { cursors.verify(it, scope).sort }, PAGE_SIZE + 1)
        val visible = page.take(PAGE_SIZE)
        val states = inquiries.caseStates(visible.mapNotNull { it.caseId }.toSet())
        return CustomerInquiryPage(
            visible.map {
                summary(it, it.caseId?.let { caseId -> states[caseId] ?: unavailable() })
            },
            next(page.size, visible.lastOrNull()?.let { InquirySort(it.createdAt, it.id) }, scope),
        )
    }

    private fun detail(
        inquiry: CustomerInquiry,
        case: SupportCaseEntity?,
        cursor: String?,
        actor: UUID,
    ): CustomerInquiryDetail {
        val scope = scope("support-inquiry-messages", "$actor|${inquiry.id}")
        val page = inquiries.messages(inquiry.id, cursor?.let { cursors.verify(it, scope).sort }, PAGE_SIZE + 1)
        val visible = page.take(PAGE_SIZE)
        return CustomerInquiryDetail(
            summary(inquiry, case?.state),
            visible,
            next(page.size, visible.lastOrNull()?.let { InquirySort(it.createdAt, it.id) }, scope),
            case?.state !in setOf(SupportCaseState.RESOLVED, SupportCaseState.CLOSED),
        )
    }

    private fun summary(
        value: CustomerInquiry,
        caseState: SupportCaseState?,
    ) = CustomerInquirySummary(
        value.id,
        value.title,
        value.category,
        if (caseState == null) {
            CustomerInquiryState.RECEIVED
        } else {
            CustomerInquiryState.valueOf(caseState.name)
        },
        value.orderReference,
        value.version,
        value.createdAt,
    )

    private fun currentCase(value: CustomerInquiry): SupportCaseEntity? =
        value.caseId?.let {
            cases.findById(it).orElse(null)
                ?: unavailable()
        }

    private fun required(
        id: UUID,
        lock: Boolean = false,
    ): CustomerInquiry = inquiries.find(id, lock) ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Inquiry was not found")

    private fun owned(
        actor: UUID,
        id: UUID,
    ): CustomerInquiry =
        required(id).also {
            if (it.customerId !=
                actor
            ) {
                throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Inquiry was not found")
            }
        }

    private fun lock(
        actor: UUID,
        operation: String,
        key: String,
    ) {
        require(key.length in 8..128 && key.none(Char::isISOControl) && key == key.trim())
        commands.lock(null, actor, operation, key)
    }

    private fun replay(
        actor: UUID,
        operation: String,
        key: String,
        hash: String,
    ): InquiryReplay? =
        inquiries.replay(actor, operation, key, clock.instant())?.also {
            if (it.payloadHash != hash) throw DomainFailure(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency key payload differs")
        }

    private fun remember(
        actor: UUID,
        operation: String,
        key: String,
        hash: String,
        inquiry: CustomerInquiry,
        message: UUID?,
        correlation: String,
        staff: Boolean,
    ) {
        val now = clock.instant()
        inquiries.remember(actor, operation, key, hash, inquiry.id, message, now)
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actor.toString(),
                    if (staff) AuditActorType.PLATFORM_OPERATOR else AuditActorType.CUSTOMER,
                    AuditCategory.OPERATIONS_POLICY,
                    operation,
                    "CUSTOMER_INQUIRY",
                    inquiry.id,
                    now,
                    "NATIVE_CUSTOMER_SUPPORT",
                    afterSummary =
                        mapOf("inquiryVersion" to inquiry.version.toString()),
                    correlationId = correlation,
                    sourceReference = "inquiry:${inquiry.id}:$operation:${message ?: inquiry.version}",
                ),
            ),
        )
    }

    private fun digest(values: List<String?>): String =
        SupportSha256.utf8(
            canonicalizer.canonical(
                "customer-inquiry:v1",
                values.mapIndexed { index, value -> SupportCommandPayloadField(index.toString(), "string", value) },
            ),
        )

    private fun scope(
        endpoint: String,
        filter: String,
    ) = SignedCursorScope(endpoint, SupportSha256.utf8(filter), SORT_ADAPTER)

    private fun next(
        size: Int,
        last: InquirySort?,
        scope: SignedCursorScope<InquirySort>,
    ): String? =
        if (size > PAGE_SIZE &&
            last != null
        ) {
            cursors.issue(scope, last, clock.instant().plusSeconds(1800))
        } else {
            null
        }

    private fun denied(): Nothing = throw DomainFailure(FailureCode.ACCESS_DENIED, "Current inquiry assignment is required")

    private fun conflict(): Nothing = throw DomainFailure(FailureCode.RESOURCE_STATE_CONFLICT, "Inquiry or case state changed")

    private fun unavailable(): Nothing = throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Inquiry case context is unavailable")

    private fun <T> boundary(block: () -> T): T =
        try {
            block()
        } catch (_: DataAccessException) {
            unavailable()
        } catch (_: IllegalArgumentException) {
            throw DomainFailure(FailureCode.INVALID_REQUEST, "Inquiry content or parameters are not permitted")
        } catch (_: IllegalStateException) {
            conflict()
        }

    private companion object {
        const val PAGE_SIZE = 20
        val SORT_ADAPTER =
            object : CursorSortAdapter<InquirySort> {
                override fun encode(sort: InquirySort) = listOf(sort.createdAt.toString(), sort.id.toString())

                override fun decode(values: List<String>): InquirySort? =
                    try {
                        if (values.size ==
                            2
                        ) {
                            InquirySort(Instant.parse(values[0]), UUID.fromString(values[1]))
                        } else {
                            null
                        }
                    } catch (_: IllegalArgumentException) {
                        null
                    } catch (_: java.time.format.DateTimeParseException) {
                        null
                    }
            }
    }
}
