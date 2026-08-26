package io.github.kdh949.beanflow.notification.internal

import com.fasterxml.jackson.annotation.JsonAnySetter
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal class MarkNotificationReadRequest(
    val read: Boolean?,
) {
    private var hasUnknownField: Boolean = false

    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: Any?,
    ) {
        hasUnknownField = true
    }

    fun isExactReadCommand(): Boolean = read == true && !hasUnknownField
}

internal class ReplaceNotificationPreferenceRequest(
    val marketingOptIn: Boolean?,
) {
    private var hasUnknownField: Boolean = false

    @JsonAnySetter
    fun rejectUnknown(
        name: String,
        value: Any?,
    ) {
        hasUnknownField = true
    }

    fun exactValue(): Boolean? = marketingOptIn?.takeUnless { hasUnknownField }
}

@Validated
@RestController
@RequestMapping("/api/v1/me")
internal class NotificationInboxController(
    private val service: NotificationInboxService,
) {
    @GetMapping("/notification-summary")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun summary(actor: CustomerActor): NotificationSummaryResponse = service.summary(actor.actorId)

    @GetMapping("/notifications")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun list(
        actor: CustomerActor,
        @RequestParam(required = false) @Size(max = 2048) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ): NotificationPageResponse = service.list(actor.actorId, cursor, limit)

    @PatchMapping("/notifications/{notificationId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun read(
        actor: CustomerActor,
        @PathVariable notificationId: UUID,
        @Valid @RequestBody request: MarkNotificationReadRequest,
    ): ResponseEntity<Void> {
        if (!request.isExactReadCommand()) throw DomainFailure(FailureCode.INVALID_REQUEST, "Only read=true is supported")
        service.read(actor.actorId, notificationId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/notification-preferences")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun preference(actor: CustomerActor): NotificationPreferenceResponse = service.preference(actor.actorId)

    @PutMapping("/notification-preferences")
    @PreAuthorize("hasRole('CUSTOMER')")
    fun replacePreference(
        actor: CustomerActor,
        @Valid @RequestBody request: ReplaceNotificationPreferenceRequest,
    ): NotificationPreferenceResponse {
        val marketingOptIn = request.exactValue() ?: throw DomainFailure(FailureCode.INVALID_REQUEST, "Marketing preference is required")
        return service.replacePreference(actor.actorId, marketingOptIn)
    }
}
