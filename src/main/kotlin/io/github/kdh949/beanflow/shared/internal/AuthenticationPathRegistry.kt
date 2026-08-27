package io.github.kdh949.beanflow.shared.internal

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.server.PathContainer
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.stereotype.Component
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

internal enum class AuthenticationChain {
    PUBLIC,
    OPERATIONS,
    MERCHANT,
    CUSTOMER,
}

@Component
internal class AuthenticationPathRegistry {
    private val parser = PathPatternParser.defaultInstance
    private val registrations =
        listOf(
            registration(AuthenticationChain.PUBLIC, "/actuator/health"),
            registration(AuthenticationChain.PUBLIC, "/api/v1/payment-config"),
            registration(AuthenticationChain.PUBLIC, "/api/v1/auth/operations/config"),
            // Scalar API 문서 페이지와 그 문서가 fetch하는 OpenAPI 스펙. 로그인 없이 열람 가능해야
            // 외부 파트너(POS 연동사 등)가 별도 인증 없이 연동 문서를 먼저 확인할 수 있다.
            registration(AuthenticationChain.PUBLIC, "/docs"),
            registration(AuthenticationChain.PUBLIC, "/docs/**"),
            registration(AuthenticationChain.OPERATIONS, "/api/v1/operations/**"),
            registration(AuthenticationChain.OPERATIONS, "/api/v1/support/**"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/auth/merchant/**"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/merchant/**"),
            // 법정동 어휘와 매장 지역 지정은 둘 다 매장주 화면의 것이다. 어휘 자체는 공개
            // 참조 데이터지만 그것을 고르는 사람이 매장주이므로 merchant chain에 둔다.
            registration(AuthenticationChain.MERCHANT, "/api/v1/regions"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/region"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/image"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/customer-display"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/ordering-policy"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/menu-catalog"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/menus", setOf("POST")),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/menus/{menuId}/trade-content"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/menus/{menuId}/archive"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/menus/{menuId}/image"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/menus/{menuId}/display-content"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/orders/**"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/settlements/**"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/disputes"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/store-orders/**"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/stores/{storeId}/support-order-change-authorizations/**"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/settlement-items/{itemId}/disputes"),
            registration(AuthenticationChain.MERCHANT, "/api/v1/payments/{paymentId}/refunds"),
            registration(AuthenticationChain.CUSTOMER, "/api/v1/auth/customer/**"),
            registration(AuthenticationChain.CUSTOMER, "/api/v1/me/**"),
            registration(AuthenticationChain.CUSTOMER, "/api/v1/orders/**"),
            registration(AuthenticationChain.CUSTOMER, "/api/v1/payment-methods/**"),
            registration(AuthenticationChain.CUSTOMER, "/api/v1/payments/{paymentId}"),
            registration(AuthenticationChain.CUSTOMER, "/api/v1/payments/{paymentId}/confirmations"),
            registration(AuthenticationChain.CUSTOMER, "/api/v1/point-accounts/**"),
            registration(AuthenticationChain.CUSTOMER, "/api/v1/stores/nearby"),
            registration(AuthenticationChain.CUSTOMER, "/api/v1/stores/search"),
            registration(AuthenticationChain.CUSTOMER, "/api/v1/stores/{storeId}"),
            registration(AuthenticationChain.CUSTOMER, "/api/v1/stores/{storeId}/menus", setOf("GET", "HEAD")),
            registration(AuthenticationChain.CUSTOMER, "/api/v1/stores/{storeId}/pickup-slots"),
        )

    fun classify(
        path: String,
        method: String? = null,
    ): AuthenticationChain? {
        val matches = matchingRegistrations(path, method).map { it.chain }.distinct()
        check(matches.size <= 1) { "Authentication path '$path' belongs to multiple chains: $matches" }
        return matches.singleOrNull()
    }

    fun requestMatcher(chain: AuthenticationChain): RequestMatcher =
        RequestMatcher { request -> classify(request.applicationPath(), request.method) == chain }

    fun overlappingPatterns(): List<Pair<String, String>> =
        registrations.indices.flatMap { leftIndex ->
            ((leftIndex + 1) until registrations.size).mapNotNull { rightIndex ->
                val left = registrations[leftIndex]
                val right = registrations[rightIndex]
                if (left.chain == right.chain || !left.methodsOverlap(right)) return@mapNotNull null
                val overlaps =
                    listOf(left.patternText.canonicalSample(), right.patternText.canonicalSample())
                        .any { sample -> left.matches(sample, null) && right.matches(sample, null) }
                if (overlaps) left.patternText to right.patternText else null
            }
        }

    internal fun hasRegistration(
        path: String,
        method: String? = null,
    ): Boolean = matchingRegistrations(path, method).map(Registration::chain).distinct().size == 1

    private fun matchingRegistrations(
        path: String,
        method: String?,
    ): List<Registration> = registrations.filter { it.matches(path, method) }

    private fun registration(
        chain: AuthenticationChain,
        pattern: String,
        methods: Set<String> = emptySet(),
    ) = Registration(chain, pattern, parser.parse(pattern), methods)

    private fun HttpServletRequest.applicationPath(): String = requestURI.removePrefix(contextPath).ifEmpty { "/" }

    private fun String.canonicalSample(): String =
        replace(Regex("\\{[^}]+}"), "sample")
            .replace("**", "sample")
            .replace("*", "sample")

    private data class Registration(
        val chain: AuthenticationChain,
        val patternText: String,
        val pattern: PathPattern,
        val methods: Set<String>,
    ) {
        fun matches(
            path: String,
            method: String?,
        ): Boolean =
            pattern.matches(PathContainer.parsePath(path)) &&
                (method == null || methods.isEmpty() || method in methods)

        fun methodsOverlap(other: Registration): Boolean =
            methods.isEmpty() || other.methods.isEmpty() || methods.any(other.methods::contains)
    }
}
