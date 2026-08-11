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
import io.github.kdh949.beanflow.shared.api.BlindIndex
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.ExactSearchCriterionType
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.KeyedBlindIndexPort
import io.github.kdh949.beanflow.shared.api.PersonalDataNormalizer
import io.github.kdh949.beanflow.shared.api.ProtectedProfileExactQuery
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

internal enum class SupportSearchSubjectType {
    CUSTOMER,
    STORE,
    RIDER,
}

internal enum class SupportSearchReasonCode {
    CASE_INTAKE,
    ACTIVE_CASE_LOOKUP,
    DELIVERY_INCIDENT,
    PRIVACY_REQUEST,
}

internal data class SupportSubjectSearchCandidate(
    val subjectType: SupportSearchSubjectType,
    val subjectId: UUID,
    val maskedDisplayName: String,
    val matchedCriterionType: ExactSearchCriterionType,
    val maskedMatchedValue: String,
) {
    override fun toString(): String = "SupportSubjectSearchCandidate(subjectType=$subjectType, subjectId=$subjectId, values=<redacted>)"
}

internal data class SupportSubjectSearchResult(
    val searchId: UUID,
    val items: List<SupportSubjectSearchCandidate>,
    val matchedCount: Int,
    val ambiguous: Boolean,
    val hasMore: Boolean,
)

internal data class SearchSupportSubjectsCommand(
    val actorId: UUID,
    val criterionType: ExactSearchCriterionType,
    val rawCriterion: String,
    val subjectTypes: List<SupportSearchSubjectType>,
    val reasonCode: SupportSearchReasonCode,
    val correlationId: String,
) {
    override fun toString(): String =
        "SearchSupportSubjectsCommand(actorId=$actorId, type=$criterionType, subjectTypes=$subjectTypes, " +
            "reason=$reasonCode, criterion=<redacted>)"
}

internal data class PreparedSupportSubjectSearch(
    val searchId: UUID,
    val actorId: UUID,
    val criterionType: ExactSearchCriterionType,
    val subjectTypes: List<SupportSearchSubjectType>,
    val reasonCode: SupportSearchReasonCode,
    val indexes: List<BlindIndex>,
    val occurredAt: Instant,
    val correlationId: String,
)

@Service
internal class SupportSubjectSearchApplicationService(
    private val preflight: SupportSubjectSearchPreflight,
    private val blindIndexes: KeyedBlindIndexPort,
    private val transaction: SupportSubjectSearchTransaction,
    private val identifiers: IdentifierSource,
    private val clock: Clock,
) {
    fun search(command: SearchSupportSubjectsCommand): SupportSubjectSearchResult {
        val subjectTypes = command.validatedSubjectTypes()
        val correlationId = command.correlationId.validatedCorrelationId()
        val normalized = PersonalDataNormalizer.normalize(command.criterionType, command.rawCriterion)
        preflight.authorizeAndConsume(command.actorId)
        val activeVersions =
            try {
                blindIndexes.activeSearchKeyVersions()
            } catch (failure: DomainFailure) {
                if (failure.code == FailureCode.DEPENDENCY_UNAVAILABLE) throw failure
                dependency(failure)
            } catch (failure: RuntimeException) {
                dependency(failure)
            }
        if (activeVersions.size !in 1..MAX_SEARCH_KEY_VERSIONS) dependency()
        val indexes =
            try {
                blindIndexes.generate(normalized, activeVersions)
            } catch (failure: DomainFailure) {
                if (failure.code == FailureCode.DEPENDENCY_UNAVAILABLE) throw failure
                dependency(failure)
            } catch (failure: RuntimeException) {
                dependency(failure)
            }
        if (
            activeVersions.isEmpty() ||
            indexes.map(BlindIndex::keyVersion).toSet() != activeVersions ||
            indexes.size != activeVersions.size
        ) {
            dependency()
        }
        return transaction.execute(
            PreparedSupportSubjectSearch(
                searchId = identifiers.next(),
                actorId = command.actorId,
                criterionType = command.criterionType,
                subjectTypes = subjectTypes,
                reasonCode = command.reasonCode,
                indexes = indexes,
                occurredAt = clock.instant(),
                correlationId = correlationId,
            ),
        )
    }

    private fun SearchSupportSubjectsCommand.validatedSubjectTypes(): List<SupportSearchSubjectType> {
        val distinct = subjectTypes.distinct()
        if (distinct.size != subjectTypes.size || distinct.isEmpty() || distinct.size > SupportSearchSubjectType.entries.size) {
            invalid()
        }
        return distinct.sortedBy { it.ordinal }
    }

    private fun String.validatedCorrelationId(): String = trim().also { if (it.length !in 1..160 || it.any(Char::isISOControl)) invalid() }

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Support search request is invalid")

    private fun dependency(cause: Throwable? = null): Nothing =
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Support subject search is unavailable")
            .also { failure -> cause?.let(failure::initCause) }

    private companion object {
        const val MAX_SEARCH_KEY_VERSIONS = 8
    }
}

