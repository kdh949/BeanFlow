package io.github.kdh949.beanflow.payment.internal

import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

@Configuration(proxyBeanMethods = false)
@Profile("toss-sandbox & !prod")
internal class TossOneTimePaymentGatewayConfiguration {
    @Bean
    fun tossOneTimePaymentGateway(
        objectMapper: ObjectMapper,
        @Value("\${beanflow.toss.client-key}") clientKey: String,
        @Value("\${beanflow.toss.secret-key}") secretKey: String,
        @Value("\${beanflow.toss.base-url:https://api.tosspayments.com}") baseUrl: String,
    ): PaymentGateway {
        require(clientKey.startsWith("test_ck_")) {
            "toss-sandbox requires a Toss API individual integration test client key (test_ck_)"
        }
        require(secretKey.startsWith("test_sk_")) {
            "toss-sandbox requires a Toss API individual integration test secret key (test_sk_)"
        }
        require(URI(baseUrl).let { it.scheme == "https" && it.host == "api.tosspayments.com" }) {
            "toss-sandbox requires the official HTTPS Toss API endpoint"
        }
        return TossOneTimePaymentGateway(
            restClient =
                RestClient
                    .builder()
                    .baseUrl(baseUrl)
                    .requestFactory(
                        JdkClientHttpRequestFactory(
                            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                        ).apply { setReadTimeout(Duration.ofSeconds(8)) },
                    ).defaultHeader(HttpHeaders.AUTHORIZATION, basicAuthorization(secretKey))
                    .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .build(),
            objectMapper = objectMapper,
        )
    }
}

