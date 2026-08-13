package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.session.web.http.HttpSessionIdResolver
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class CustomerRegistrationRequest(
    val loginId: String,
    val password: String,
    val displayName: String,
)

internal data class CustomerRegistrationResponse(
    val loginId: String,
)

internal data class CustomerLoginRequest(
    val loginId: String,
    val password: String,
)

internal data class CustomerActorResponse(
    val actorType: String = "CUSTOMER",
    val customerId: UUID,
    val displayName: String,
)

@RestController
@RequestMapping("/api/v1")
internal class CustomerAccountController(
    private val service: CustomerAccountApplicationService,
    private val sessionIds: HttpSessionIdResolver,
    private val sourceIps: CustomerSourceIpResolver,
) {
    @PostMapping("/auth/customer/registrations")
    fun register(
        @RequestBody request: CustomerRegistrationRequest,
    ): ResponseEntity<CustomerRegistrationResponse> {
        val loginId = service.register(CustomerRegistration(request.loginId, request.password, request.displayName))
        return ResponseEntity.status(HttpStatus.CREATED).body(CustomerRegistrationResponse(loginId))
    }

    @PostMapping("/auth/customer/sessions")
    fun login(
        @RequestBody body: CustomerLoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): CustomerActorResponse {
        val result =
            service.login(
                rawLoginId = body.loginId,
                password = body.password,
                sourceIp = sourceIps.resolve(request.remoteAddr, request.getHeader("X-Forwarded-For")),
                currentSessionId = sessionIds.resolveSessionIds(request).firstOrNull(),
            )
        sessionIds.setSessionId(request, response, result.session.sessionId)
        return result.toResponse()
    }

    @GetMapping("/me")
    fun me(actor: CustomerActor): CustomerActorResponse {
        val customer = service.me(actor.actorId)
        return CustomerActorResponse(customerId = customer.customerId, displayName = customer.displayName)
    }

    @DeleteMapping("/auth/customer/sessions/current")
    fun logout(
        actor: CustomerActor,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        actor.actorId
        val sessionId =
            sessionIds.resolveSessionIds(request).firstOrNull()
                ?: throw DomainFailure(FailureCode.AUTHENTICATION_FAILED, "Authentication failed")
        service.logout(sessionId)
        sessionIds.expireSession(request, response)
        return ResponseEntity.noContent().build()
    }

    private fun CustomerLoginResult.toResponse() = CustomerActorResponse(customerId = customerId, displayName = displayName)
}
