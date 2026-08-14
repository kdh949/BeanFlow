package io.github.kdh949.beanflow.merchant.api

import java.time.Instant
import java.util.UUID

enum class BrandStatus {
    ACTIVE,
    ARCHIVED,
}

/**
 * A brand as the operator surface sees it.
 *
 * [assignedStoreCount] is part of the snapshot because it is the operator's only view of the
 * fan-out cost of a later rename. ADR-112 6절의 1000개 상한이 언제 가까워지는지 알 수 없으면
 * 이름 변경이 갑자기 409가 되는 것처럼 보인다.
 */
data class BrandSnapshot(
    val brandId: UUID,
    val name: String,
    val normalizedName: String,
    val status: BrandStatus,
    val assignedStoreCount: Int,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class StoreBrandAssignment(
    val storeId: UUID,
    val brandId: UUID?,
    val brandName: String?,
)

/**
 * What every brand command carries.
 *
 * `actorId` and `idempotencyKey` together identify one attempt, so a retry is recognised without
 * the caller having to remember what it sent. `now` is the caller's clock reading, not the
 * database's, so that a replay and its original agree on when the command happened.
 */
sealed interface BrandCommand {
    val actorId: UUID
    val idempotencyKey: String
    val now: Instant
}

data class CreateBrandCommand(
    override val actorId: UUID,
    override val idempotencyKey: String,
    val name: String,
    override val now: Instant,
) : BrandCommand

/**
 * A partial brand change. `null` fields are left alone; at least one must be present.
 *
 * [expectedVersion] is optional so that a first-time correction does not require a read, but a
 * client that has read the brand can pass it to be told about a concurrent change instead of
 * overwriting it.
 */
data class UpdateBrandCommand(
    override val actorId: UUID,
    override val idempotencyKey: String,
    val brandId: UUID,
    val name: String?,
    val status: BrandStatus?,
    val expectedVersion: Long?,
    override val now: Instant,
) : BrandCommand

data class AssignStoreBrandCommand(
    override val actorId: UUID,
    override val idempotencyKey: String,
    val storeId: UUID,
    val brandId: UUID,
    override val now: Instant,
) : BrandCommand

data class ClearStoreBrandCommand(
    override val actorId: UUID,
    override val idempotencyKey: String,
    val storeId: UUID,
    override val now: Instant,
) : BrandCommand

/**
 * Operator-driven brand writes, owned by `merchant` (ADR-112 1절).
 *
 * Every method joins the caller's transaction, and each one replaces the affected stores'
 * `BRAND_NAME` search terms inside it. 색인 갱신이 실패하면 브랜드 변경도 함께 rollback된다
 * (불변식 11). 호출자는 권한 확인과 AuditRecord를 같은 transaction에서 처리한다.
 *
 * Idempotency lives here rather than in the caller because the replay ledger must commit or roll
 * back together with the write it describes.
 */
interface StoreBrandOperations {
    fun create(command: CreateBrandCommand): BrandSnapshot

    fun update(command: UpdateBrandCommand): BrandSnapshot

    fun assignStoreBrand(command: AssignStoreBrandCommand): StoreBrandAssignment

    fun clearStoreBrand(command: ClearStoreBrandCommand): StoreBrandAssignment
}

data class BrandPage(
    val brands: List<BrandSnapshot>,
    val nextNormalizedName: String?,
    val nextBrandId: UUID?,
)

interface StoreBrandQueryOperations {
    fun find(brandId: UUID): BrandSnapshot?

    /** Keyset page ordered by `(normalizedName ASC, brandId ASC)`. */
    fun list(
        afterNormalizedName: String?,
        afterBrandId: UUID?,
        limit: Int,
    ): BrandPage
}
