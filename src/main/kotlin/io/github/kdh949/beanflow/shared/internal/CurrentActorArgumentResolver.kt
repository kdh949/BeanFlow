package io.github.kdh949.beanflow.shared.internal

import io.github.kdh949.beanflow.shared.api.CurrentActor
import io.github.kdh949.beanflow.shared.api.CustomerActor
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import io.github.kdh949.beanflow.shared.api.MerchantAccountState
import io.github.kdh949.beanflow.shared.api.MerchantActor
import io.github.kdh949.beanflow.shared.api.OperatorActor
import org.springframework.core.MethodParameter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.util.UUID

internal class BrowserSessionAuthenticationToken(
    private val actor: CurrentActor,
) : AbstractAuthenticationToken(
        when (actor) {
            is CustomerActor -> listOf(SimpleGrantedAuthority("ROLE_CUSTOMER"))
            is MerchantActor -> listOf(SimpleGrantedAuthority("ROLE_MERCHANT"))
            is OperatorActor -> emptyList()
        },
    ) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = ""

    override fun getPrincipal(): CurrentActor = actor
}

internal class CurrentActorArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean = CurrentActor::class.java.isAssignableFrom(parameter.parameterType)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): CurrentActor {
        val actor = currentActor(SecurityContextHolder.getContext().authentication)
        if (!parameter.parameterType.isInstance(actor)) {
            throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated actor type does not match this endpoint")
        }
        return actor
    }

    private fun currentActor(authentication: Authentication?): CurrentActor =
        when {
            authentication is BrowserSessionAuthenticationToken -> authentication.principal
            authentication is JwtAuthenticationToken -> authentication.toCurrentActor()
            else -> throw DomainFailure(FailureCode.ACCESS_DENIED, "A supported authenticated actor is required")
        }

    private fun JwtAuthenticationToken.toCurrentActor(): CurrentActor {
        val actorId =
            try {
                UUID.fromString(token.subject)
            } catch (_: IllegalArgumentException) {
                throw DomainFailure(FailureCode.ACCESS_DENIED, "Authenticated operator subject is invalid")
            }
        val roles =
            token.getClaimAsStringList("roles").orEmpty().toSet() +
                authorities.mapNotNull { authority ->
                    authority.authority?.takeIf { it.startsWith("ROLE_") }?.removePrefix("ROLE_")
                }
        return when {
            "CUSTOMER" in roles -> CustomerActor(actorId)
            "STORE_OWNER" in roles || "STORE_STAFF" in roles -> MerchantActor(actorId, MerchantAccountState.ACTIVE)
            else -> OperatorActor(actorId, roles)
        }
    }
}