@Service
internal class SupportSubjectSearchPreflight(
    private val permissions: OperatorPermissionAuthorization,
    private val rateWindows: SupportSubjectSearchRateWindowRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun authorizeAndConsume(actorId: UUID) {
        permissions.requireActive(actorId, OperatorPermission.SUPPORT_SUBJECT_SEARCH)
        rateWindows.consume(actorId)
    }
}

@Component
internal class SupportSubjectSearchRateWindowRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun consume(actorId: UUID) {
        val decision =
            try {
                jdbcTemplate.queryForObject(
                    """
                    WITH rate_clock AS MATERIALIZED (
                        SELECT clock_timestamp() AS db_now
                    ), rate_context AS MATERIALIZED (
                        SELECT db_now,
                               date_bin(
                                   INTERVAL '5 minutes',
                                   db_now,
                                   TIMESTAMPTZ '1970-01-01 00:00:00Z'
                               ) AS window_started_at
                          FROM rate_clock
                    ), consumed AS (
                        INSERT INTO support_subject_search_rate_window (
                            actor_id, window_started_at, attempt_count, updated_at
                        )
                        SELECT ?, window_started_at, 1, db_now
                          FROM rate_context
                        ON CONFLICT (actor_id, window_started_at) DO UPDATE
                           SET attempt_count = support_subject_search_rate_window.attempt_count + 1,
                               updated_at = EXCLUDED.updated_at
                         WHERE support_subject_search_rate_window.attempt_count < 30
                        RETURNING attempt_count
                    )
                    SELECT EXISTS(SELECT 1 FROM consumed) AS allowed,
                           GREATEST(
                               1,
                               LEAST(
                                   300,
                                   CEIL(
                                       EXTRACT(EPOCH FROM (
                                           window_started_at + INTERVAL '5 minutes' - db_now
                                       ))
                                   )::integer
                               )
                           ) AS retry_after_seconds
                      FROM rate_context
                    """.trimIndent(),
                    { resultSet, _ ->
                        RateWindowDecision(
                            allowed = resultSet.getBoolean("allowed"),
                            retryAfterSeconds = resultSet.getLong("retry_after_seconds"),
                        )
                    },
                    actorId,
                )
            } catch (failure: DataAccessException) {
                throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Support search rate guard is unavailable")
            }
                ?: throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Support search rate guard is unavailable")
        if (!decision.allowed) {
            throw DomainFailure(
                FailureCode.SUPPORT_SEARCH_RATE_LIMITED,
                "Support search rate limit was exceeded",
                decision.retryAfterSeconds,
            )
        }
    }

    private data class RateWindowDecision(
        val allowed: Boolean,
        val retryAfterSeconds: Long,
    )
}

