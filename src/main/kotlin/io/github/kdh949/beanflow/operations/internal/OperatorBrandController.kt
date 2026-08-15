package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.BrandSnapshot
import io.github.kdh949.beanflow.merchant.api.BrandStatus
import io.github.kdh949.beanflow.merchant.api.StoreBrandAssignment
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreateBrandRequest(
    @field:Size(min = 1, max = 120)
    val name: String,
    @field:Size(min = 1, max = 200)
    val reason: String,
)

data class UpdateBrandRequest(
    @field:Size(min = 1, max = 120)
    val name: String?,
    val status: BrandStatus?,
    val expectedVersion: Long?,
    @field:Size(min = 1, max = 200)
    val reason: String,
)

data class AssignStoreBrandRequest(
    val brandId: UUID,
    @field:Size(min = 1, max = 200)
    val reason: String,
)

data class ClearStoreBrandRequest(
    @field:Size(min = 1, max = 200)
    val reason: String,
)

data class BrandResponse(
    val brandId: UUID,
    val name: String,
    val status: BrandStatus,
    val assignedStoreCount: Int,
    val version: Long,
) {
    companion object {
        fun of(brand: BrandSnapshot) = BrandResponse(brand.brandId, brand.name, brand.status, brand.assignedStoreCount, brand.version)
    }
}

data class BrandPageResponse(
    val items: List<BrandResponse>,
    val page: BrandPageInfo,
)

data class BrandPageInfo(
    val nextCursor: String?,
)

data class StoreBrandResponse(
    val storeId: UUID,
    val brandId: UUID?,
    val brandName: String?,
) {
    companion object {
        fun of(assignment: StoreBrandAssignment) = StoreBrandResponse(assignment.storeId, assignment.brandId, assignment.brandName)
    }
}

/**
 * Operator brand administration (ADR-112 4절).
 *
 * `PLATFORM_OPERATOR` is the role gate and `STORE_BRAND_MANAGE` the explicit grant the service
 * checks inside the transaction. 매장주가 스스로 유명 브랜드에 편입될 수 없어야 하므로 매장
 * 브랜드 지정도 이 경로에만 있다.
 *
 * The relevance score of a brand term is never exposed here; brands carry only their name, status
 * and store count.
 */
@Validated
@RestController
@RequestMapping("/api/v1/operations")
internal class OperatorBrandController(
    private val service: OperatorBrandService,
) {
    @PostMapping("/brands")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun create(
        actor: OperatorActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: CreateBrandRequest,
    ): BrandResponse = BrandResponse.of(service.create(context(actor, idempotencyKey, request.reason), request.name))

    @GetMapping("/brands")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun list(
        actor: OperatorActor,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): BrandPageResponse {
        val page = service.list(actorId(actor), cursor, limit)
        return BrandPageResponse(page.brands.map(BrandResponse::of), BrandPageInfo(page.nextCursor))
    }

    @GetMapping("/brands/{brandId}")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun find(
        actor: OperatorActor,
        @PathVariable brandId: UUID,
    ): BrandResponse = BrandResponse.of(service.find(actorId(actor), brandId))

    @PatchMapping("/brands/{brandId}")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun update(
        actor: OperatorActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable brandId: UUID,
        @Valid @RequestBody request: UpdateBrandRequest,
    ): BrandResponse =
        BrandResponse.of(
            service.update(
                context(actor, idempotencyKey, request.reason),
                brandId,
                request.name,
                request.status,
                request.expectedVersion,
            ),
        )

    @PutMapping("/stores/{storeId}/brand")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun assign(
        actor: OperatorActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable storeId: UUID,
        @Valid @RequestBody request: AssignStoreBrandRequest,
    ): StoreBrandResponse = StoreBrandResponse.of(service.assign(context(actor, idempotencyKey, request.reason), storeId, request.brandId))

    @DeleteMapping("/stores/{storeId}/brand")
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun clear(
        actor: OperatorActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @PathVariable storeId: UUID,
        @Valid @RequestBody request: ClearStoreBrandRequest,
    ): StoreBrandResponse = StoreBrandResponse.of(service.clear(context(actor, idempotencyKey, request.reason), storeId))

    private fun context(
        actor: OperatorActor,
        idempotencyKey: String,
        reason: String,
    ) = OperatorBrandCommandContext(actorId(actor), idempotencyKey, reason)

    private fun actorId(actor: OperatorActor): UUID =
        try {
            actor.actorId
        } catch (_: RuntimeException) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated subject is not a valid operator actor ID")
        }
}
