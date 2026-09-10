package io.github.kdh949.beanflow.notification.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.operations.api.AppendAuditRecordCommand
import io.github.kdh949.beanflow.operations.api.AuditActorType
import io.github.kdh949.beanflow.operations.api.AuditCategory
import io.github.kdh949.beanflow.operations.api.AuditRecordOperations
import io.github.kdh949.beanflow.operations.api.ManualRecoveryAcceptance
import io.github.kdh949.beanflow.operations.api.ManualRecoveryCaseOperations
import io.github.kdh949.beanflow.operations.api.ManualRecoveryCaseView
import io.github.kdh949.beanflow.operations.api.ManualRecoveryKind
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import io.github.kdh949.beanflow.operations.api.OperatorPermissionAuthorization
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
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
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class RetryNotificationDeliveryRequest(
    @field:Min(0) val expectedVersion: Long,
    @field:Min(0) val expectedCaseVersion: Long,
    @field:NotBlank @field:Size(max = 500) val reason: String,
) {
    @JsonAnySetter fun rejectUnknown(
        name: String,
        value: Any?,
    ): Unit = throw IllegalArgumentException("Unknown recovery field: $name")
}

internal data class NotificationRecoveryView(
    val deliveryId: UUID,
    val state: String,
    val version: Long,
    val attemptCount: Int,
    val attemptLimit: Int,
    val nextAttemptAt: Instant?,
    val lastFailureCode: String?,
    val recoveryCase: ManualRecoveryCaseView?,
)

internal data class RetryNotificationCommand(
    val actorId: UUID,
    val deliveryId: UUID,
    val key: String,
    val expectedVersion: Long,
    val expectedCaseVersion: Long,
    val reason: String,
    val now: Instant,
)

@Service
@Transactional
internal class NotificationManualRecoveryService(
    private val deliveries: NotificationDeliveryJpaRepository,
    private val deliveryService: NotificationDeliveryService,
    private val cases: ManualRecoveryCaseOperations,
    private val grants: OperatorPermissionAuthorization,
    private val audits: AuditRecordOperations,
    private val correlation: CorrelationIdSource,
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    fun list(
        actorId: UUID,
        cursor: String?,
        limit: Int,
    ) = run {
        grants.requireActive(
            actorId,
            OperatorPermission.NOTIFICATION_RECOVERY_READ,
        )
        cases.list(
            ManualRecoveryKind.NOTIFICATION_DELIVERY,
            actorId,
            cursor,
            limit,
        )
    }

    fun get(
        actorId: UUID,
        id: UUID,
    ): NotificationRecoveryView {
        grants.requireActive(
            actorId,
            OperatorPermission.NOTIFICATION_RECOVERY_READ,
        )
        val row =
            deliveries.findById(id).orElse(null)
                ?: throw DomainFailure(
                    FailureCode.RESOURCE_NOT_FOUND,
                    "Notification delivery was not found",
                )
        return NotificationRecoveryView(
            id,
            row.state.name,
            row.version,
            row.attemptCount,
            row.attemptLimit,
            row.nextAttemptAt,
            row.lastFailureCode,
            cases.find(
                ManualRecoveryKind.NOTIFICATION_DELIVERY,
                id,
            ),
        )
    }

    fun retry(c: RetryNotificationCommand): ManualRecoveryAcceptance {
        grants.requireActive(
            c.actorId,
            OperatorPermission.NOTIFICATION_RECOVERY_RETRY,
        )
        if (!validText(
                c.key,
                8,
                128,
            ) ||
            !validText(
                c.reason,
                1,
                500,
            ) ||
            c.expectedVersion < 0 ||
            c.expectedCaseVersion < 0
        ) {
            throw DomainFailure(
                FailureCode.INVALID_REQUEST,
                "Notification recovery input is invalid",
            )
        }
        val hash =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    mapper.writeValueAsBytes(
                        listOf(
                            c.deliveryId,
                            c.expectedVersion,
                            c.expectedCaseVersion,
                            c.reason,
                        ),
                    ),
                ),
            )
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
            Any::class.java,
            "notification-recovery:${c.actorId}:${c.key}",
        )
        jdbc
            .query(
                "SELECT payload_hash, response_json FROM notification_manual_recovery_command " +
                    "WHERE actor_id = ? AND idempotency_key = ?",
                {
                    rs,
                    _,
                    ->
                    rs.getString("payload_hash") to rs.getString("response_json")
                },
                c.actorId,
                c.key,
            ).singleOrNull()
            ?.let {
                if (it.first != hash) {
                    throw DomainFailure(
                        FailureCode.IDEMPOTENCY_KEY_REUSED,
                        "Notification recovery key has another payload",
                    )
                }
                return mapper.readValue(
                    it.second,
                    ManualRecoveryAcceptance::class.java,
                )
            }
        deliveryService.resumeManual(
            c.deliveryId,
            c.expectedVersion,
            c.now,
        )
        val recoveryCase =
            cases.begin(
                ManualRecoveryKind.NOTIFICATION_DELIVERY,
                c.deliveryId,
                c.expectedCaseVersion,
                c.now,
            )
        val commandId = UUID.randomUUID()
        val response =
            ManualRecoveryAcceptance(
                commandId,
                recoveryCase,
                c.now,
            )
        audits.appendAll(
            listOf(
                AppendAuditRecordCommand(
                    actorId = c.actorId.toString(),
                    actorType = AuditActorType.PLATFORM_OPERATOR,
                    category = AuditCategory.OPERATIONS_POLICY,
                    action = "NOTIFICATION_RETRY_REQUESTED",
                    targetType = "NotificationDelivery",
                    targetId = c.deliveryId,
                    occurredAt = c.now,
                    reason = c.reason,
                    beforeSummary =
                        mapOf(
                            "state" to "MANUAL_REVIEW",
                            "version" to c.expectedVersion.toString(),
                        ),
                    afterSummary =
                        mapOf(
                            "state" to "RETRY_SCHEDULED",
                            "caseVersion" to recoveryCase.version.toString(),
                        ),
                    correlationId = correlation.currentOrCreate(),
                    sourceReference = "notification-recovery:$commandId",
                ),
            ),
        )
        jdbc.update(
            "INSERT INTO notification_manual_recovery_command(id, actor_id, delivery_id, idempotency_key, " +
                "payload_hash, response_json, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            commandId,
            c.actorId,
            c.deliveryId,
            c.key,
            hash,
            mapper.writeValueAsString(response),
            Timestamp.from(c.now),
        )
        return response
    }

    private fun validText(
        value: String,
        min: Int,
        max: Int,
    ) = value.length in min..max && value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)
}

