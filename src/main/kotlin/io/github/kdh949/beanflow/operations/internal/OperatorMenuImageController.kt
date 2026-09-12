package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.merchant.api.StorefrontImageUpload
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.OperatorActor
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/v1/operations/stores/{storeId}/menus/{menuId}/image")
internal class OperatorMenuImageController(
    private val service: OperatorMenuImageService,
) {
    @GetMapping
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun current(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @PathVariable menuId: UUID,
        @RequestHeader("X-Access-Reason") @Size(min = 1, max = 200) reason: String,
    ): ResponseEntity<OperatorStorefrontImageAuthoringResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            OperatorStorefrontImageAuthoringResponse(
                service.current(actorId(actor), storeId, menuId, reason)?.let(OperatorStorefrontImageResponse::of),
            ),
        )

    @PutMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun replace(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @PathVariable menuId: UUID,
        @RequestHeader("X-Access-Reason") @Size(min = 1, max = 200) reason: String,
        @RequestPart("image") image: MultipartFile,
    ): OperatorStorefrontImageResponse =
        OperatorStorefrontImageResponse.of(
            service.replace(actorId(actor), storeId, menuId, reason, StorefrontImageUpload(image.bytes, image.contentType)),
        )

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('PLATFORM_OPERATOR')")
    fun delete(
        actor: OperatorActor,
        @PathVariable storeId: UUID,
        @PathVariable menuId: UUID,
        @RequestHeader("X-Access-Reason") @Size(min = 1, max = 200) reason: String,
    ) {
        service.delete(actorId(actor), storeId, menuId, reason)
    }

    private fun actorId(actor: OperatorActor): UUID =
        try {
            actor.actorId
        } catch (_: RuntimeException) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated subject is not a valid operator actor ID")
        }
}
