package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageAccess
import io.github.kdh949.beanflow.merchant.api.StorefrontImageUpload
import io.github.kdh949.beanflow.shared.api.MerchantActor
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.Instant
import java.util.UUID

internal data class StorefrontImageResponse(
    val url: String,
    val expiresAt: Instant,
) {
    companion object {
        fun of(access: StorefrontImageAccess) = StorefrontImageResponse(access.url, access.expiresAt)
    }
}

@RestController
@RequestMapping("/api/v1/stores/{storeId}/image")
internal class MerchantStoreImageController(
    private val service: MerchantStoreImageService,
) {
    @PutMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun replace(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
        @RequestPart("image") image: MultipartFile,
    ): StorefrontImageResponse =
        StorefrontImageResponse.of(
            service.replace(actor.actorId, storeId, StorefrontImageUpload(image.bytes, image.contentType)),
        )

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        actor: MerchantActor,
        @PathVariable storeId: UUID,
    ) {
        service.delete(actor.actorId, storeId)
    }
}
