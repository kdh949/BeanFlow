package io.github.kdh949.beanflow.payment.internal

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kdh949.beanflow.payment.api.ProviderPaymentResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

internal class TossOneTimePaymentGatewayTest {
    private lateinit var server: HttpServer
    private lateinit var gateway: TossOneTimePaymentGateway
    private val responses = ConcurrentLinkedQueue<StubResponse>()
    private val requests = ConcurrentLinkedQueue<CapturedRequest>()

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/", ::handle)
        server.start()
        val authorization =
            "Basic " +
                Base64.getEncoder().encodeToString("test_sk_unit:".toByteArray(StandardCharsets.UTF_8))
        gateway =
            TossOneTimePaymentGateway(
                RestClient
                    .builder()
                    .baseUrl("http://127.0.0.1:${server.address.port}")
                    .defaultHeader(HttpHeaders.AUTHORIZATION, authorization)
                    .build(),
                ObjectMapper(),
            )
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `confirmation uses colon suffixed Basic auth and stable idempotency and verifies bindings`() {
        responses +=
            StubResponse(
                200,
                """{"paymentKey":"pay-key","orderId":"bf_order_123","status":"DONE","totalAmount":1000,"currency":"KRW"}""",
            )

        val result = gateway.confirmOneTime(confirmationRequest())

        assertThat(result).isEqualTo(ProviderPaymentResult.Approved("pay-key", 1_000, "KRW"))
        val captured = requests.single()
        assertThat(captured.method).isEqualTo("POST")
        assertThat(captured.path).isEqualTo("/v1/payments/confirm")
        assertThat(captured.authorization).isEqualTo(
            "Basic " + Base64.getEncoder().encodeToString("test_sk_unit:".toByteArray(StandardCharsets.UTF_8)),
        )
        assertThat(captured.idempotencyKey).isEqualTo("provider-idempotency-key")
        val body = ObjectMapper().readTree(captured.body)
        assertThat(body.path("paymentKey").asText()).isEqualTo("pay-key")
        assertThat(body.path("orderId").asText()).isEqualTo("bf_order_123")
        assertThat(body.path("amount").asLong()).isEqualTo(1_000)
    }

    @Test
    fun `only allowlisted Provider rejections become terminal declines`() {
        responses += StubResponse(400, """{"code":"INVALID_REJECT_CARD","message":"declined"}""")
        responses += StubResponse(500, """{"code":"INTERNAL_SERVER_ERROR","message":"unknown"}""")

        val declined = gateway.confirmOneTime(confirmationRequest())
        val unknown = gateway.confirmOneTime(confirmationRequest())

        assertThat(declined).isEqualTo(ProviderPaymentResult.Declined("INVALID_REJECT_CARD"))
        assertThat(unknown).isEqualTo(ProviderPaymentResult.Unknown("TOSS_CONFIRM_INTERNAL_SERVER_ERROR"))
    }

    @Test
    fun `full cancel omits amount while partial cancel sends the exact server amount`() {
        val fullReason = refundReason("cancel-full-idempotency")
        val partialReason = refundReason("cancel-partial-idempotency")
        responses +=
            StubResponse(
                200,
                """{"status":"CANCELED","cancels":[{"cancelAmount":1000,"cancelReason":"$fullReason","cancelStatus":"DONE","transactionKey":"cancel-full"}]}""",
            )
        responses +=
            StubResponse(
                200,
                """{"status":"PARTIAL_CANCELED","cancels":[{"cancelAmount":400,"cancelReason":"$partialReason","cancelStatus":"DONE","transactionKey":"cancel-partial"}]}""",
            )
        val lookup = lookupRequest()

        val full = gateway.void(lookup, "cancel-full-idempotency")
        val partial = gateway.requestRefund(lookup, 400, "cancel-partial-idempotency")

        assertThat(full).isEqualTo(GatewayRecoveryResult.Succeeded)
        assertThat(partial).isEqualTo(GatewayRefundResult.Succeeded("cancel-partial"))
        val captured = requests.toList()
        assertThat(captured).hasSize(2)
        assertThat(captured[0].path).isEqualTo("/v1/payments/pay-key/cancel")
        assertThat(captured[0].body).doesNotContain("cancelAmount")
        assertThat(captured[0].idempotencyKey).isEqualTo("cancel-full-idempotency")
        assertThat(ObjectMapper().readTree(captured[0].body).path("cancelReason").asText()).isEqualTo(fullReason)
        val partialBody = ObjectMapper().readTree(captured[1].body)
        assertThat(partialBody.path("cancelAmount").asLong()).isEqualTo(400)
        assertThat(partialBody.path("cancelReason").asText()).isEqualTo(partialReason)
        assertThat(captured[1].body).doesNotContain("cancel-partial-idempotency")
        assertThat(captured[1].idempotencyKey).isEqualTo("cancel-partial-idempotency")
    }

    @Test
    fun `partial refund lookup recovers an unknown operation by exact amount and marker`() {
        val key = "refund-partial-unknown"
        responses +=
            StubResponse(
                200,
                """{"cancels":[{"cancelAmount":400,"cancelReason":"${refundReason(
                    key,
                )}","cancelStatus":"DONE","transactionKey":"refund-partial"}]}""",
            )

        val result = gateway.lookupRefund(lookupRequest(), 400, key)

        assertThat(result).isEqualTo(GatewayRefundResult.Succeeded("refund-partial"))
    }

