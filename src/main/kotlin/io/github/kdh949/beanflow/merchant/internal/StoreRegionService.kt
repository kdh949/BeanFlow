package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.AssignStoreRegionCommand
import io.github.kdh949.beanflow.merchant.api.RegionCatalogQueryOperations
import io.github.kdh949.beanflow.merchant.api.RegionPage
import io.github.kdh949.beanflow.merchant.api.RegionSnapshot
import io.github.kdh949.beanflow.merchant.api.StoreRegionAssignment
import io.github.kdh949.beanflow.merchant.api.StoreRegionOperations
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReplaceStoreSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
import io.github.kdh949.beanflow.shared.api.StoreSearchTermEntry
import io.github.kdh949.beanflow.shared.api.StoreSearchTermKind
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat

/**
 * Store region assignment and the `REGION_*` search terms it owns.
 *
 * The command runs inside the caller's transaction ([Propagation.MANDATORY]) so that the store's
 * `region_code`, its region terms, the replay ledger entry and the caller's AuditRecord share one
 * commit. A store whose region changed without its terms would be searchable under the region it
 * left and invisible under the one it moved to, with nothing to explain it.
 *
 * Every assignment replaces all four `REGION_*` kinds even when the new region has no 리, because
 * a move from a 리 to a 동 has to delete the old `REGION_RI` row rather than leave it behind.
 */