@Validated
@RestController
@RequestMapping("/api/v1/operations/notification-delivery-recoveries")
@PreAuthorize("hasRole('PLATFORM_OPERATOR')")
internal class NotificationManualRecoveryController(
    private val service: NotificationManualRecoveryService,
    private val clock: Clock,
) {
    @GetMapping fun list(
        actor: OperatorActor,
        @RequestParam(required = false) @Size(
            min = 1,
            max = 2048,
        ) cursor: String?,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) limit: Int,
    ) = service.list(
        actor.actorId,
        cursor,
        limit,
    )

    @GetMapping("/{deliveryId}")
    fun get(
        actor: OperatorActor,
        @PathVariable deliveryId: UUID,
    ) = service.get(
        actor.actorId,
        deliveryId,
    )

    @PostMapping("/{deliveryId}/retries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun retry(
        actor: OperatorActor,
        @PathVariable deliveryId: UUID,
        @RequestHeader("Idempotency-Key") @Size(
            min = 8,
            max = 128,
        ) key: String,
        @Valid @RequestBody request: RetryNotificationDeliveryRequest,
    ) = service.retry(
        RetryNotificationCommand(
            actor.actorId,
            deliveryId,
            key,
            request.expectedVersion,
            request.expectedCaseVersion,
            request.reason,
            clock.instant(),
        ),
    )
}

@Component
internal class NotificationManualCommandRetention(
    private val jdbc: JdbcTemplate,
    private val clock: Clock,
) {
    @Transactional
    @Scheduled(
        fixedDelayString = "\${beanflow.notification-manual-recovery.retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.notification-manual-recovery.retention.initial-delay-ms:3600000}",
    )
    fun cleanup() {
        jdbc.update(
            "DELETE FROM notification_manual_recovery_command WHERE id IN (SELECT id FROM notification_manual_recovery_command " +
                "WHERE created_at < ? ORDER BY created_at, id LIMIT 100 FOR UPDATE SKIP LOCKED)",
            Timestamp.from(clock.instant().minus(Duration.ofDays(90))),
        )
    }
}
