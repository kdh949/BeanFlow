package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.identity.api.CustomerSupportProfileQueryOperations
import io.github.kdh949.beanflow.identity.api.MaskedCustomerSupportProfile
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.ProtectedProfileExactQuery
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
internal class CustomerSupportProfileQueryService(
    private val repository: CustomerSupportProfileQueryRepository,
) : CustomerSupportProfileQueryOperations {
    @Transactional(readOnly = true)
    override fun findByExactIndexes(query: ProtectedProfileExactQuery): List<MaskedCustomerSupportProfile> =
        try {
            repository.findByExactIndexes(query).onEach(::requireMasked)
        } catch (exception: DataAccessException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Customer support profile query is unavailable",
            ).also { it.initCause(exception) }
        }

    @Transactional(readOnly = true)
    override fun findMaskedById(customerId: UUID): MaskedCustomerSupportProfile? =
        try {
            repository.findMaskedById(customerId)?.also(::requireMasked)
        } catch (exception: DataAccessException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Customer support profile query is unavailable",
            ).also { it.initCause(exception) }
        }

    private fun requireMasked(profile: MaskedCustomerSupportProfile) {
        if ('*' !in profile.maskedDisplayName || '*' !in profile.maskedMatchedValue) {
            throw DomainFailure(FailureCode.DEPENDENCY_UNAVAILABLE, "Customer support profile projection is invalid")
        }
    }
}

@Repository
internal class CustomerSupportProfileQueryRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun findMaskedById(customerId: UUID): MaskedCustomerSupportProfile? =
        jdbcTemplate
            .query(
                """
                SELECT customer_id, masked_display_name,
                       coalesce(masked_primary_phone, masked_primary_email) AS masked_matched_value
                  FROM identity_customer_support_profile
                 WHERE customer_id = ?
                   AND coalesce(masked_primary_phone, masked_primary_email) IS NOT NULL
                """.trimIndent(),
                { resultSet, _ ->
                    MaskedCustomerSupportProfile(
                        customerId = resultSet.getObject("customer_id", UUID::class.java),
                        maskedDisplayName = resultSet.getString("masked_display_name"),
                        maskedMatchedValue = resultSet.getString("masked_matched_value"),
                    )
                },
                customerId,
            ).singleOrNull()

    fun findByExactIndexes(query: ProtectedProfileExactQuery): List<MaskedCustomerSupportProfile> {
        val requested = query.indexes.joinToString(",") { "(?, ?)" }
        val matchedColumn =
            when (query.criterionType) {
                io.github.kdh949.beanflow.shared.api.ExactSearchCriterionType.PHONE -> "profile.masked_primary_phone"
                io.github.kdh949.beanflow.shared.api.ExactSearchCriterionType.EMAIL -> "profile.masked_primary_email"
            }
        val arguments = query.indexes.flatMap { listOf(it.keyVersion, it.digestBytes()) }.toMutableList<Any>()
        arguments.add(query.criterionType.name)
        arguments.add(query.limit)
        return jdbcTemplate.query(
            """
            WITH requested(index_key_version, blind_index) AS (VALUES $requested)
            SELECT DISTINCT profile.customer_id, profile.masked_display_name,
                   $matchedColumn AS masked_matched_value
              FROM requested
              JOIN identity_customer_support_profile_exact_index exact_index
                ON exact_index.index_key_version = requested.index_key_version
               AND exact_index.blind_index = requested.blind_index
              JOIN identity_customer_support_profile profile ON profile.customer_id = exact_index.customer_id
             WHERE exact_index.criterion_type = ?
               AND $matchedColumn IS NOT NULL
             ORDER BY profile.customer_id
             LIMIT ?
            """.trimIndent(),
            { resultSet, _ ->
                MaskedCustomerSupportProfile(
                    customerId = resultSet.getObject("customer_id", UUID::class.java),
                    maskedDisplayName = resultSet.getString("masked_display_name"),
                    maskedMatchedValue = resultSet.getString("masked_matched_value"),
                )
            },
            *arguments.toTypedArray(),
        )
    }
}
