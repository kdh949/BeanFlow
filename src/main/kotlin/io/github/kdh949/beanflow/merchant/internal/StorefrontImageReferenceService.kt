package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageReferenceOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
internal class StorefrontImageReferenceService(
    private val jdbc: JdbcTemplate,
) : StorefrontImageReferenceOperations {
    @Transactional(readOnly = true)
    override fun isReferenced(key: String): Boolean =
        try {
            jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                      FROM merchant_store
                     WHERE image_original_key = ? OR image_thumbnail_key = ?
                    UNION ALL
                    SELECT 1
                      FROM merchant_menu
                     WHERE image_original_key = ? OR image_thumbnail_key = ?
                )
                """.trimIndent(),
                Boolean::class.java,
                key,
                key,
                key,
                key,
            ) == true
        } catch (failure: DataAccessException) {
            throw DomainFailure(
                FailureCode.DEPENDENCY_UNAVAILABLE,
                "Storefront image reference check is unavailable",
            ).also { it.initCause(failure) }
        }
}
