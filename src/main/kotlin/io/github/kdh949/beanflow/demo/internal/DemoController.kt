package io.github.kdh949.beanflow.demo.internal

import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.BrowserSessionCookies
import io.github.kdh949.beanflow.shared.api.CorrelationIdSource
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

internal data class DemoStartRequest(
    val mode: String,
)

internal data class DemoTrackRequest(
    val orderReference: String,
)

internal data class DemoErrorResponse(
    val code: String,
    val message: String,
    val correlationId: String,
)

@RestController
internal class DemoConfigController(
    private val settings: DemoSettings,
    private val environment: Environment,
) {
    @GetMapping("/api/v1/demo/config")
    fun config(response: HttpServletResponse): Map<String, Any> {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        return mapOf(
            "enabled" to settings.enabled,
            "lifetimeSeconds" to settings.lifetimeSeconds,
            "testPaymentEnabled" to (settings.enabled && "toss-sandbox" in environment.activeProfiles),
        )
    }
}

@RestController
@RequestMapping("/api/v1/demo")
@ConditionalOnProperty(name = ["beanflow.demo.enabled"], havingValue = "true")
internal class DemoController(
    private val service: DemoWorkspaceService,
    private val cookies: BrowserSessionCookies,
    private val environment: Environment,
    private val correlationIds: CorrelationIdSource,
) {
    private val random = SecureRandom()

    @ModelAttribute
    fun noCache(response: HttpServletResponse) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
    }

    @GetMapping("/csrf")
    fun csrf(
        token: CsrfToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Map<String, String> {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        if (browserToken(request) == null) {
            val value = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
            response.addHeader(
                HttpHeaders.SET_COOKIE,
                ResponseCookie
                    .from(COOKIE, value)
                    .httpOnly(true)
                    .secure(true)
                    .sameSite("Lax")
                    .path("/")
                    .maxAge(86400)
                    .build()
                    .toString(),
            )
        }
        return mapOf("token" to token.token)
    }

    @GetMapping("/session")
    fun current(request: HttpServletRequest): ResponseEntity<DemoSessionView> =
        service.current(hash(request))?.let { ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(it) }
            ?: ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build()

    @PostMapping("/sessions")
    fun start(
        @RequestBody body: DemoStartRequest,
        @RequestHeader("Idempotency-Key") key: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<DemoSessionView> {
        if (body.mode == "DIRECT" && "toss-sandbox" !in environment.activeProfiles) {
            throw DemoFailure(503, "DEMO_PAYMENT_UNAVAILABLE", "Direct ordering requires Toss TEST")
        }
        val w = service.start(hash(request), key, body.mode, presented(request))
        issue(w, request, response)
        return ResponseEntity.status(201).header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.representation(w))
    }

    @PostMapping("/session/resume")
    fun resume(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): DemoSessionView {
        val w = service.resume(hash(request), presented(request))
        issue(w, request, response)
        return service.representation(w)
    }

    @PostMapping("/session/orders")
    fun sample(
        @RequestHeader("Idempotency-Key") key: String,
        request: HttpServletRequest,
    ): DemoSessionView = service.sample(hash(request), key, presented(request))

    @PostMapping("/session/order")
    fun track(
        @RequestBody body: DemoTrackRequest,
        @RequestHeader("Idempotency-Key") key: String,
        request: HttpServletRequest,
    ): DemoSessionView = service.track(hash(request), key, body.orderReference, presented(request))

    @DeleteMapping("/session")
    fun end(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<Void> {
        service.end(hash(request), presented(request))?.let {
            cookies.clear(BrowserActorType.CUSTOMER, request, response)
            cookies.clear(BrowserActorType.MERCHANT, request, response)
        }
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build()
    }

    @ExceptionHandler(DemoFailure::class)
    fun failure(error: DemoFailure): ResponseEntity<DemoErrorResponse> =
        ResponseEntity
            .status(error.status)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(
                DemoErrorResponse(
                    code = error.code,
                    message = error.message ?: "Demo request failed",
                    correlationId = correlationIds.currentOrCreate(),
                ),
            )

    private fun issue(
        w: DemoWorkspace,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        cookies.issue(BrowserActorType.CUSTOMER, request, response, w.customerSessionId)
        cookies.issue(BrowserActorType.MERCHANT, request, response, w.merchantSessionId)
    }

    private fun presented(request: HttpServletRequest) =
        PresentedDemoSessions(cookies.read(BrowserActorType.CUSTOMER, request), cookies.read(BrowserActorType.MERCHANT, request))

    private fun browserToken(request: HttpServletRequest): String? =
        request.cookies
            ?.firstOrNull { it.name == COOKIE }
            ?.value
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{43}")) }

    private fun hash(request: HttpServletRequest): String {
        val token = browserToken(request) ?: throw DemoFailure(401, "DEMO_SESSION_NOT_FOUND", "Initialize the demo browser first")
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        private const val COOKIE = "BEANFLOW_DEMO"
    }
}
