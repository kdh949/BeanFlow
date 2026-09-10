package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.OperatorActor
import io.github.kdh949.beanflow.support.internal.domain.CustomerInquiryCategory
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class CreateCustomerInquiryRequest(
    @field:NotBlank @field:Size(max = 100) val title: String,
    val category: CustomerInquiryCategory,
    @field:NotBlank @field:Size(max = 2000) val content: String,
    @field:Size(max = 100) val orderReference: String? = null,
) : StrictSupportRequest

internal data class InquiryMessageRequest(
    @field:PositiveOrZero val expectedVersion: Long,
    @field:NotBlank @field:Size(max = 2000) val content: String,
) : StrictSupportRequest

internal data class SupportInquiryMessageRequest(
    @field:PositiveOrZero val expectedVersion: Long,
    @field:PositiveOrZero val expectedCaseVersion: Long,
    @field:NotBlank @field:Size(max = 2000) val content: String,
) : StrictSupportRequest

internal data class InquiryClaimRequest(
    @field:PositiveOrZero val expectedVersion: Long,
) : StrictSupportRequest

@RestController
@Validated
@RequestMapping("/api/v1/me/support-inquiries")
@PreAuthorize("hasRole('CUSTOMER')")
internal class CustomerInquiryController(
    private val service: CustomerInquiryService,
    private val correlations: CorrelationIdSource,
) {
    @GetMapping
    fun list(
        actor: CustomerActor,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<CustomerInquiryPage> = noStore(service.customerList(actor.actorId, cursor))

    @GetMapping("/{inquiryId}")
    fun get(
        actor: CustomerActor,
        @PathVariable inquiryId: UUID,
        @RequestParam(required = false) messageCursor: String?,
    ): ResponseEntity<CustomerInquiryDetail> = noStore(service.customerDetail(actor.actorId, inquiryId, messageCursor))

    @PostMapping
    fun create(
        actor: CustomerActor,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody body: CreateCustomerInquiryRequest,
    ): ResponseEntity<InquiryCommandResult> =
        noStore(
            service.create(
                actor.actorId,
                key,
                body.title,
                body.category,
                body.content,
                body.orderReference,
                correlations.currentOrCreate(),
            ),
            HttpStatus.CREATED,
        )

    @PostMapping("/{inquiryId}/messages")
    fun message(
        actor: CustomerActor,
        @PathVariable inquiryId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody body: InquiryMessageRequest,
    ): ResponseEntity<InquiryCommandResult> =
        noStore(
            service.customerMessage(actor.actorId, inquiryId, key, body.expectedVersion, body.content, correlations.currentOrCreate()),
            HttpStatus.CREATED,
        )
}

@RestController
@Validated
@RequestMapping("/api/v1/support/inquiries")
@PreAuthorize("isAuthenticated()")
internal class SupportInquiryController(
    private val service: CustomerInquiryService,
    private val correlations: CorrelationIdSource,
) {
    @GetMapping
    fun list(
        actor: OperatorActor,
        @RequestParam(defaultValue = "true") unclaimed: Boolean,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<CustomerInquiryPage> = noStore(service.supportList(actor.actorId, unclaimed, cursor))

    @GetMapping("/{inquiryId}")
    fun get(
        actor: OperatorActor,
        @PathVariable inquiryId: UUID,
        @RequestParam(required = false) messageCursor: String?,
    ): ResponseEntity<SupportInquiryDetail> = noStore(service.supportDetail(actor.actorId, inquiryId, messageCursor))

    @PostMapping("/{inquiryId}/claims")
    fun claim(
        actor: OperatorActor,
        @PathVariable inquiryId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody body: InquiryClaimRequest,
    ): ResponseEntity<InquiryClaimResult> =
        noStore(service.claim(actor.actorId, inquiryId, key, body.expectedVersion, correlations.currentOrCreate()))

    @PostMapping("/{inquiryId}/messages")
    fun message(
        actor: OperatorActor,
        @PathVariable inquiryId: UUID,
        @RequestHeader("Idempotency-Key") @Size(min = 8, max = 128) key: String,
        @Valid @RequestBody body: SupportInquiryMessageRequest,
    ): ResponseEntity<InquiryCommandResult> =
        noStore(
            service.supportMessage(
                actor.actorId,
                inquiryId,
                key,
                body.expectedVersion,
                body.expectedCaseVersion,
                body.content,
                correlations.currentOrCreate(),
            ),
            HttpStatus.CREATED,
        )
}

private fun <T : Any> noStore(
    value: T,
    status: HttpStatus = HttpStatus.OK,
): ResponseEntity<T> = ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(value)
