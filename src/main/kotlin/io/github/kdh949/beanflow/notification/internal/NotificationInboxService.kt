package io.github.kdh949.beanflow.notification.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.notification.internal.domain.NotificationClassification
import io.github.kdh949.beanflow.notification.internal.domain.NotificationInboxItem
import io.github.kdh949.beanflow.notification.internal.domain.NotificationTarget
import io.github.kdh949.beanflow.notification.internal.domain.NotificationTargetType
import io.github.kdh949.beanflow.shared.api.CursorSortAdapter
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.SignedCursorCodec
import io.github.kdh949.beanflow.shared.api.SignedCursorScope
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

internal data class NotificationInboxSort(
    val createdAt: Instant,
    val notificationId: UUID,
)

internal data class NotificationInboxProjection(
    val notificationId: UUID,
    val title: String,
    val body: String,
    val createdAt: Instant,
    val readAt: Instant?,
    val classification: NotificationClassification,
    val targetType: NotificationTargetType,
    val targetReference: String?,
)

@Repository
internal class NotificationInboxQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findPage(
        customerId: UUID,
        after: NotificationInboxSort?,
        limit: Int,
    ): List<NotificationInboxProjection> {
        val arguments = mutableListOf<Any>(customerId)
        val boundary =
            after?.let {
                arguments += Timestamp.from(it.createdAt)
                arguments += Timestamp.from(it.createdAt)
                arguments += it.notificationId
                "AND (created_at < ? OR (created_at = ? AND id < ?))"
            } ?: ""
        arguments += limit
        return jdbcTemplate.query(
            """
            SELECT id, title, body, created_at, read_at, classification, target_type, target_reference
              FROM notification_inbox_item
             WHERE customer_id = ?
               $boundary
             ORDER BY created_at DESC, id DESC
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                NotificationInboxProjection(
                    notificationId = resultSet.getObject("id", UUID::class.java),
                    title = resultSet.getString("title"),
                    body = resultSet.getString("body"),
                    createdAt = resultSet.getTimestamp("created_at").toInstant(),
                    readAt = resultSet.getTimestamp("read_at")?.toInstant(),
                    classification = NotificationClassification.valueOf(resultSet.getString("classification")),
                    targetType = NotificationTargetType.valueOf(resultSet.getString("target_type")),
                    targetReference = resultSet.getString("target_reference"),
                )
            },
            *arguments.toTypedArray(),
        )
    }

    fun hasUnread(customerId: UUID): Boolean =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM notification_inbox_item WHERE customer_id = ? AND read_at IS NULL)",
                Boolean::class.java,
                customerId,
            ),
        )

    fun marketingOptIn(customerId: UUID): Boolean =
        jdbcTemplate
            .query(
                "SELECT marketing_opt_in FROM notification_customer_preference WHERE customer_id = ?",
                { resultSet, _ -> resultSet.getBoolean("marketing_opt_in") },
                customerId,
            ).singleOrNull() ?: false

    fun marketingOptInForUpdate(customerId: UUID): Boolean =
        jdbcTemplate
            .query(
                "SELECT marketing_opt_in FROM notification_customer_preference WHERE customer_id = ? FOR UPDATE",
                { resultSet, _ -> resultSet.getBoolean("marketing_opt_in") },
                customerId,
            ).singleOrNull() ?: false

    fun replaceMarketingPreference(
        customerId: UUID,
        marketingOptIn: Boolean,
        now: Instant,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO notification_customer_preference (customer_id, marketing_opt_in, updated_at, version)
            VALUES (?, ?, ?, 0)
            ON CONFLICT (customer_id) DO UPDATE
                SET marketing_opt_in = EXCLUDED.marketing_opt_in,
                    updated_at = EXCLUDED.updated_at,
                    version = notification_customer_preference.version + 1
            """.trimIndent(),
            customerId,
            marketingOptIn,
            Timestamp.from(now),
        )
    }

    fun deleteExpired(
        now: Instant,
        limit: Int,
    ): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                """
                WITH due AS (
                    SELECT id
                      FROM notification_inbox_item
                     WHERE retention_expires_at <= ?
                     ORDER BY retention_expires_at, id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), deleted AS (
                    DELETE FROM notification_inbox_item item
                     USING due
                     WHERE item.id = due.id
                    RETURNING item.id
                )
                SELECT count(*) FROM deleted
                """.trimIndent(),
                Int::class.java,
                Timestamp.from(now),
                limit,
            ),
        )
}

