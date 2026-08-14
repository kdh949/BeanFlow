package io.github.kdh949.beanflow.identity.internal

import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import io.github.kdh949.beanflow.shared.api.MerchantActor
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.session.web.http.HttpSessionIdResolver
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

internal data class MerchantLoginRequest(
    val loginId: String,
    val password: String,
)

internal data class MerchantPasswordChangeRequest(
    val currentPassword: String,
    val newPassword: String,
)

internal data class MerchantActorResponse(
    val actorType: String = "MERCHANT",
    val merchantId: UUID,
    val displayName: String,
    val accountState: MerchantAccountState,
)

@RestController
@RequestMapping("/api/v1")
internal class MerchantAccountController(
    private val service: MerchantAccountApplicationService,
    private val sessionIds: HttpSessionIdResolver,
    private val sourceIps: CustomerSourceIpResolver,
) {
    @PostMapping("/auth/merchant/sessions")
    fun login(
        @RequestBody body: MerchantLoginRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): MerchantActorResponse {
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

    @PostMapping("/auth/merchant/password-changes")
    fun changePassword(
        actor: MerchantActor,
        @RequestBody body: MerchantPasswordChangeRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        val currentSessionId =
            sessionIds.resolveSessionIds(request).firstOrNull()
                ?: throw DomainFailure(FailureCode.AUTHENTICATION_FAILED, "Authentication failed")
        val result =
            service.changePassword(actor.actorId, body.currentPassword, body.newPassword, currentSessionId)
        sessionIds.setSessionId(request, response, result.session.sessionId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/merchant/me")
    fun me(actor: MerchantActor): MerchantActorResponse {
        val merchant = service.me(actor.actorId)
        return MerchantActorResponse(
            merchantId = merchant.merchantId,
            displayName = merchant.displayName,
            accountState = merchant.accountState,
        )
    }

    @GetMapping("/merchant/me/stores")
    fun stores(actor: MerchantActor): List<MerchantStoreView> = service.stores(actor.actorId)

    @DeleteMapping("/auth/merchant/sessions/current")
    fun logout(
        actor: MerchantActor,
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

    private fun MerchantLoginResult.toResponse() =
        MerchantActorResponse(
            merchantId = merchantId,
            displayName = displayName,
            accountState = accountState,
        )
}
