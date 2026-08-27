package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.merchant.api.MenuCatalogLifecycle
import io.github.kdh949.beanflow.merchant.api.MenuCatalogSummary
import io.github.kdh949.beanflow.merchant.api.MenuConfigurationTradeContent
import io.github.kdh949.beanflow.merchant.api.MenuOptionTradeContent
import io.github.kdh949.beanflow.merchant.api.MenuSellableRequirement
import io.github.kdh949.beanflow.merchant.api.MenuTradeContent
import io.github.kdh949.beanflow.merchant.api.MenuTradeDefinition
import io.github.kdh949.beanflow.shared.api.MerchantActor
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
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

data class MenuOptionTradeContentRequest(
    val optionId: UUID,
    @field:NotBlank
    @field:Size(max = 200)
    val name: String,
    @field:Min(0)
    val additionalPriceKrw: Long,
    val available: Boolean,
)

data class MenuSellableRequirementRequest(
    val sellableUnitId: UUID,
    @field:Min(1)
    val quantityPerLineUnit: Long,
)

data class MenuConfigurationTradeContentRequest(
    val configurationId: UUID,
    @field:Size(max = 100)
    val selectedOptionIds: List<UUID>,
    val available: Boolean,
    @field:Size(min = 1, max = 50)
    @field:Valid
    val requirements: List<MenuSellableRequirementRequest>,
)

data class MenuTradeContentRequest(
    val menuId: UUID,
    @field:NotBlank
    @field:Size(max = 200)
    val name: String,
    @field:Min(0)
    val basePriceKrw: Long,
    val available: Boolean,
    @field:Size(max = 100)
    @field:Valid
    val options: List<MenuOptionTradeContentRequest>,
    @field:Size(max = 500)
    @field:Valid
    val configurations: List<MenuConfigurationTradeContentRequest>,
) {
    fun definition(): MenuTradeDefinition =
        MenuTradeDefinition(
            menuId,
            name,
            basePriceKrw,
            available,
            options.map { MenuOptionTradeContent(it.optionId, it.name, it.additionalPriceKrw, it.available) },
            configurations.map { configuration ->
                MenuConfigurationTradeContent(
                    configuration.configurationId,
                    configuration.selectedOptionIds,
                    configuration.available,
                    configuration.requirements.map {
                        MenuSellableRequirement(it.sellableUnitId, it.quantityPerLineUnit)
                    },
                )
            },
        )
}

data class ReplaceMenuTradeContentRequest(
    @field:Min(0)
    val expectedVersion: Long,
    val menuId: UUID,
    @field:NotBlank
    @field:Size(max = 200)
    val name: String,
    @field:Min(0)
    val basePriceKrw: Long,
    val available: Boolean,
    @field:Size(max = 100)
    @field:Valid
    val options: List<MenuOptionTradeContentRequest>,
    @field:Size(max = 500)
    @field:Valid
    val configurations: List<MenuConfigurationTradeContentRequest>,
) {
    fun definition(): MenuTradeDefinition =
        MenuTradeContentRequest(menuId, name, basePriceKrw, available, options, configurations).definition()
}

data class ArchiveMenuRequest(
    @field:Min(0)
    val expectedVersion: Long,
)

data class MenuCatalogPageResponse(
    val items: List<MenuCatalogSummary>,
    val nextCursor: String?,
)

@Validated
@RestController
@RequestMapping("/api/v1/stores/{storeId}")
internal class MenuCatalogController(
    private val service: MenuCatalogApplicationService,
) {
    @GetMapping("/menu-catalog")
    fun list(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @RequestParam(defaultValue = "ACTIVE") lifecycle: MenuCatalogLifecycle,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(required = false) @Min(1) @Max(50) limit: Int?,
    ): MenuCatalogPageResponse {
        val page = service.list(actor.actorId, storeId, lifecycle, cursor, limit)
        return MenuCatalogPageResponse(page.items, page.nextCursor)
    }

    @GetMapping("/menus/{menuId}/trade-content")
    fun find(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable menuId: UUID,
    ): MenuTradeContent = service.find(actor.actorId, storeId, menuId)

    @PostMapping("/menus")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: MenuTradeContentRequest,
    ): MenuTradeContent = service.create(MenuCatalogCommandContext(actor.actorId, idempotencyKey), storeId, request.definition())

    @PutMapping("/menus/{menuId}/trade-content")
    fun replace(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable menuId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: ReplaceMenuTradeContentRequest,
    ): MenuTradeContent =
        service.replace(
            MenuCatalogCommandContext(actor.actorId, idempotencyKey),
            storeId,
            menuId,
            request.expectedVersion,
            request.definition(),
        )

    @PostMapping("/menus/{menuId}/archive")
    fun archive(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable menuId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) idempotencyKey: String,
        @Valid @RequestBody request: ArchiveMenuRequest,
    ): MenuTradeContent =
        service.archive(
            MenuCatalogCommandContext(actor.actorId, idempotencyKey),
            storeId,
            menuId,
            request.expectedVersion,
        )
}
