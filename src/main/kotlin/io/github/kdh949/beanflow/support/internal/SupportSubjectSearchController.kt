package io.github.kdh949.beanflow.support.internal

import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.ExactSearchCriterionType
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class SupportSearchCriterionRequest(
    @field:NotNull
    val type: ExactSearchCriterionType?,
    @field:NotBlank @field:Size(max = 320)
    val value: String,
) : StrictSupportRequest {
    override fun toString(): String = "SupportSearchCriterionRequest(type=$type, value=<redacted>)"
}

internal data class SearchSupportSubjectsRequest(
    @field:Valid @field:NotNull
    val criterion: SupportSearchCriterionRequest?,
    @field:Size(min = 1, max = 3)
    val subjectTypes: List<SupportSearchSubjectType> = emptyList(),
    @field:NotNull
    val reasonCode: SupportSearchReasonCode?,
) : StrictSupportRequest {
    override fun toString(): String =
        "SearchSupportSubjectsRequest(criterionType=${criterion?.type}, subjectTypes=$subjectTypes, " +
            "reasonCode=$reasonCode, value=<redacted>)"
}

@Validated
@RestController
@RequestMapping("/api/v1/support/searches")
internal class SupportSubjectSearchController(
    private val service: SupportSubjectSearchApplicationService,
    private val correlationIds: CorrelationIdSource,
) {
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    fun search(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: SearchSupportSubjectsRequest,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<SupportSubjectSearchResult> {
        if (servletRequest.parameterMap.isNotEmpty()) invalid()
        val criterion = request.criterion ?: invalid()
        val response =
            service.search(
                SearchSupportSubjectsCommand(
                    actorId = jwt.actorId(),
                    criterionType = criterion.type ?: invalid(),
                    rawCriterion = criterion.value,
                    subjectTypes = request.subjectTypes,
                    reasonCode = request.reasonCode ?: invalid(),
                    correlationId = correlationIds.currentOrCreate(),
                ),
            )
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response)
    }

    private fun Jwt.actorId(): UUID =
        try {
            UUID.fromString(subject)
        } catch (_: IllegalArgumentException) {
            invalid()
        }

    private fun invalid(): Nothing = throw DomainFailure(FailureCode.INVALID_REQUEST, "Support search request is invalid")
}