internal data class NotificationSummaryResponse(
    val hasUnread: Boolean,
)

internal data class NotificationPageResponse(
    val items: List<NotificationItemResponse>,
    val page: NotificationPageInfoResponse,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class NotificationPageInfoResponse(
    val nextCursor: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class NotificationItemResponse(
    val notificationId: UUID,
    val title: String,
    val body: String,
    val createdAt: Instant,
    val readAt: Instant?,
    val classification: NotificationClassification,
    val target: NotificationTargetResponse,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class NotificationTargetResponse(
    val type: NotificationTargetType,
    val reference: String?,
)

internal data class NotificationPreferenceResponse(
    val marketingOptIn: Boolean,
)

@Service
internal class NotificationInboxService(
    private val queries: NotificationInboxQueryRepository,
    private val inboxRepository: NotificationInboxItemJpaRepository,
    private val signedCursorCodec: SignedCursorCodec,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) {
    @Transactional(readOnly = true)
    fun summary(customerId: UUID): NotificationSummaryResponse =
        observeRead("summary") {
            persistence("Notification summary query is unavailable") {
                NotificationSummaryResponse(queries.hasUnread(customerId))
            }
        }

    @Transactional(readOnly = true)
    fun list(
        customerId: UUID,
        cursor: String?,
        requestedLimit: Int?,
    ): NotificationPageResponse =
        observeRead("list") {
            persistence("Notification inbox query is unavailable") {
                val limit = normalizeLimit(requestedLimit)
                val scope = cursorScope(customerId)
                val after = cursor?.let { signedCursorCodec.verify(it, scope).sort }
                val fetched = queries.findPage(customerId, after, limit + 1)
                val page = fetched.take(limit)
                val nextCursor =
                    if (fetched.size > limit) {
                        val last = page.last()
                        signedCursorCodec.issue(
                            scope,
                            NotificationInboxSort(last.createdAt, last.notificationId),
                            clock.instant().plus(CURSOR_TTL),
                        )
                    } else {
                        null
                    }
                NotificationPageResponse(page.map { it.toResponse() }, NotificationPageInfoResponse(nextCursor))
            }
        }

    @Transactional
    fun read(
        customerId: UUID,
        notificationId: UUID,
    ) {
        try {
            val readLatency =
                persistence("Notification read command is unavailable") {
                    val entity = inboxRepository.findLockedByIdAndCustomerId(notificationId, customerId) ?: notFound()
                    val item = entity.toDomain()
                    if (item.read(clock.instant())) {
                        entity.readAt = item.readAt
                        inboxRepository.saveAndFlush(entity)
                        Duration.between(item.createdAt, requireNotNull(item.readAt))
                    } else {
                        null
                    }
                }
            afterCommit {
                commandMetric("mark_read", if (readLatency == null) "replayed" else "updated")
                readLatency?.let { meterRegistry.timer("beanflow.notification.inbox.read_latency").record(it) }
            }
        } catch (failure: RuntimeException) {
            commandMetric("mark_read", "failed")
            throw failure
        }
    }

    @Transactional(readOnly = true)
    fun preference(customerId: UUID): NotificationPreferenceResponse =
        observeRead("preference") {
            persistence("Notification preference query is unavailable") {
                NotificationPreferenceResponse(queries.marketingOptIn(customerId))
            }
        }

    @Transactional
    fun replacePreference(
        customerId: UUID,
        marketingOptIn: Boolean,
    ): NotificationPreferenceResponse {
        try {
            val response =
                persistence("Notification preference update is unavailable") {
                    queries.replaceMarketingPreference(customerId, marketingOptIn, clock.instant())
                    NotificationPreferenceResponse(marketingOptIn)
                }
            afterCommit { commandMetric("replace_preference", "succeeded") }
            return response
        } catch (failure: RuntimeException) {
            commandMetric("replace_preference", "failed")
            throw failure
        }
    }

    @Transactional
    fun purgeExpired(
        now: Instant,
        limit: Int = CLEANUP_LIMIT,
    ): Int {
        require(limit in 1..CLEANUP_LIMIT) { "Notification cleanup limit must be between 1 and 100" }
        return persistence("Notification inbox cleanup is unavailable") { queries.deleteExpired(now, limit) }
    }

    private fun cursorScope(customerId: UUID): SignedCursorScope<NotificationInboxSort> =
        SignedCursorScope(
            endpoint = CURSOR_ENDPOINT,
            filterHash = sha256("{\"endpoint\":\"$CURSOR_ENDPOINT\",\"customerId\":\"$customerId\"}"),
            sortAdapter = SORT_ADAPTER,
        )

    private fun normalizeLimit(requestedLimit: Int?): Int {
        val limit = requestedLimit ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_LIMIT) invalid("Notification limit must be between 1 and 100")
        return limit
    }

    private fun NotificationInboxProjection.toResponse(): NotificationItemResponse =
        NotificationItemResponse(
            notificationId,
            title,
            body,
            createdAt,
            readAt,
            classification,
            NotificationTargetResponse(targetType, targetReference),
        )

    private fun NotificationInboxItemEntity.toDomain(): NotificationInboxItem =
        NotificationInboxItem.restore(
            id,
            customerId,
            logicalSource,
            orderId,
            classification,
            template,
            title,
            body,
            NotificationTarget.restore(targetType, targetReference),
            readAt,
            createdAt,
            retentionExpiresAt,
        )

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))

    private fun <T> observeRead(
        operation: String,
        block: () -> T,
    ): T {
        val startedAt = System.nanoTime()
        return try {
            block().also {
                readMetric(operation, "succeeded", System.nanoTime() - startedAt)
            }
        } catch (failure: RuntimeException) {
            readMetric(operation, "failed", System.nanoTime() - startedAt)
            throw failure
        }
    }

    private fun readMetric(
        operation: String,
        outcome: String,
        durationNanos: Long,
    ) {
        meterRegistry.counter("beanflow.notification.inbox.read.count", "operation", operation, "outcome", outcome).increment()
        meterRegistry
            .timer("beanflow.notification.inbox.read.duration", "operation", operation, "outcome", outcome)
            .record(Duration.ofNanos(durationNanos))
    }

    private fun commandMetric(
        operation: String,
        outcome: String,
    ) {
        meterRegistry.counter("beanflow.notification.inbox.command.count", "operation", operation, "outcome", outcome).increment()
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = action()
            },
        )
    }

    private fun <T> persistence(
        message: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (failure: DomainFailure) {
            throw failure
        } catch (failure: DataAccessException) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, message).also { it.initCause(failure) }
        }

    private fun invalid(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private fun notFound(): Nothing = throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Notification was not found")

    private companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
        const val CLEANUP_LIMIT = 100
        const val CURSOR_ENDPOINT = "customer-notifications"
        val CURSOR_TTL: Duration = Duration.ofHours(24)
        val SORT_ADAPTER =
            object : CursorSortAdapter<NotificationInboxSort> {
                override fun encode(sort: NotificationInboxSort): List<String> =
                    listOf(sort.createdAt.toString(), sort.notificationId.toString())

                override fun decode(values: List<String>): NotificationInboxSort? {
                    if (values.size != 2) return null
                    return try {
                        val createdAt = Instant.parse(values[0])
                        val notificationId = UUID.fromString(values[1])
                        if (createdAt.toString() != values[0] || notificationId.toString() != values[1]) {
                            null
                        } else {
                            NotificationInboxSort(createdAt, notificationId)
                        }
                    } catch (_: DateTimeException) {
                        null
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            }
    }
}
