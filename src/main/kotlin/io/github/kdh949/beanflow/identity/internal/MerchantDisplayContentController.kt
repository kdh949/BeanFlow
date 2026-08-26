package io.github.kdh949.beanflow.identity.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.merchant.api.MenuDisplayContentSnapshot
import io.github.kdh949.beanflow.merchant.api.ReplaceMenuDisplayContentCommand
import io.github.kdh949.beanflow.merchant.api.ReplaceStoreCustomerDisplayCommand
import io.github.kdh949.beanflow.merchant.api.StoreCustomerDisplaySnapshot
import io.github.kdh949.beanflow.merchant.api.StoreOperatingDay
import io.github.kdh949.beanflow.shared.api.MerchantActor
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.UUID

internal data class StoreOperatingDayRequest(
    val dayOfWeek: DayOfWeek,
    val closed: Boolean,
    val opensAt: LocalTime?,
    val closesAt: LocalTime?,
)

internal data class StoreOperatingHoursRequest(
    val timezone: String,
    val days: List<StoreOperatingDayRequest>,
)

internal data class ReplaceStoreCustomerDisplayRequest(
    val expectedVersion: Long,
    val addressLine: String?,
    val directionsHint: String?,
    val operatingHours: StoreOperatingHoursRequest?,
)

internal data class ReplaceMenuDisplayContentRequest(
    val expectedVersion: Long,
    val displayCategory: String?,
    val description: String?,
)

internal data class StoreOperatingDayResponse(
    val dayOfWeek: DayOfWeek,
    val closed: Boolean,
    val opensAt: LocalTime?,
    val closesAt: LocalTime?,
)

internal data class StoreOperatingHoursResponse(
    val timezone: String = SEOUL_TIMEZONE,
    val days: List<StoreOperatingDayResponse>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class StoreCustomerDisplayAuthoringResponse(
    val addressLine: String?,
    val directionsHint: String?,
    val operatingHours: StoreOperatingHoursResponse?,
    val version: Long,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class MenuDisplayContentAuthoringResponse(
    val displayCategory: String?,
    val description: String?,
    val version: Long,
)

@RestController
@RequestMapping("/api/v1/stores/{storeId}")
internal class MerchantDisplayContentController(
    private val service: MerchantDisplayContentService,
) {
    @GetMapping("/customer-display")
    fun profile(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
    ): StoreCustomerDisplayAuthoringResponse = service.profile(actor.actorId, storeId).toResponse()

    @PutMapping("/customer-display")
    fun replaceProfile(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @RequestBody request: ReplaceStoreCustomerDisplayRequest,
    ): StoreCustomerDisplayAuthoringResponse =
        service
            .replaceProfile(
                actor.actorId,
                ReplaceStoreCustomerDisplayCommand(
                    storeId = storeId,
                    expectedVersion = request.expectedVersion,
                    addressLine = request.addressLine,
                    directionsHint = request.directionsHint,
                    timezone = request.operatingHours?.timezone,
                    operatingDays = request.operatingHours?.days?.map(StoreOperatingDayRequest::toCommand),
                ),
            ).toResponse()

    @GetMapping("/menus/{menuId}/display-content")
    fun menu(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable menuId: UUID,
    ): MenuDisplayContentAuthoringResponse = service.menu(actor.actorId, storeId, menuId).toResponse()

    @PutMapping("/menus/{menuId}/display-content")
    fun replaceMenu(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @PathVariable menuId: UUID,
        @RequestBody request: ReplaceMenuDisplayContentRequest,
    ): MenuDisplayContentAuthoringResponse =
        service
            .replaceMenu(
                actor.actorId,
                ReplaceMenuDisplayContentCommand(
                    storeId = storeId,
                    menuId = menuId,
                    expectedVersion = request.expectedVersion,
                    displayCategory = request.displayCategory,
                    description = request.description,
                ),
            ).toResponse()
}

private fun StoreOperatingDayRequest.toCommand() = StoreOperatingDay(dayOfWeek, closed, opensAt, closesAt)

private fun StoreCustomerDisplaySnapshot.toResponse() =
    StoreCustomerDisplayAuthoringResponse(
        addressLine = addressLine,
        directionsHint = directionsHint,
        operatingHours =
            operatingHours?.let { hours ->
                StoreOperatingHoursResponse(
                    days =
                        hours.days.map { day ->
                            StoreOperatingDayResponse(day.dayOfWeek, day.closed, day.opensAt, day.closesAt)
                        },
                )
            },
        version = version,
    )

private fun MenuDisplayContentSnapshot.toResponse() = MenuDisplayContentAuthoringResponse(displayCategory, description, version)

private const val SEOUL_TIMEZONE = "Asia/Seoul"
