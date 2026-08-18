package io.github.kdh949.beanflow.promotion.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.promotion.api.CouponDiscountType
import io.github.kdh949.beanflow.promotion.api.CouponWalletInapplicableReason
import io.github.kdh949.beanflow.promotion.api.CustomerCouponWalletBenefit
import io.github.kdh949.beanflow.promotion.api.CustomerCouponWalletItem
import io.github.kdh949.beanflow.promotion.api.CustomerCouponWalletPage
import io.github.kdh949.beanflow.promotion.api.CustomerCouponWalletQueryOperations
import io.github.kdh949.beanflow.promotion.api.ListCustomerCouponWalletCommand
import io.github.kdh949.beanflow.shared.api.CustomerActor
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class CustomerCouponWalletBenefitResponse(
    val discountType: CouponDiscountType,
    val fixedAmountKrw: Long?,
    val rateBps: Int?,
    val maximumDiscountKrw: Long?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class CustomerCouponWalletItemResponse(
    val couponIssuanceId: UUID,
    val benefit: CustomerCouponWalletBenefitResponse,
    val minimumOrderKrw: Long,
    val couponExpiresAt: Instant,
    val applicable: Boolean,
    val reasonCode: CouponWalletInapplicableReason?,
)

internal data class CustomerCouponWalletPageInfoResponse(
    val nextCursor: String?,
)

internal data class CustomerCouponWalletPageResponse(
    val items: List<CustomerCouponWalletItemResponse>,
    val page: CustomerCouponWalletPageInfoResponse,
)

@RestController
@RequestMapping("/api/v1/me/coupons")
internal class CustomerCouponWalletQueryController(
    private val queries: CustomerCouponWalletQueryOperations,
    private val clock: Clock,
) {
    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    fun list(
        actor: CustomerActor,
        @RequestParam(required = false) storeId: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: String?,
    ): CustomerCouponWalletPageResponse =
        queries
            .list(
                ListCustomerCouponWalletCommand(
                    customerId = actor.actorId,
                    storeId = storeId,
                    cursor = cursor,
                    limit = limit,
                    now = clock.instant(),
                ),
            ).toResponse()

    private fun CustomerCouponWalletPage.toResponse() =
        CustomerCouponWalletPageResponse(
            items = items.map { item -> item.toItemResponse() },
            page = CustomerCouponWalletPageInfoResponse(nextCursor),
        )

    private fun CustomerCouponWalletItem.toItemResponse() =
        CustomerCouponWalletItemResponse(
            couponIssuanceId = couponIssuanceId,
            benefit = benefit.toResponse(),
            minimumOrderKrw = minimumOrderKrw,
            couponExpiresAt = couponExpiresAt,
            applicable = applicable,
            reasonCode = reasonCode,
        )

    private fun CustomerCouponWalletBenefit.toResponse() =
        CustomerCouponWalletBenefitResponse(discountType, fixedAmountKrw, rateBps, maximumDiscountKrw)
}