@Service
internal class SupportSubjectSearchTransaction(
    private val permissions: OperatorPermissionAuthorization,
    private val customers: CustomerSupportProfileQueryOperations,
    private val stores: StoreSupportProfileQueryOperations,
    private val couriers: ExternalCourierSupportProfileQueryOperations,
    private val audits: AuditRecordOperations,
) {
    @Transactional
    fun execute(search: PreparedSupportSubjectSearch): SupportSubjectSearchResult {
        permissions.requireActive(search.actorId, OperatorPermission.SUPPORT_SUBJECT_SEARCH)
        val query = ProtectedProfileExactQuery(search.criterionType, search.indexes, OWNER_QUERY_LIMIT)
        val candidates =
            try {
                buildList {
                    if (SupportSearchSubjectType.CUSTOMER in search.subjectTypes) {
                        addAll(
                            customers.findByExactIndexes(query).map {
                                SupportSubjectSearchCandidate(
                                    SupportSearchSubjectType.CUSTOMER,
                                    it.customerId,
                                    it.maskedDisplayName,
                                    search.criterionType,
                                    it.maskedMatchedValue,
                                )
                            },
                        )
                    }
                    if (SupportSearchSubjectType.STORE in search.subjectTypes) {
                        addAll(
                            stores.findByExactIndexes(query).map {
                                SupportSubjectSearchCandidate(
                                    SupportSearchSubjectType.STORE,
                                    it.storeId,
                                    it.maskedDisplayName,
                                    search.criterionType,
                                    it.maskedMatchedValue,
                                )
                            },
                        )
                    }
                    if (SupportSearchSubjectType.RIDER in search.subjectTypes) {
                        addAll(
                            couriers.findByExactIndexes(query).map {
                                SupportSubjectSearchCandidate(
                                    SupportSearchSubjectType.RIDER,
                                    it.externalCourierId,
                                    it.maskedDisplayName,
                                    search.criterionType,
                                    it.maskedMatchedValue,
                                )
                            },
                        )
                    }
                }.distinctBy { it.subjectType to it.subjectId }
                    .sortedWith(compareBy<SupportSubjectSearchCandidate>({ it.subjectType.ordinal }, { it.subjectId }))
            } catch (failure: DomainFailure) {
                throw failure
            } catch (failure: RuntimeException) {
                dependency(failure)
            }
        val hasMore = candidates.size > RESPONSE_LIMIT
        val matchedCount = candidates.size.coerceAtMost(MATCH_COUNT_CAP)
        val result =
            SupportSubjectSearchResult(
                searchId = search.searchId,
                items = candidates.take(RESPONSE_LIMIT),
                matchedCount = matchedCount,
                ambiguous = candidates.size > 1,
                hasMore = hasMore,
            )
        appendAudit(search, result)
        return result
    }

    private fun appendAudit(
        search: PreparedSupportSubjectSearch,
        result: SupportSubjectSearchResult,
    ) {
        try {
            audits.appendAll(
                listOf(
                    AppendAuditRecordCommand(
                        actorId = search.actorId.toString(),
                        actorType = AuditActorType.PLATFORM_OPERATOR,
                        category = AuditCategory.PII_ACCESS,
                        action = "SUPPORT_PII_ACCESS_RECORDED",
                        targetType = "SUPPORT_SUBJECT_SEARCH",
                        targetId = search.searchId,
                        occurredAt = search.occurredAt,
                        reason = "SUPPORT_SUBJECT_SEARCH_${search.reasonCode.name}",
                        afterSummary =
                            mapOf(
                                "criterionKind" to search.criterionType.name,
                                "selectedKinds" to search.subjectTypes.joinToString(",") { it.name },
                                "boundedMatchCount" to result.matchedCount.toString(),
                                "ambiguous" to result.ambiguous.toString(),
                                "truncated" to result.hasMore.toString(),
                                "indexVersionCount" to search.indexes.size.toString(),
                            ),
                        correlationId = search.correlationId,
                        sourceReference = "support-subject-search:${search.searchId}",
                    ),
                ),
            )
        } catch (failure: RuntimeException) {
            dependency(failure)
        }
    }

    private fun dependency(cause: Throwable): Nothing =
        throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Support subject search is unavailable")
            .also { it.initCause(cause) }

    private companion object {
        const val RESPONSE_LIMIT = 20
        const val MATCH_COUNT_CAP = 21
        const val OWNER_QUERY_LIMIT = 21
    }
}
