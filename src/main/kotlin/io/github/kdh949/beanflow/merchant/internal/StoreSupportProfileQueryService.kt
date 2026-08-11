package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.MaskedStoreSupportProfile
import io.github.kdh949.beanflow.merchant.api.StoreSupportProfileQueryOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.ExactSearchCriterionType
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ProtectedProfileExactQuery
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class StoreSupportProfileQueryService(
    private val repository: StoreSupportProfileQueryRepository,
) : StoreSupportProfileQueryOperations {
    @Transactional(readOnly = true)
    override fun findByExactIndexes(query: ProtectedProfileExactQuery): List<MaskedStoreSupportProfile> =
        try {
            repository.findByExactIndexes(query).onEach(::requireMasked)
        } catch (exception: DataAccessException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Store support profile query is unavailable",
            ).also { it.initCause(exception) }
        }

    private fun requireMasked(profile: MaskedStoreSupportProfile) {
        if ('*' !in profile.maskedDisplayName || '*' !in profile.maskedMatchedValue) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Store support profile projection is invalid")
        }
    }
}

@Repository
internal class StoreSupportProfileQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findByExactIndexes(query: ProtectedProfileExactQuery): List<MaskedStoreSupportProfile> {
        val requested = query.indexes.joinToString(",") { "(?, ?)" }
        val matchedColumn =
            when (query.criterionType) {
                ExactSearchCriterionType.PHONE -> "profile.masked_support_phone"
                ExactSearchCriterionType.EMAIL -> "profile.masked_support_email"
            }
        val arguments = query.indexes.flatMap { listOf(it.keyVersion, it.digestBytes()) }.toMutableList<Any>()
        arguments.add(query.criterionType.name)
        arguments.add(query.limit)
        return jdbcTemplate.query(
            """
            WITH requested(index_key_version, blind_index) AS (VALUES $requested)
            SELECT DISTINCT profile.store_id, profile.masked_display_name,
                   $matchedColumn AS masked_matched_value
              FROM requested
              JOIN merchant_store_support_profile_exact_index exact_index
                ON exact_index.index_key_version = requested.index_key_version
               AND exact_index.blind_index = requested.blind_index
              JOIN merchant_store_support_profile profile ON profile.store_id = exact_index.store_id
             WHERE exact_index.criterion_type = ?
               AND $matchedColumn IS NOT NULL
             ORDER BY profile.store_id
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                MaskedStoreSupportProfile(
                    storeId = resultSet.getObject("store_id", UUID::class.java),
                    maskedDisplayName = resultSet.getString("masked_display_name"),
                    maskedMatchedValue = resultSet.getString("masked_matched_value"),
                )
            },
            *arguments.toTypedArray(),
        )
    }
}
