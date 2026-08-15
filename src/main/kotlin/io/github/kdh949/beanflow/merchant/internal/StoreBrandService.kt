package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.merchant.api.AssignStoreBrandCommand
import io.github.kdh949.beanflow.merchant.api.BrandCommand
import io.github.kdh949.beanflow.merchant.api.BrandPage
import io.github.kdh949.beanflow.merchant.api.BrandSnapshot
import io.github.kdh949.beanflow.merchant.api.BrandStatus
import io.github.kdh949.beanflow.merchant.api.ClearStoreBrandCommand
import io.github.kdh949.beanflow.merchant.api.CreateBrandCommand
import io.github.kdh949.beanflow.merchant.api.StoreBrandAssignment
import io.github.kdh949.beanflow.merchant.api.StoreBrandOperations
import io.github.kdh949.beanflow.merchant.api.StoreBrandQueryOperations
import io.github.kdh949.beanflow.merchant.api.UpdateBrandCommand
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.IdentifierSource
import io.github.kdh949.beanflow.shared.api.ReplaceBrandSearchTermsCommand
import io.github.kdh949.beanflow.shared.api.SearchTextNormalizer
import io.github.kdh949.beanflow.shared.api.StoreSearchIndexOperations
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
import java.util.UUID

/**
 * Brand commands and the search terms they own.
 *
 * Each command runs inside the caller's transaction ([Propagation.MANDATORY]). That is not a
 * convention but the mechanism behind implementation invariant 11: the brand row, the affected
 * stores' `BRAND_NAME` terms, the replay ledger entry and the caller's AuditRecord either all
 * commit or all roll back. A brand renamed without its terms would be invisible to search with no
 * failure anywhere to explain it.
 *
 * Replay is keyed on `(actorId, idempotencyKey)` and compares a hash of the whole request. A
 * repeat of the same request returns the stored result without touching anything; the same key
 * with a different request is [FailureCode.IDEMPOTENCY_KEY_REUSED].
 */