    @Test
    fun `same amount refunds are distinguished by their operation marker`() {
        val currentKey = "refund-same-amount-two"
        responses +=
            StubResponse(
                200,
                """
                {"cancels":[
                    {"cancelAmount":400,"cancelReason":"${refundReason(currentKey)}","cancelStatus":"DONE","transactionKey":"refund-two"},
                    {"cancelAmount":400,"cancelReason":"${refundReason(
                    "refund-same-amount-one",
                )}","cancelStatus":"DONE","transactionKey":"refund-one"}
                ]}
                """.trimIndent(),
            )

        val result = gateway.lookupRefund(lookupRequest(), 400, currentKey)

        assertThat(result).isEqualTo(GatewayRefundResult.Succeeded("refund-two"))
    }

    @Test
    fun `unrelated full cancel cannot satisfy a partial refund lookup`() {
        val key = "refund-partial-with-full-cancel"
        responses +=
            StubResponse(
                200,
                """
                {"cancels":[
                    {"cancelAmount":400,"cancelReason":"${refundReason(key)}","cancelStatus":"DONE","transactionKey":"refund-partial"},
                    {"cancelAmount":1000,"cancelReason":"${refundReason(
                    "unrelated-full-cancel",
                )}","cancelStatus":"DONE","transactionKey":"cancel-full"}
                ]}
                """.trimIndent(),
            )

        val result = gateway.lookupRefund(lookupRequest(), 400, key)

        assertThat(result).isEqualTo(GatewayRefundResult.Succeeded("refund-partial"))
    }

    @Test
    fun `same refund idempotency key replay selects the same cancellation`() {
        val key = "refund-replay-key"
        val response =
            StubResponse(
                200,
                """
                {"cancels":[
                    {"cancelAmount":400,"cancelReason":"${refundReason(key)}","cancelStatus":"DONE","transactionKey":"refund-replayed"},
                    {"cancelAmount":400,"cancelReason":"${refundReason(
                    "another-operation",
                )}","cancelStatus":"DONE","transactionKey":"refund-other"}
                ]}
                """.trimIndent(),
            )
        responses += response
        responses += response

        val first = gateway.requestRefund(lookupRequest(), 400, key)
        val replay = gateway.requestRefund(lookupRequest(), 400, key)

        assertThat(first).isEqualTo(GatewayRefundResult.Succeeded("refund-replayed"))
        assertThat(replay).isEqualTo(first)
    }

    @Test
    fun `order lookup keeps nonterminal and malformed Provider results unknown`() {
        responses +=
            StubResponse(
                200,
                """{"paymentKey":"pay-key","orderId":"bf_order_123","status":"IN_PROGRESS","totalAmount":1000,"currency":"KRW"}""",
            )
        responses +=
            StubResponse(
                200,
                """{"paymentKey":"other-key","orderId":"bf_order_123","status":"DONE","totalAmount":1000,"currency":"KRW"}""",
            )

        val nonterminal = gateway.lookup(lookupRequest(providerTransactionReference = null))
        val mismatched = gateway.lookup(lookupRequest())

        assertThat(nonterminal).isEqualTo(ProviderPaymentResult.Unknown("TOSS_STATUS_IN_PROGRESS"))
        assertThat(mismatched).isEqualTo(ProviderPaymentResult.Unknown("TOSS_PAYMENT_BINDING_MISMATCH"))
        assertThat(requests.first().path).isEqualTo("/v1/payments/orders/bf_order_123")
    }

    private fun confirmationRequest() =
        GatewayOneTimeConfirmationRequest(
            paymentId = UUID.randomUUID(),
            provider = "TOSS_PAYMENTS",
            providerOrderId = "bf_order_123",
            paymentKey = "pay-key",
            amountKrw = 1_000,
            currency = "KRW",
            providerIdempotencyKey = "provider-idempotency-key",
        )

    private fun lookupRequest(providerTransactionReference: String? = "pay-key") =
        GatewayLookupRequest(
            paymentId = UUID.randomUUID(),
            provider = "TOSS_PAYMENTS",
            tokenReference = null,
            providerCustomerReference = null,
            providerTransactionReference = providerTransactionReference,
            providerOrderId = "bf_order_123",
            amountKrw = 1_000,
            currency = "KRW",
        )

    private fun refundReason(providerIdempotencyKey: String): String = "BeanFlow refund [bf:${sha256(providerIdempotencyKey).take(24)}]"

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun handle(exchange: HttpExchange) {
        requests +=
            CapturedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                authorization = exchange.requestHeaders.getFirst(HttpHeaders.AUTHORIZATION),
                idempotencyKey = exchange.requestHeaders.getFirst("Idempotency-Key"),
                body = exchange.requestBody.bufferedReader().use { it.readText() },
            )
        val response = checkNotNull(responses.poll()) { "No stub response was queued" }
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add(HttpHeaders.CONTENT_TYPE, "application/json")
        exchange.sendResponseHeaders(response.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

private data class StubResponse(
    val status: Int,
    val body: String,
)

private data class CapturedRequest(
    val method: String,
    val path: String,
    val authorization: String?,
    val idempotencyKey: String?,
    val body: String,
)
