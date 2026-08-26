package io.github.kdh949.beanflow.discovery.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.discovery.api.CustomerStoreDisplayView
import io.github.kdh949.beanflow.discovery.api.CustomerStoreView
import io.github.kdh949.beanflow.discovery.api.NextPickupWindowView
import io.github.kdh949.beanflow.discovery.api.StorefrontImageView
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class CustomerStoreResponse(
    val storeId: UUID,
    val name: String,
    val orderingAvailable: Boolean,
    val pickupAvailable: Boolean,
    val nextPickupWindow: NextPickupWindowView?,
    val customerDisplay: CustomerStoreDisplayView,
    val distanceMeters: Long?,
    val image: StorefrontImageView?,
)

internal data class CustomerStoreListResponse(
    val items: List<CustomerStoreResponse>,
)

internal fun CustomerStoreView.toResponse() =
    CustomerStoreResponse(
        storeId = storeId,
        name = name,
        orderingAvailable = orderingAvailable,
        pickupAvailable = pickupAvailable,
        nextPickupWindow = nextPickupWindow,
        customerDisplay = customerDisplay,
        distanceMeters = distanceMeters,
        image = image,
    )
