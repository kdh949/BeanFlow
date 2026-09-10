package io.github.kdh949.beanflow.operations.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.merchant.api.RegisterStoreSettlementTermsCommand
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsManagementOperations
import io.github.kdh949.beanflow.merchant.api.StoreSettlementTermsSnapshot
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class RegisterStoreSettlementTermsRequest(
    @field:NotBlank @field:Size(max = 240) val sourceReference: String,
    @field:Min(0) @field:Max(10000) val feeRateBps: Int,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    @field:Min(0) val expectedRevision: Long,
    @field:NotBlank @field:Size(max = 500) val reason: String,
) {
    @JsonAnySetter fun rejectUnknown(
        name: String,
        value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown terms field: $name")
}

internal data class ManagedStoreSettlementTermsPage(
    val items: List<StoreSettlementTermsSnapshot>,
    val revision: Long,
    val nextCursor: String?,
)

@Service
@Transactional
internal class OperatorStoreSettlementTermsService(
    private val terms: StoreSettlementTermsManagementOperations,
    private val grants: OperatorPermissionAuthorization,
    private val audits: AuditRecordOperations,
    private val cursors: SignedCursorCodec,
    private val correlation: CorrelationIdSource,
    private val clock: Clock,
) {
    fun get(
        actorId: UUID,
        storeId: UUID,
        termsVersionId: UUID,
    ) = run {
        grants.requireActive(
            actorId,
            OperatorPermission.STORE_SETTLEMENT_TERMS_READ,
        )
        terms.get(
            storeId,
            termsVersionId,
        )
    }

    fun list(
        actorId: UUID,
        storeId: UUID,
        cursor: String?,
        limit: Int,
    ): ManagedStoreSettlementTermsPage {
        grants.requireActive(
            actorId,
            OperatorPermission.STORE_SETTLEMENT_TERMS_READ,
        )
        val scope =
            SignedCursorScope(
                "operator-store-terms",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("$actorId:$storeId".toByteArray())),
                object : CursorSortAdapter<
                    Pair<
                        Instant,
                        UUID,
                    >,
                > {
                    override fun encode(
                        sort: Pair<
                            Instant,
                            UUID,
                        >,
                    ) = listOf(
                        sort.first.toString(),
                        sort.second.toString(),
                    )

                    override fun decode(
                        values: List<String>,
                    ): Pair<
                        Instant,
                        UUID,
                    >? =
                        if (values.size == 2) {
                            runCatching {
                                Instant.parse(values[0]) to UUID.fromString(values[1])
                            }.getOrNull()
                        } else {
                            null
                        }
                },
            )
        val after =
            cursor?.let {
                cursors
                    .verify(
                        it,
                        scope,
                    ).sort
            }
        val rows =
            terms.list(
                storeId,
                after?.first,
                after?.second,
                limit + 1,
            )
        val items = rows.items.take(limit)
        val next =
            if (rows.items.size > limit) {
                cursors.issue(
                    scope,
                    items.last().let {
                        it.effectiveFrom to it.termsVersionId
                    },
                    clock.instant().plus(Duration.ofMinutes(15)),
                )
            } else {
                null
            }
        return ManagedStoreSettlementTermsPage(
            items,
            rows.revision,
            next,
        )
    }

    fun register(command: RegisterStoreSettlementTermsCommand) =
        run {
            grants.requireActive(
                command.actorId,
                OperatorPermission.STORE_SETTLEMENT_TERMS_WRITE,
            )
            val change = terms.register(command)
            if (!change.replayed) {
                audits.appendAll(
                    listOf(
                        AppendAuditRecordCommand(
                            actorId = command.actorId.toString(),
                            actorType = AuditActorType.PLATFORM_OPERATOR,
                            category = AuditCategory.OPERATIONS_POLICY,
                            action = "STORE_SETTLEMENT_TERMS_REGISTERED",
                            targetType = "StoreSettlementTerms",
                            targetId = change.response.terms.termsVersionId,
                            occurredAt = command.now,
                            reason = command.reason,
                            beforeSummary = mapOf("revision" to command.expectedRevision.toString()),
                            afterSummary =
                                mapOf(
                                    "revision" to change.response.revision.toString(),
                                    "feeRateBps" to command.feeRateBps.toString(),
                                    "sourceDigest" to
                                        HexFormat
                                            .of()
                                            .formatHex(
                                                MessageDigest
                                                    .getInstance("SHA-256")
                                                    .digest(command.sourceReference.toByteArray()),
                                            ).chunked(2)
                                            .joinToString(":"),
                                ),
                            correlationId = correlation.currentOrCreate(),
                            sourceReference = "store-terms:${change.commandId}",
                        ),
                    ),
                )
            }
            change.response
        }
}

@Validated
@RestController
@RequestMapping("/api/v1/operations/stores/{storeId}/settlement-terms")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
internal class OperatorStoreSettlementTermsController(
    private val service: OperatorStoreSettlementTermsService,
    private val clock: Clock,
) {
    @GetMapping
    fun list(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @RequestParam(required = false) @Size(
            min = 1,
            max = 2048,
        ) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ) = service.list(
        actor.actorId,
        storeId,
        cursor,
        limit,
    )

    @GetMapping("/{termsVersionId}")
    fun get(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @PathVariable termsVersionId: UUID,
    ) = service.get(
        actor.actorId,
        storeId,
        termsVersionId,
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(
            min = 8,
            max = 128,
        ) key: String,
        @Valid @RequestBody request: RegisterStoreSettlementTermsRequest,
    ) = service.register(
        RegisterStoreSettlementTermsCommand(
            actor.actorId,
            storeId,
            key,
            request.sourceReference,
            request.feeRateBps,
            request.effectiveFrom,
            request.effectiveTo,
            request.expectedRevision,
            request.reason,
            clock.instant(),
        ),
    )
}
