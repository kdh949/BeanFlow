package io.github.kdh949.beanflow.shared.api

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/** Uses the same serializers as ordinary actor-scoped login controllers. */
interface BrowserSessionCookies {
    fun read(
        actor: BrowserActorType,
        request: HttpServletRequest,
    ): String?

    fun issue(
        actor: BrowserActorType,
        request: HttpServletRequest,
        response: HttpServletResponse,
        sessionId: String,
    )

    fun clear(
        actor: BrowserActorType,
        request: HttpServletRequest,
        response: HttpServletResponse,
    )
}