internal class TossOneTimePaymentGateway(
    private val restClient: RestClient,
    private val objectMapper: ObjectMapper,
) : PaymentGateway {
    override fun approve(request: GatewayApprovalRequest): ProviderPaymentResult =
        ProviderPaymentResult.Unknown("UNSUPPORTED_LEGACY_PAYMENT_METHOD_APPROVAL")

    override fun confirmOneTime(request: GatewayOneTimeConfirmationRequest): ProviderPaymentResult =
        try {
            val body =
                restClient
                    .post()
                    .uri("/v1/payments/confirm")
                    .header(IDEMPOTENCY_HEADER, request.providerIdempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        mapOf(
                            "paymentKey" to request.paymentKey,
                            "orderId" to request.providerOrderId,
                            "amount" to request.amountKrw,
                        ),
                    ).retrieve()
                    .body(String::class.java)
                    ?: return ProviderPaymentResult.Unknown("TOSS_EMPTY_CONFIRM_RESPONSE")
            paymentResult(parse(body), request.paymentKey, request.providerOrderId)
        } catch (failure: RestClientResponseException) {
            confirmationFailure(failure)
        } catch (_: ResourceAccessException) {
            ProviderPaymentResult.Unknown("TOSS_CONFIRM_TRANSPORT_UNKNOWN")
        } catch (_: RestClientException) {
            ProviderPaymentResult.Unknown("TOSS_CONFIRM_CLIENT_UNKNOWN")
        } catch (_: RuntimeException) {
            ProviderPaymentResult.Unknown("TOSS_CONFIRM_RESPONSE_UNKNOWN")
        }

    override fun lookup(request: GatewayLookupRequest): ProviderPaymentResult =
        try {
            val body =
                if (!request.providerTransactionReference.isNullOrBlank()) {
                    restClient.get().uri("/v1/payments/{paymentKey}", request.providerTransactionReference)
                } else {
                    val orderId =
                        request.providerOrderId
                            ?: return ProviderPaymentResult.Unknown("TOSS_LOOKUP_BINDING_MISSING")
                    restClient.get().uri("/v1/payments/orders/{orderId}", orderId)
                }.retrieve().body(String::class.java)
                    ?: return ProviderPaymentResult.Unknown("TOSS_EMPTY_LOOKUP_RESPONSE")
            paymentResult(parse(body), request.providerTransactionReference, request.providerOrderId)
        } catch (failure: RestClientResponseException) {
            ProviderPaymentResult.Unknown("TOSS_LOOKUP_${safeCode(failure)}")
        } catch (_: ResourceAccessException) {
            ProviderPaymentResult.Unknown("TOSS_LOOKUP_TRANSPORT_UNKNOWN")
        } catch (_: RestClientException) {
            ProviderPaymentResult.Unknown("TOSS_LOOKUP_CLIENT_UNKNOWN")
        } catch (_: RuntimeException) {
            ProviderPaymentResult.Unknown("TOSS_LOOKUP_RESPONSE_UNKNOWN")
        }

    override fun void(
        request: GatewayLookupRequest,
        providerIdempotencyKey: String,
    ): GatewayRecoveryResult =
        cancel(request, null, providerIdempotencyKey).let { result ->
            when (result) {
                is GatewayRefundResult.Succeeded -> GatewayRecoveryResult.Succeeded
                is GatewayRefundResult.Failed -> GatewayRecoveryResult.Unavailable
                is GatewayRefundResult.RetryableFailed -> GatewayRecoveryResult.Unknown(result.code)
                is GatewayRefundResult.Unknown -> GatewayRecoveryResult.Unknown(result.code)
            }
        }

    override fun refund(
        request: GatewayLookupRequest,
        amountKrw: Long,
        providerIdempotencyKey: String,
    ): GatewayRecoveryResult =
        cancel(request, amountKrw, providerIdempotencyKey).let { result ->
            when (result) {
                is GatewayRefundResult.Succeeded -> GatewayRecoveryResult.Succeeded
                is GatewayRefundResult.Failed -> GatewayRecoveryResult.Unavailable
                is GatewayRefundResult.RetryableFailed -> GatewayRecoveryResult.Unknown(result.code)
                is GatewayRefundResult.Unknown -> GatewayRecoveryResult.Unknown(result.code)
            }
        }

    override fun requestRefund(
        request: GatewayLookupRequest,
        amountKrw: Long,
        providerIdempotencyKey: String,
    ): GatewayRefundResult = cancel(request, amountKrw, providerIdempotencyKey)

    override fun lookupRefund(
        request: GatewayLookupRequest,
        amountKrw: Long,
        providerIdempotencyKey: String,
    ): GatewayRefundResult =
        try {
            val paymentKey =
                request.providerTransactionReference
                    ?: return GatewayRefundResult.Unknown("TOSS_REFUND_LOOKUP_PAYMENT_KEY_MISSING")
            val body =
                restClient
                    .get()
                    .uri("/v1/payments/{paymentKey}", paymentKey)
                    .retrieve()
                    .body(String::class.java)
                    ?: return GatewayRefundResult.Unknown("TOSS_EMPTY_REFUND_LOOKUP_RESPONSE")
            val matches =
                parse(body)
                    .path("cancels")
                    .filter { cancel ->
                        cancel.path("cancelStatus").asText() == "DONE" &&
                            cancel.path("cancelAmount").asLong(-1) == amountKrw &&
                            cancel.path("cancelReason").asText("") == refundReason(providerIdempotencyKey)
                    }
            if (matches.size != 1) {
                GatewayRefundResult.Unknown("TOSS_REFUND_LOOKUP_AMBIGUOUS")
            } else {
                val reference = matches.single().path("transactionKey").asText("")
                if (reference.isBlank()) {
                    GatewayRefundResult.Unknown("TOSS_REFUND_REFERENCE_MISSING")
                } else {
                    GatewayRefundResult.Succeeded(reference)
                }
            }
        } catch (failure: RestClientResponseException) {
            GatewayRefundResult.Unknown("TOSS_REFUND_LOOKUP_${safeCode(failure)}")
        } catch (_: ResourceAccessException) {
            GatewayRefundResult.Unknown("TOSS_REFUND_LOOKUP_TRANSPORT_UNKNOWN")
        } catch (_: RestClientException) {
            GatewayRefundResult.Unknown("TOSS_REFUND_LOOKUP_CLIENT_UNKNOWN")
        } catch (_: RuntimeException) {
            GatewayRefundResult.Unknown("TOSS_REFUND_LOOKUP_RESPONSE_UNKNOWN")
        }

    private fun cancel(
        request: GatewayLookupRequest,
        amountKrw: Long?,
        providerIdempotencyKey: String,
    ): GatewayRefundResult =
        try {
            val paymentKey =
                request.providerTransactionReference
                    ?: return GatewayRefundResult.Unknown("TOSS_CANCEL_PAYMENT_KEY_MISSING")
            val reason = refundReason(providerIdempotencyKey)
            val payload = linkedMapOf<String, Any>("cancelReason" to reason)
            amountKrw?.let { payload["cancelAmount"] = it }
            val body =
                restClient
                    .post()
                    .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                    .header(IDEMPOTENCY_HEADER, providerIdempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(String::class.java)
                    ?: return GatewayRefundResult.Unknown("TOSS_EMPTY_CANCEL_RESPONSE")
            cancelResult(parse(body), amountKrw, reason)
        } catch (failure: RestClientResponseException) {
            GatewayRefundResult.Unknown("TOSS_CANCEL_${safeCode(failure)}")
        } catch (_: ResourceAccessException) {
            GatewayRefundResult.Unknown("TOSS_CANCEL_TRANSPORT_UNKNOWN")
        } catch (_: RestClientException) {
            GatewayRefundResult.Unknown("TOSS_CANCEL_CLIENT_UNKNOWN")
        } catch (_: RuntimeException) {
            GatewayRefundResult.Unknown("TOSS_CANCEL_RESPONSE_UNKNOWN")
        }

    private fun paymentResult(
        payment: JsonNode,
        expectedPaymentKey: String?,
        expectedOrderId: String?,
    ): ProviderPaymentResult {
        val paymentKey = payment.path("paymentKey").asText("")
        val orderId = payment.path("orderId").asText("")
        if (
            paymentKey.isBlank() || orderId.isBlank() ||
            expectedPaymentKey?.let { it != paymentKey } == true ||
            expectedOrderId?.let { it != orderId } == true
        ) {
            return ProviderPaymentResult.Unknown("TOSS_PAYMENT_BINDING_MISMATCH")
        }
        val status = payment.path("status").asText("")
        return when (status) {
            "DONE" -> {
                ProviderPaymentResult.Approved(
                    providerTransactionReference = paymentKey,
                    amountKrw = payment.path("totalAmount").asLong(-1),
                    currency = payment.path("currency").asText(""),
                )
            }

            "ABORTED", "EXPIRED", "CANCELED", "PARTIAL_CANCELED" -> {
                ProviderPaymentResult.Declined(
                    payment
                        .path("failure")
                        .path("code")
                        .asText("")
                        .ifBlank { "TOSS_$status" },
                )
            }

            else -> {
                ProviderPaymentResult.Unknown("TOSS_STATUS_${status.ifBlank { "MISSING" }}")
            }
        }
    }

    private fun cancelResult(
        payment: JsonNode,
        requestedAmountKrw: Long?,
        expectedReason: String,
    ): GatewayRefundResult {
        val matches =
            payment.path("cancels").filter { cancel ->
                cancel.path("cancelStatus").asText() == "DONE" &&
                    cancel.path("cancelReason").asText("") == expectedReason &&
                    (requestedAmountKrw == null || cancel.path("cancelAmount").asLong(-1) == requestedAmountKrw)
            }
        if (matches.size != 1) {
            return GatewayRefundResult.Unknown("TOSS_CANCEL_RESULT_AMBIGUOUS")
        }
        val reference = matches.single().path("transactionKey").asText("")
        return if (reference.isBlank()) {
            GatewayRefundResult.Unknown("TOSS_CANCEL_REFERENCE_MISSING")
        } else {
            GatewayRefundResult.Succeeded(reference)
        }
    }

    private fun refundReason(providerIdempotencyKey: String): String = "BeanFlow refund [bf:${sha256(providerIdempotencyKey).take(24)}]"

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun confirmationFailure(failure: RestClientResponseException): ProviderPaymentResult {
        val code = safeCode(failure)
        return if (code in DEFINITIVE_DECLINE_CODES || code.startsWith("REJECT_")) {
            ProviderPaymentResult.Declined(code)
        } else {
            ProviderPaymentResult.Unknown("TOSS_CONFIRM_$code")
        }
    }

    private fun safeCode(failure: RestClientResponseException): String =
        try {
            parse(failure.responseBodyAsString).path("code").asText("").takeIf(CODE_PATTERN::matches)
                ?: "HTTP_${failure.statusCode.value()}"
        } catch (_: RuntimeException) {
            "HTTP_${failure.statusCode.value()}"
        }

    private fun parse(body: String): JsonNode = objectMapper.readTree(body)

    private companion object {
        const val IDEMPOTENCY_HEADER = "Idempotency-Key"
        val CODE_PATTERN = Regex("[A-Z0-9_]{1,100}")
        val DEFINITIVE_DECLINE_CODES =
            setOf(
                "INVALID_REJECT_CARD",
                "NOT_ALLOWED_POINT_USE",
                "EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT",
                "EXCEED_MAX_CARD_INSTALLMENT_PLAN",
            )
    }
}

private fun basicAuthorization(secretKey: String): String {
    val encoded =
        Base64.getEncoder().encodeToString(
            "$secretKey:".toByteArray(StandardCharsets.UTF_8),
        )
    return "Basic $encoded"
}
