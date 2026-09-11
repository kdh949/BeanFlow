package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.delivery.api.ExternalCourierSupportProfileQueryOperations
import io.github.kdh949.beanflow.identity.api.CustomerSupportProfileQueryOperations
import io.github.kdh949.beanflow.merchant.api.StoreSupportProfileQueryOperations
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.ordering.api.OrderingSupportTimelineOperations
import io.github.kdh949.beanflow.ordering.api.SupportOrderDisplay
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

internal enum class SupportSubjectDisplayState { AVAILABLE, MISSING_PROFILE, REQUIRES_PERMISSION }

internal data class SupportSubjectDisplay(
    val state: SupportSubjectDisplayState,
    val label: String? = null,
) {
    override fun toString(): String = "SupportSubjectDisplay(state=$state, label=<redacted>)"
}

@Service
internal class SupportSubjectSelectionService(
    private val permissions: OperatorPermissionAuthorization,
    private val customers: CustomerSupportProfileQueryOperations,
    private val stores: StoreSupportProfileQueryOperations,
    private val couriers: ExternalCourierSupportProfileQueryOperations,
    private val ordering: OrderingSupportTimelineOperations,
    private val authorization: SupportTimelineAuthorization,
    private val audits: AuditRecordOperations,
    private val correlations: CorrelationIdSource,
    private val identifiers: IdentifierSource,
    private val clock: Clock,
) {
    @Transactional
    fun displays(
        actorId: UUID,
        caseId: UUID,
        links: List<SupportSubjectLinkResource>,
    ): List<SupportSubjectLinkResource> {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_READ)
        val masked = permissions.hasActive(actorId, OperatorPermission.SUPPORT_SUBJECT_SEARCH)
        val orderRead = permissions.hasActive(actorId, OperatorPermission.SUPPORT_ORDER_READ)
        val labels = mutableMapOf<Pair<SupportSubjectType, UUID>, String>()
        for ((type, group) in links.groupBy { it.subjectType }) {
            if (if (type == SupportSubjectType.ORDER) !orderRead else !masked) continue
            for (ids in group.map { it.subjectId }.distinct().chunked(100)) {
                val names =
                    when (type) {
                        SupportSubjectType.CUSTOMER -> {
                            customers.findMaskedNames(ids.toSet())
                        }

                        SupportSubjectType.STORE -> {
                            stores.findMaskedNames(ids.toSet())
                        }

                        SupportSubjectType.DELIVERY -> {
                            couriers.findMaskedNames(ids.toSet())
                        }

                        SupportSubjectType.ORDER -> {
                            ordering.findOrderDisplays(ids.toSet()).mapValues { (_, order) ->
                                "${order.publicReference} · ${order.storeName}"
                            }
                        }
                    }
                names.forEach { (id, label) -> labels[type to id] = label }
            }
        }
        if (links.any {
                if (it.subjectType ==
                    SupportSubjectType.ORDER
                ) {
                    orderRead
                } else {
                    masked
                }
            }
        ) {
            audit(actorId, caseId, "LINKED_SUBJECT_DISPLAY")
        }
        return links.map { link ->
            val allowed = if (link.subjectType == SupportSubjectType.ORDER) orderRead else masked
            val label = labels[link.subjectType to link.subjectId]
            link.copy(
                display =
                    when {
                        !allowed -> SupportSubjectDisplay(SupportSubjectDisplayState.REQUIRES_PERMISSION)
                        label == null -> SupportSubjectDisplay(SupportSubjectDisplayState.MISSING_PROFILE)
                        else -> SupportSubjectDisplay(SupportSubjectDisplayState.AVAILABLE, label)
                    },
            )
        }
    }

    @Transactional
    fun authorizeLookup(
        actorId: UUID,
        caseId: UUID,
    ) {
        authorization.authorizeCase(actorId, caseId)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_CASE_WRITE)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_ORDER_READ)
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_SUBJECT_SEARCH)
    }

    @Transactional
    fun order(
        actorId: UUID,
        caseId: UUID,
        reference: String,
    ): SupportOrderDisplay? {
        authorizeLookup(actorId, caseId)
        val order = ordering.findOrderByPublicReference(reference)
        audit(actorId, caseId, if (order == null) "ORDER_CANDIDATE_MISS" else "ORDER_CANDIDATE_MATCH")
        return order
    }

    private fun audit(
        actorId: UUID,
        caseId: UUID,
        reason: String,
    ) {
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.PII_ACCESS,
                    action = "SUPPORT_PII_ACCESS_RECORDED",
                    targetType = "SUPPORT_CASE",
                    targetId = caseId,
                    occurredAt = clock.instant(),
                    reason = "SUPPORT_SUBJECT_$reason",
                    afterSummary = mapOf("purpose" to reason),
                    correlationId = correlations.currentOrCreate(),
                    sourceReference = "support-subject-read:${identifiers.next()}",
                ),
            ),
        )
    }
}

@Service
internal class SupportOrderCandidateApplicationService(
    private val selection: SupportSubjectSelectionService,
    private val preflight: SupportSubjectSearchPreflight,
) {
    fun find(
        actorId: UUID,
        caseId: UUID,
        reference: String,
    ): SupportOrderDisplay {
        selection.authorizeLookup(actorId, caseId)
        preflight.authorizeAndConsume(actorId)
        return selection.order(actorId, caseId, reference)
            ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Support order candidate was not found")
    }
}

@Validated
@RestController
internal class SupportOrderCandidateController(
    private val service: SupportOrderCandidateApplicationService,
) {
    @GetMapping("/api/v1/support/cases/{caseId}/order-candidates/{orderReference}")
    @PreAuthorize("isAuthenticated()")
    fun find(
        actor: OperatorActor,
        @PathVariable caseId: UUID,
        @PathVariable @Size(min = 12, max = 12) orderReference: String,
    ): ResponseEntity<SupportOrderDisplay> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.find(actor.actorId, caseId, orderReference))
}
