package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageAccess
import io.github.kdh949.beanflow.merchant.api.StorefrontImageUpload
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

internal data class OperatorStorefrontImageResponse(
    val url: String,
    val expiresAt: java.time.Instant,
) {
    companion object {
        fun of(access: StorefrontImageAccess) = OperatorStorefrontImageResponse(access.url, access.expiresAt)
    }
}

@Validated
@RestController
@RequestMapping("/api/v1/operations/stores/{storeId}/image")
internal class OperatorStoreImageController(
    private val service: OperatorStoreImageService,
) {
    @PutMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun replace(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @RequestHeader("X-Access-Reason") @Size(min = 1, max = 200) reason: String,
        @RequestPart("image") image: MultipartFile,
    ): OperatorStorefrontImageResponse =
        OperatorStorefrontImageResponse.of(
            service.replace(actorId(actor), storeId, reason, StorefrontImageUpload(image.bytes, image.contentType)),
        )

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun delete(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @RequestHeader("X-Access-Reason") @Size(min = 1, max = 200) reason: String,
    ) {
        service.delete(actorId(actor), storeId, reason)
    }

    private fun actorId(actor: OperatorActor): UUID =
        try {
            actor.actorId
        } catch (_: RuntimeException) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated subject is not a valid operator actor ID")
        }
}