@Service
internal class StoreBrandService(
    private val repository: BrandRepository,
    private val searchIndex: StoreSearchIndexOperations,
    private val identifiers: IdentifierSource,
    private val objectMapper: ObjectMapper,
) : StoreBrandOperations,
    StoreBrandQueryOperations {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun create(command: CreateBrandCommand): BrandSnapshot =
        replayable(command, CommandType.CREATE_BRAND, payload(command.name), BrandSnapshot::class.java) {
            val name = validName(command.name)
            val normalized = normalizedName(name)
            requireNameAvailable(normalized, null)
            val brand =
                BrandRow(
                    id = identifiers.next(),
                    name = name,
                    normalizedName = normalized,
                    status = BrandStatus.ACTIVE,
                    version = 0,
                    createdAt = command.now,
                    updatedAt = command.now,
                )
            repository.insert(brand)
            // 새 브랜드에는 소속 매장이 없으므로 갱신할 term도 없다. 매장이 붙는 시점은
            // assignStoreBrand이며 그때 그 매장의 term을 만든다.
            brand.toSnapshot(assignedStoreCount = 0, version = brand.version)
        }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun update(command: UpdateBrandCommand): BrandSnapshot =
        replayable(
            command,
            CommandType.UPDATE_BRAND,
            payload(command.brandId, command.name, command.status, command.expectedVersion),
            BrandSnapshot::class.java,
        ) {
            if (command.name == null && command.status == null) {
                reject(FailureCode.INVALID_REQUEST, "A brand update must change the name, the status or both")
            }
            val current = repository.findLocked(command.brandId) ?: notFound(command.brandId)
            if (command.expectedVersion != null && command.expectedVersion != current.version) {
                reject(FailureCode.BRAND_STATE_CONFLICT, "The brand changed since it was read")
            }
            val name = command.name?.let(::validName) ?: current.name
            val normalized = normalizedName(name)
            val status = command.status ?: current.status
            if (normalized != current.normalizedName || status != current.status) {
                requireNameAvailable(normalized, current.id)
            }

            val assignedStoreCount = repository.countAssignedStores(current.id)
            if (status == BrandStatus.ARCHIVED && current.status == BrandStatus.ACTIVE && assignedStoreCount > 0) {
                // 보관은 이름을 다시 쓸 수 있게 만든다. 소속 매장을 남긴 채 보관하면 새 브랜드가
                // 같은 이름을 차지해 색인에 서로 다른 브랜드의 같은 이름 term이 공존한다.
                reject(
                    FailureCode.BRAND_STATE_CONFLICT,
                    "A brand with $assignedStoreCount assigned stores cannot be archived before they are cleared",
                )
            }
            val renamed = name != current.name
            if (renamed) requireFanoutWithinLimit(assignedStoreCount)

            val updated = current.copy(name = name, normalizedName = normalized, status = status, updatedAt = command.now)
            if (!repository.update(updated, current.version)) {
                reject(FailureCode.BRAND_STATE_CONFLICT, "The brand changed while it was being updated")
            }
            if (renamed) {
                val storeIds = repository.findAssignedStoreIds(current.id, MAX_BRAND_FANOUT)
                searchIndex.replaceBrandTerms(ReplaceBrandSearchTermsCommand(storeIds, name))
            }
            updated.toSnapshot(assignedStoreCount, version = current.version + 1)
        }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun assignStoreBrand(command: AssignStoreBrandCommand): StoreBrandAssignment =
        replayable(
            command,
            CommandType.ASSIGN_STORE_BRAND,
            payload(command.storeId, command.brandId),
            StoreBrandAssignment::class.java,
        ) {
            val store = repository.findStoreBrandLocked(command.storeId) ?: notFoundStore(command.storeId)
            // 두 매장을 서로 반대 순서의 브랜드 쌍에 배정하는 요청이 교착하지 않도록 브랜드 행은
            // 항상 id 오름차순으로 잠근다.
            val brands = listOfNotNull(store.brandId, command.brandId).distinct().sorted()
            val locked = brands.associateWith { repository.findLocked(it) }
            val target = locked[command.brandId] ?: notFound(command.brandId)
            if (target.status != BrandStatus.ACTIVE) {
                reject(FailureCode.BRAND_STATE_CONFLICT, "An archived brand cannot be assigned to a store")
            }
            if (store.brandId != command.brandId) {
                // 상한을 배정에서도 지킨다. 상한을 넘긴 브랜드는 이름을 영영 바꿀 수 없게 되므로
                // 나중에 이름 변경이 갑자기 막히는 것보다 지금 거절하는 편이 낫다.
                requireFanoutWithinLimit(repository.countAssignedStores(command.brandId) + 1)
                repository.updateStoreBrand(command.storeId, command.brandId)
            }
            searchIndex.replaceBrandTerms(ReplaceBrandSearchTermsCommand(listOf(command.storeId), target.name))
            StoreBrandAssignment(command.storeId, target.id, target.name)
        }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun clearStoreBrand(command: ClearStoreBrandCommand): StoreBrandAssignment =
        replayable(command, CommandType.CLEAR_STORE_BRAND, payload(command.storeId), StoreBrandAssignment::class.java) {
            val store = repository.findStoreBrandLocked(command.storeId) ?: notFoundStore(command.storeId)
            store.brandId?.let(repository::findLocked)
            repository.updateStoreBrand(command.storeId, null)
            // 브랜드가 없던 매장에도 실행한다. 색인에 고아 BRAND_NAME term이 남아 있을 수 있고
            // 해제는 그 상태를 바로잡는 명령이기도 하다.
            searchIndex.replaceBrandTerms(ReplaceBrandSearchTermsCommand(listOf(command.storeId), null))
            StoreBrandAssignment(command.storeId, null, null)
        }

    @Transactional(readOnly = true)
    override fun find(brandId: UUID): BrandSnapshot? =
        repository.find(brandId)?.let { brand ->
            brand.toSnapshot(repository.countAssignedStores(brandId), brand.version)
        }

    @Transactional(readOnly = true)
    override fun list(
        afterNormalizedName: String?,
        afterBrandId: UUID?,
        limit: Int,
    ): BrandPage {
        require(limit in 1..MAX_PAGE_SIZE) { "Brand page size is invalid" }
        val rows = repository.page(afterNormalizedName, afterBrandId, limit + 1)
        val page = rows.take(limit)
        val counts = repository.countAssignedStores(page.map(BrandRow::id))
        val last = page.lastOrNull()
        return BrandPage(
            brands = page.map { brand -> brand.toSnapshot(counts[brand.id] ?: 0, brand.version) },
            nextNormalizedName = if (rows.size > limit) last?.normalizedName else null,
            nextBrandId = if (rows.size > limit) last?.id else null,
        )
    }

    /**
     * Runs [command] once per `(actorId, idempotencyKey)` and stores its result.
     *
     * The stored result is the domain result rather than an HTTP body: the caller owns the wire
     * shape, and a replay has to reproduce the decision, not a serialization of it.
     */
    private fun <T : Any> replayable(
        command: BrandCommand,
        commandType: CommandType,
        payload: String,
        resultType: Class<T>,
        execute: () -> T,
    ): T {
        validateIdempotencyKey(command.idempotencyKey)
        val hash = sha256("${commandType.name}\u001F$payload")
        repository.findCommand(command.actorId, command.idempotencyKey)?.let { existing ->
            if (existing.payloadHash != hash) {
                reject(FailureCode.IDEMPOTENCY_KEY_REUSED, "Idempotency-Key was reused with another brand command")
            }
            return objectMapper.readValue(existing.responseJson, resultType)
        }
        val result = execute()
        repository.insertCommand(
            id = identifiers.next(),
            actorId = command.actorId,
            commandType = commandType.name,
            idempotencyKey = command.idempotencyKey,
            payloadHash = hash,
            responseJson = objectMapper.writeValueAsString(result),
            now = command.now,
        )
        return result
    }

    /**
     * The request fields that make two commands "the same request".
     *
     * The separator is a unit separator so that no combination of field values can produce the
     * same string as a different combination. `now` is excluded: a retry arrives later than the
     * original and must still be recognised as a replay.
     */
    private fun payload(vararg fields: Any?): String = fields.joinToString("\u001F") { it?.toString() ?: "" }

    private fun requireNameAvailable(
        normalizedName: String,
        selfId: UUID?,
    ) {
        val existing = repository.findActiveByNormalizedName(normalizedName) ?: return
        if (existing.id != selfId) {
            reject(FailureCode.BRAND_NAME_ALREADY_IN_USE, "Another active brand already uses this name")
        }
    }

    private fun requireFanoutWithinLimit(storeCount: Int) {
        if (storeCount > MAX_BRAND_FANOUT) {
            reject(
                FailureCode.BRAND_FANOUT_LIMIT_EXCEEDED,
                "A brand may not exceed $MAX_BRAND_FANOUT assigned stores",
            )
        }
    }

    private fun validName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH || name.any { it.isISOControl() }) {
            reject(FailureCode.INVALID_REQUEST, "A brand name must be 1 to $MAX_NAME_LENGTH characters without control characters")
        }
        return name
    }

    private fun normalizedName(name: String): String {
        val normalized = SearchTextNormalizer.normalize(name)
        if (normalized.isEmpty()) {
            reject(FailureCode.INVALID_REQUEST, "A brand name must not be blank after normalization")
        }
        if (normalized.length > MAX_NAME_LENGTH) {
            // NFKC는 문자를 늘릴 수 있어 정규화 결과가 원본보다 길어질 수 있다. 잘라 넣으면
            // 서로 다른 브랜드가 같은 정규화 이름을 갖게 되므로 거절한다.
            reject(FailureCode.INVALID_REQUEST, "A brand name exceeds $MAX_NAME_LENGTH characters after normalization")
        }
        return normalized
    }

    private fun validateIdempotencyKey(key: String) {
        if (key.length !in 8..128 || key != key.trim() || key.any { it.isISOControl() }) {
            reject(FailureCode.INVALID_REQUEST, "Idempotency-Key must contain 8 to 128 non-control characters without outer whitespace")
        }
    }

    private fun notFound(brandId: UUID): Nothing =
        throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Brand not found", targetReference = brandId.toString())

    private fun notFoundStore(storeId: UUID): Nothing =
        throw DomainFailure(FailureCode.RESOURCE_NOT_FOUND, "Store not found", targetReference = storeId.toString())

    private fun reject(
        code: FailureCode,
        message: String,
    ): Nothing = throw DomainFailure(code, message)

    private fun BrandRow.toSnapshot(
        assignedStoreCount: Int,
        version: Long,
    ) = BrandSnapshot(
        brandId = id,
        name = name,
        normalizedName = normalizedName,
        status = status,
        assignedStoreCount = assignedStoreCount,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    internal enum class CommandType {
        CREATE_BRAND,
        UPDATE_BRAND,
        ASSIGN_STORE_BRAND,
        CLEAR_STORE_BRAND,
    }

    internal companion object {
        /** ADR-112 6절. 이름 변경 fan-out을 한 transaction 안에 담을 수 있는 상한이다. */
        const val MAX_BRAND_FANOUT = 1_000
        const val MAX_NAME_LENGTH = 120
        const val MAX_PAGE_SIZE = 100

        fun sha256(text: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8)))
    }
}

@Component
internal class BrandCommandRetentionWorker(
    private val cleanup: BrandCommandRetentionCleanup,
    private val clock: Clock,
    @Value("\${beanflow.brand-command.retention.batch-size:100}")
    private val batchSize: Int,
) {
    @Scheduled(
        fixedDelayString = "\${beanflow.brand-command.retention.fixed-delay-ms:3600000}",
        initialDelayString = "\${beanflow.brand-command.retention.initial-delay-ms:3600000}",
    )
    fun cleanupExpired() {
        cleanup.deleteExpired(clock.instant(), batchSize)
    }
}