@Service
internal class StoreRegionService(
    private val repository: RegionRepository,
    private val searchIndex: StoreSearchIndexOperations,
    private val identifiers: IdentifierSource,
    private val objectMapper: ObjectMapper,
) : StoreRegionOperations,
    RegionCatalogQueryOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun assignStoreRegion(command: AssignStoreRegionCommand): StoreRegionAssignment {
        validateIdempotencyKey(command.idempotencyKey)
        val regionCode = validRegionCode(command.regionCode)
        val payload = listOf(CommandType.ASSIGN_STORE_REGION.name, command.storeId.toString(), regionCode)
        val hash = sha256(payload.joinToString(FIELD_SEPARATOR))
        repository.findCommand(command.actorId, command.idempotencyKey)?.let { existing ->
            if (existing.payloadHash != hash) {
                reject(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused with another region command")
            }
            return objectMapper.readValue(existing.responseJson, StoreRegionAssignment::class.java)
        }

        val store =
            repository.findStoreRegionLocked(command.storeId)
                ?: throw DomainFailure(
                    FailureCode.RESOURCE_NOT_FOUND,
                    "Store discovery profile not found",
                    targetReference = command.storeId.toString(),
                )
        val region =
            repository.find(regionCode)
                ?: throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Region code not found", targetReference = regionCode)

        repository.updateStoreRegion(command.storeId, region.code)
        searchIndex.replaceStoreTerms(
            ReplaceStoreSearchTermsCommand(
                storeId = command.storeId,
                kinds = REGION_TERM_KINDS,
                terms = region.terms(),
            ),
        )

        val result = StoreRegionAssignment(command.storeId, region, store.regionCode)
        repository.insertCommand(
            id = identifiers.next(),
            actorId = command.actorId,
            commandType = CommandType.ASSIGN_STORE_REGION.name,
            idempotencyKey = command.idempotencyKey,
            payloadHash = hash,
            responseJson = objectMapper.writeValueAsString(result),
            now = command.now,
        )
        return result
    }

    @Transactional(readOnly = true)
    override fun find(code: String): RegionSnapshot? = repository.find(code.trim())

    @Transactional(readOnly = true)
    override fun search(
        query: String?,
        afterFullName: String?,
        afterCode: String?,
        limit: Int,
    ): RegionPage {
        require(limit in 1..MAX_PAGE_SIZE) { "Region page size is invalid" }
        val tokens = tokens(query)
        val rows = repository.search(tokens, afterFullName, afterCode, limit + 1)
        val page = rows.take(limit)
        val last = page.lastOrNull()
        return RegionPage(
            regions = page,
            nextFullName = if (rows.size > limit) last?.fullName else null,
            nextCode = if (rows.size > limit) last?.code else null,
        )
    }

    /**
     * The search words a caller typed, all of which must appear in a region's full name.
     *
     * A blank query is no filter at all, which is how the picker lists the vocabulary from the
     * start. Too many words is a rejected request rather than a silently truncated one.
     */
    private fun tokens(query: String?): List<String> {
        val raw = query?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        if (raw.any { it.isISOControl() }) {
            reject(FailureCode.INVALID_REQUEST, "A region query must not contain control characters")
        }
        val tokens = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size > MAX_QUERY_TOKENS) {
            reject(FailureCode.INVALID_REQUEST, "A region query may contain at most $MAX_QUERY_TOKENS words")
        }
        if (tokens.any { it.length > MAX_QUERY_TOKEN_LENGTH }) {
            reject(FailureCode.INVALID_REQUEST, "A region query word may not exceed $MAX_QUERY_TOKEN_LENGTH characters")
        }
        return tokens
    }

    /**
     * The `REGION_*` terms of one region, one per level that exists.
     *
     * A 리 region yields four, a 동 region three and a 시도 row one. The levels are read from the
     * vocabulary row rather than split out of the full name, so `강남구` and `강남 구` cannot both
     * appear as terms for the same place.
     */
    private fun RegionSnapshot.terms(): List<StoreSearchTermEntry> =
        buildList {
            add(StoreSearchTermEntry(StoreSearchTermKind.REGION_SIDO, sido))
            if (sigungu.isNotBlank()) add(StoreSearchTermEntry(StoreSearchTermKind.REGION_SIGUNGU, sigungu))
            if (eupmyeondong.isNotBlank()) add(StoreSearchTermEntry(StoreSearchTermKind.REGION_EUPMYEONDONG, eupmyeondong))
            if (ri.isNotBlank()) add(StoreSearchTermEntry(StoreSearchTermKind.REGION_RI, ri))
        }

    private fun validRegionCode(raw: String): String {
        val code = raw.trim()
        if (!REGION_CODE.matches(code)) {
            reject(FailureCode.INVALID_REQUEST, "A region code is exactly 10 digits")
        }
        return code
    }

    private fun validateIdempotencyKey(key: String) {
        if (key.length !in 8..128 || key != key.trim() || key.any { it.isISOControl() }) {
            reject(FailureCode.INVALID_REQUEST, "Idempotency-Key must contain 8 to 128 non-control characters without outer whitespace")
        }
    }

    private fun sha256(text: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)))

    private fun reject(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    internal enum class CommandType {
        ASSIGN_STORE_REGION,
    }

    internal companion object {
        const val MAX_PAGE_SIZE = 100
        const val MAX_QUERY_TOKENS = 4
        const val MAX_QUERY_TOKEN_LENGTH = 40

        /**
         * Always replaced together. Assigning a region that has no 리 must remove the `REGION_RI`
         * term the previous region left behind.
         */
        val REGION_TERM_KINDS =
            setOf(
                StoreSearchTermKind.REGION_SIDO,
                StoreSearchTermKind.REGION_SIGUNGU,
                StoreSearchTermKind.REGION_EUPMYEONDONG,
                StoreSearchTermKind.REGION_RI,
            )

        private val REGION_CODE = Regex("^[0-9]{10}$")

        /**
         * Separates the fields of the replay payload. `now` is not one of them: a retry arrives
         * later than the original and must still be recognised as a replay.
         */
        private const val FIELD_SEPARATOR = "\u001F"
    }
}

@Component
internal class StoreRegionCommandRetentionWorker(
    private val cleanup: StoreRegionCommandRetentionCleanup,
    private val clock: Clock,
    @Value("\${beanflow.store-region-command.retention.batch-size:100}")
    private val batchSize: Int,
) {
    @Scheduled(
        fixedDelayString = "\${beanflow.store-region-command.retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.store-region-command.retention.initial-delay-ms:3600000}",
    )
    fun cleanupExpired() {
        cleanup.deleteExpired(clock.instant(), batchSize)
    }
}
