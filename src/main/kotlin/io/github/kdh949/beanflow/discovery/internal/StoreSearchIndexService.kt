package io.github.kdh949.beanflow.discovery.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReplaceBrandSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.ReplaceStoreSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.SearchTextNormalizer
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
import io.github.kdh949.beanflow.shared.api.StoreSearchTermEntry
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.sql.PreparedStatement
import java.sql.Types
import java.util.UUID

/**
 * ADR-103 A4 relevance weights. They live in Discovery because ranking is a Discovery rule: the
 * Merchant commands that write terms state only which kind a term is.
 */
internal fun StoreSearchTermKind.relevanceWeight(): BigDecimal =
    when (this) {
        StoreSearchTermKind.STORE_NAME -> STORE_NAME_WEIGHT

        StoreSearchTermKind.BRAND_NAME -> BRAND_NAME_WEIGHT

        StoreSearchTermKind.REGION_SIDO,
        StoreSearchTermKind.REGION_SIGUNGU,
        StoreSearchTermKind.REGION_EUPMYEONDONG,
        StoreSearchTermKind.REGION_RI,
        -> REGION_WEIGHT

        StoreSearchTermKind.MENU_NAME -> MENU_NAME_WEIGHT
    }

private val STORE_NAME_WEIGHT = BigDecimal("1.00")
private val BRAND_NAME_WEIGHT = BigDecimal("0.90")

/** Every region level scores the same, including `REGION_RI` (ADR-103 A7). */
private val REGION_WEIGHT = BigDecimal("0.80")
private val MENU_NAME_WEIGHT = BigDecimal("0.70")

/** One row of `discovery_store_search_term`, already normalized and weighted. */
internal data class StoreSearchTermRow(
    val id: UUID,
    val storeId: UUID,
    val kind: StoreSearchTermKind,
    val sourceId: UUID?,
    val termNormalized: String,
    val displayText: String,
    val weight: BigDecimal,
)

/**
 * Discovery's implementation of the ADR-112 index write port.
 *
 * Every method is `Propagation.MANDATORY`. Implementation invariant 11 requires the index update
 * to commit or roll back with the command that caused it, and a missing caller transaction is the
 * one way that guarantee could be lost silently, so it is rejected instead of opening a new one.
 */
@Service
internal class StoreSearchIndexService(
    private val repository: StoreSearchIndexRepository,
    private val identifiers: IdentifierSource,
) : StoreSearchIndexOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun replaceStoreTerms(command: ReplaceStoreSearchTermsCommand) {
        repository.deleteTerms(listOf(command.storeId), command.kinds)
        val rows =
            command.terms
                .map { entry -> row(command.storeId, entry) }
                // Identity is (store, kind, source, normalized). Two entries that collapse to the
                // same identity carry the same fact twice, so keeping one is not a loss; letting
                // both through would only raise a unique violation.
                .distinctBy { Triple(it.kind, it.sourceId, it.termNormalized) }
        repository.insertTerms(rows)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun replaceBrandTerms(command: ReplaceBrandSearchTermsCommand) {
        if (command.storeIds.isEmpty()) return
        repository.deleteTerms(command.storeIds, setOf(StoreSearchTermKind.BRAND_NAME))
        val brandName = command.brandName ?: return
        repository.insertTerms(
            command.storeIds.map { storeId ->
                row(storeId, StoreSearchTermEntry(StoreSearchTermKind.BRAND_NAME, brandName))
            },
        )
    }

    /**
     * Normalization happens here and nowhere else on the write path, so the index can never hold a
     * string the query path would not produce for the same input (MD-2026-015).
     *
     * Text that normalizes to nothing, or that outgrows the column, is rejected rather than skipped
     * or truncated: either would leave a store that quietly cannot be found by that attribute.
     */
    private fun row(
        storeId: UUID,
        entry: StoreSearchTermEntry,
    ): StoreSearchTermRow {
        val normalized = SearchTextNormalizer.normalize(entry.displayText)
        if (normalized.isEmpty()) {
            reject("A ${entry.kind} search term is blank after normalization")
        }
        if (normalized.codePointCount(0, normalized.length) > MAX_NORMALIZED_LENGTH) {
            reject("A ${entry.kind} search term exceeds $MAX_NORMALIZED_LENGTH characters after normalization")
        }
        if (entry.displayText.codePointCount(0, entry.displayText.length) > MAX_DISPLAY_LENGTH) {
            reject("A ${entry.kind} search term exceeds $MAX_DISPLAY_LENGTH characters")
        }
        return StoreSearchTermRow(
            id = identifiers.next(),
            storeId = storeId,
            kind = entry.kind,
            sourceId = entry.sourceId,
            termNormalized = normalized,
            displayText = entry.displayText,
            weight = entry.kind.relevanceWeight(),
        )
    }

    private fun reject(message: String): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, message)

    private companion object {
        /** `discovery_store_search_term.display_text` is `varchar(200)`. */
        const val MAX_DISPLAY_LENGTH = 200

        /** `term_normalized` is `varchar(400)`: NFKC can lengthen the text it normalizes. */
        const val MAX_NORMALIZED_LENGTH = 400
    }
}

/**
 * The index table is written by whole kinds, never row by row.
 *
 * "This store's brand term is now X" and "this store has no brand term" are the same delete plus
 * insert, which is what makes a replace idempotent and free of a window in which a store has both
 * its old and its new term.
 */
@Repository
internal class StoreSearchIndexRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun deleteTerms(
        storeIds: List<UUID>,
        kinds: Collection<StoreSearchTermKind>,
    ): Int {
        if (storeIds.isEmpty() || kinds.isEmpty()) return 0
        val storePlaceholders = storeIds.joinToString(", ") { "?" }
        val kindPlaceholders = kinds.joinToString(", ") { "?" }
        val arguments: List<Any> = storeIds + kinds.map { it.name }
        return jdbcTemplate.update(
            """
            DELETE FROM discovery_store_search_term
             WHERE store_id IN ($storePlaceholders)
               AND term_kind IN ($kindPlaceholders)
            """.trimIndent(),
            *arguments.toTypedArray(),
        )
    }

    fun insertTerms(rows: List<StoreSearchTermRow>) {
        if (rows.isEmpty()) return
        jdbcTemplate.batchUpdate(
            """
            INSERT INTO discovery_store_search_term
                (id, store_id, term_kind, source_id, term_normalized, display_text, weight)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            object : BatchPreparedStatementSetter {
                override fun getBatchSize(): Int = rows.size

                override fun setValues(
                    statement: PreparedStatement,
                    index: Int,
                ) {
                    val row = rows[index]
                    statement.setObject(1, row.id)
                    statement.setObject(2, row.storeId)
                    statement.setString(3, row.kind.name)
                    // A null `uuid` needs its type stated; an untyped null makes PostgreSQL
                    // reject the parameter rather than store SQL NULL.
                    if (row.sourceId == null) {
                        statement.setNull(4, Types.OTHER)
                    } else {
                        statement.setObject(4, row.sourceId)
                    }
                    statement.setString(5, row.termNormalized)
                    statement.setString(6, row.displayText)
                    statement.setBigDecimal(7, row.weight)
                }
            },
        )
    }

    fun countStoresWithTerm(kind: StoreSearchTermKind): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(DISTINCT store_id) FROM discovery_store_search_term WHERE term_kind = ?",
            Long::class.java,
            kind.name,
        ) ?: throw IllegalStateException("Search term coverage query returned no row")
}
