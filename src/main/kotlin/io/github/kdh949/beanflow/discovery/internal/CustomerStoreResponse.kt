package io.github.kdh949.beanflow.discovery.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.github.kdh949.beanflow.discovery.api.CustomerStoreView
import io.github.kdh949.beanflow.discovery.api.StorefrontImageView
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
internal data class CustomerStoreResponse(
    val storeId: UUID,
    val name: String,
    val pickupAvailable: Boolean,
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
        pickupAvailable = pickupAvailable,
        distanceMeters = distanceMeters,
        image = image,
    )
