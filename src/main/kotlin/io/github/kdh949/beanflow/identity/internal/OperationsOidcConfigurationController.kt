package io.github.kdh949.beanflow.identity.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@ConfigurationProperties(prefix = "beanflow.operations-oidc")
internal data class OperationsOidcConfigurationProperties(
    val issuerUri: String,
    val authorizationServerUrl: String,
    val realm: String,
    val clientId: String,
    val redirectUri: String,
    val postLogoutRedirectUri: String,
    val scopes: List<String>,
) {
    init {
        require(realm.isNotBlank()) { "operations OIDC realm must not be blank" }
        require(clientId.isNotBlank()) { "operations OIDC clientId must not be blank" }
        val authorizationServer = validatedUri("authorizationServerUrl", authorizationServerUrl)
        val issuer = validatedUri("issuerUri", issuerUri)
        val redirect = validatedUri("redirectUri", redirectUri)
        val postLogoutRedirect = validatedUri("postLogoutRedirectUri", postLogoutRedirectUri)
        require(authorizationServer.query == null && authorizationServer.fragment == null) {
            "operations OIDC authorizationServerUrl must not contain query or fragment"
        }
        val expectedIssuer = "${authorizationServerUrl.trimEnd('/')}/realms/${realm.trim()}"
        require(issuer.toString() == expectedIssuer) {
            "operations OIDC issuerUri must equal authorizationServerUrl + /realms/{realm}"
        }
        require(redirect.fragment == null && redirect.userInfo == null) {
            "operations OIDC redirectUri must not contain a fragment or user information"
        }
        require(postLogoutRedirect.fragment == null && postLogoutRedirect.userInfo == null) {
            "operations OIDC postLogoutRedirectUri must not contain a fragment or user information"
        }
        require(origin(redirect) == origin(postLogoutRedirect)) {
            "operations OIDC redirectUri and postLogoutRedirectUri must use the same origin"
        }
        require(scopes.isNotEmpty() && scopes.all { it.isNotBlank() } && scopes.distinct().size == scopes.size) {
            "operations OIDC scopes must contain unique non-blank values"
        }
        require("openid" in scopes) { "operations OIDC scopes must include openid" }
        require("offline_access" !in scopes) { "operations OIDC scopes must not include offline_access" }
    }

    private fun validatedUri(name: String, value: String): URI {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("operations OIDC $name is invalid", it) }
        require(uri.isAbsolute && uri.host != null && uri.scheme in setOf("https", "http")) {
            "operations OIDC $name must be an absolute HTTP(S) URI"
        }
        return uri
    }

    private fun origin(uri: URI): Triple<String, String, Int> =
        Triple(uri.scheme.lowercase(), uri.host.lowercase(), uri.port)
}

internal data class OperationsOidcConfigurationResponse(
    val issuerUri: String,
    val authorizationServerUrl: String,
    val realm: String,
    val clientId: String,
    val redirectUri: String,
    val postLogoutRedirectUri: String,
    val scopes: List<String>,
)

@RestController
@RequestMapping("/api/v1/auth/operations/config")
@EnableConfigurationProperties(OperationsOidcConfigurationProperties::class)
internal class OperationsOidcConfigurationController(
    private val properties: OperationsOidcConfigurationProperties,
) {
    @GetMapping
    fun get(): OperationsOidcConfigurationResponse =
        OperationsOidcConfigurationResponse(
            issuerUri = properties.issuerUri,
            authorizationServerUrl = properties.authorizationServerUrl,
            realm = properties.realm,
            clientId = properties.clientId,
            redirectUri = properties.redirectUri,
            postLogoutRedirectUri = properties.postLogoutRedirectUri,
            scopes = properties.scopes.toList(),
        )
}
