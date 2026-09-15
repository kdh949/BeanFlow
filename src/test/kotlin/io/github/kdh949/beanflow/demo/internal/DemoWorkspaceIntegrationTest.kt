package io.github.kdh949.beanflow.demo.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.architecture.HttpOperation
import io.github.kdh949.beanflow.architecture.OpenApiOperationInventory
import io.github.kdh949.beanflow.identity.api.CustomerOrderingAccess
import io.github.kdh949.beanflow.ordering.internal.StoreAcceptanceDeadlineService
import io.github.kdh949.beanflow.shared.api.BrowserActorLoader
import io.github.kdh949.beanflow.shared.api.BrowserActorType
import io.github.kdh949.beanflow.shared.api.BrowserAuthenticationInvalid
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Import(TestcontainersConfiguration::class, DemoTestClockConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("committed visitor sessions, concurrency, rollback and expiry require isolated PostgreSQL")
@SpringBootTest(
    properties = [
        "beanflow.demo.enabled=true", "beanflow.toss.client-key=test_ck_visitor_demo",
        "beanflow.demo.expiry-initial-delay-ms=3600000",
    ],
)
internal class DemoWorkspaceIntegrationTest
    @Autowired
    constructor(
        @Qualifier("requestMappingHandlerMapping")
        private val mapping: RequestMappingHandlerMapping,
        private val service: DemoWorkspaceService,
        private val jdbc: JdbcTemplate,
        private val mvc: MockMvc,
        private val mapper: ObjectMapper,
        private val clock: DemoMutableClock,
        private val loaders: List<BrowserActorLoader>,
        private val deadlines: StoreAcceptanceDeadlineService,
        private val orderingAccess: CustomerOrderingAccess,
        transactionManager: PlatformTransactionManager,
    ) {
        // This suite uses the existing explicit Testcontainers provider doubles, without activating local providers.
        // The real safety singleton is exercised independently by DemoSafetyTest.
        @MockitoBean(name = "demoSafety")
        private lateinit var safety: SmartInitializingSingleton
        private val transactions = TransactionTemplate(transactionManager)
        private val empty = PresentedDemoSessions(null, null)

        @BeforeEach fun prepare() {
            clock.set(Instant.parse("2026-09-15T03:00:00Z"))
            jdbc.execute("TRUNCATE demo_workspace CASCADE")
        }

        private fun hash() =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .repeat(2)

        private fun start(
            browser: String = hash(),
            key: String =
                UUID
                    .randomUUID()
                    .toString(),
        ) = service.start(
            browser,
            key,
            "GUIDED",
            empty,
        )

        @Test fun `enabled demo mappings exactly match the documented operations`() {
            val documented =
                OpenApiOperationInventory
                    .load(Path.of("openapi/beanflow-v1-runtime.yaml"))
                    .filter { it.path.startsWith("/api/v1/demo/") }
                    .toSet()
            val actual =
                mapping.handlerMethods.keys
                    .flatMap { route ->
                        route.patternValues
                            .filter { it.startsWith("/api/v1/demo/") }
                            .flatMap { path -> route.methodsCondition.methods.map { method -> HttpOperation(path, method.name) } }
                    }.toSet()
            assertThat(actual).hasSize(8).isEqualTo(documented)
        }

        @Test fun `fresh visitors get isolated accounts stores and real paid benefit orders`() {
            val a = start()
            val b = start()
            assertThat(a.customerId).isNotEqualTo(b.customerId)
            assertThat(a.merchantId).isNotEqualTo(b.merchantId)
            assertThat(a.storeId).isNotEqualTo(b.storeId)
            assertThat(a.orderReference).isNotEqualTo(b.orderReference)
            assertThat(service.current(a.browserHash)?.order?.status).isEqualTo("PAID")
            assertThat(
                jdbc.queryForObject(
                    "SELECT payable_krw FROM ordering_order WHERE public_reference = ?",
                    Long::class.java,
                    a.orderReference,
                ),
            ).isZero()
            assertThatThrownBy { service.track(a.browserHash, "foreign-order-test", requireNotNull(b.orderReference), empty) }
                .isInstanceOf(io.github.kdh949.beanflow.shared.api.DomainFailure::class.java)
            assertThatThrownBy { transactions.execute { orderingAccess.requireStore(a.customerId, b.storeId) } }
                .isInstanceOf(io.github.kdh949.beanflow.shared.api.DomainFailure::class.java)
        }

        @Test fun `same start key serializes concurrent requests into one workspace and one order`() {
            val browser = hash()
            val key = UUID.randomUUID().toString()
            val gate = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results =
                    (1..2).map {
                        executor.submit<DemoWorkspace> {
                            gate.await()
                            start(browser, key)
                        }
                    }
                gate.countDown()
                val created = results.map { it.get(30, TimeUnit.SECONDS) }
                assertThat(created[0].id).isEqualTo(created[1].id)
                assertThat(created[0].orderReference).isEqualTo(created[1].orderReference)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM demo_workspace WHERE browser_hash = ?",
                        Int::class.java,
                        browser,
                    ),
                ).isEqualTo(1)
            } finally {
                executor.shutdownNow()
            }
        }

        @Test fun `expiry boundary and explicit end invalidate both actor loaders without deleting order evidence`() {
            val w = start()
            val customer = loaders.single { it.actorType == BrowserActorType.CUSTOMER }
            val merchant = loaders.single { it.actorType == BrowserActorType.MERCHANT }
            customer.load(w.customerId, 0)
            merchant.load(w.merchantId, 0)
            clock.set(w.expiresAt)
            assertThatThrownBy { customer.load(w.customerId, 0) }.isInstanceOf(BrowserAuthenticationInvalid::class.java)
            assertThatThrownBy { merchant.load(w.merchantId, 0) }.isInstanceOf(BrowserAuthenticationInvalid::class.java)
            assertThat(service.current(w.browserHash)?.status).isEqualTo("EXPIRED")
            service.expire()
            assertThat(
                jdbc.queryForObject(
                    "SELECT accepting_orders FROM merchant_store WHERE id = ?",
                    Boolean::class.java,
                    w.storeId,
                ),
            ).isFalse()
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM ordering_order WHERE public_reference = ?",
                    Int::class.java,
                    w.orderReference,
                ),
            ).isEqualTo(1)
            val fresh = start()
            service.end(fresh.browserHash, empty)
            assertThatThrownBy { customer.load(fresh.customerId, 0) }.isInstanceOf(BrowserAuthenticationInvalid::class.java)
            assertThatThrownBy { merchant.load(fresh.merchantId, 0) }.isInstanceOf(BrowserAuthenticationInvalid::class.java)
            assertThat(service.current(fresh.browserHash)?.status).isEqualTo("ENDED")
        }

        @Test fun `existing login is preserved and active orders cannot be silently replaced`() {
            assertThatThrownBy {
                service.start(
                    hash(),
                    UUID
                        .randomUUID()
                        .toString(),
                    "GUIDED",
                    PresentedDemoSessions(
                        "ordinary-customer-session",
                        null,
                    ),
                )
            }.isInstanceOf(DemoFailure::class.java)
                .hasMessageContaining("Sign out")
            val w = start()
            assertThatThrownBy { service.sample(w.browserHash, "new-sample-test", empty) }.isInstanceOf(DemoFailure::class.java)
            assertThat(service.current(w.browserHash)?.order?.orderReference).isEqualTo(w.orderReference)
        }

        @Test fun `late transaction failure rolls back identities store points sessions and workspace`() {
            fun counts() =
                listOf(
                    "identity_customer_account",
                    "identity_merchant_account",
                    "merchant_store",
                    "loyalty_point_account",
                    "spring_session",
                    "demo_workspace",
                ).associateWith {
                    jdbc.queryForObject("SELECT count(*) FROM $it", Long::class.java)
                }
            val before = counts()
            jdbc.execute(
                "CREATE FUNCTION demo_test_fail() RETURNS trigger LANGUAGE plpgsql AS " +
                    "'BEGIN RAISE EXCEPTION ''demo injected failure''; END'",
            )
            jdbc.execute("CREATE TRIGGER demo_test_failure BEFORE INSERT ON demo_workspace FOR EACH ROW EXECUTE FUNCTION demo_test_fail()")
            try {
                assertThatThrownBy { start() }.isInstanceOf(RuntimeException::class.java)
            } finally {
                jdbc.execute("DROP TRIGGER demo_test_failure ON demo_workspace")
                jdbc.execute("DROP FUNCTION demo_test_fail()")
            }
            assertThat(counts()).isEqualTo(before)
        }

        @Test fun `timeout preserves the original order and a retry replays one new paid sample`() {
            val w = start()
            val original = requireNotNull(w.orderReference)
            val orderId =
                jdbc.queryForObject(
                    "SELECT id FROM ordering_order WHERE public_reference = ?",
                    UUID::class.java,
                    original,
                )!!
            clock.set(w.createdAt.plusSeconds(180))
            deadlines.rejectTimedOut(orderId, clock.instant())
            assertThat(service.current(w.browserHash)?.order?.status).isEqualTo("REJECTED")
            val next = service.sample(w.browserHash, "sample-after-timeout", empty)
            val replay = service.sample(w.browserHash, "sample-after-timeout", empty)
            assertThat(next.order?.status).isEqualTo("PAID")
            assertThat(next.order?.orderReference).isNotEqualTo(original).isEqualTo(replay.order?.orderReference)
            assertThat(
                jdbc.queryForObject(
                    "SELECT state FROM ordering_order WHERE public_reference = ?",
                    String::class.java,
                    original,
                ),
            ).isEqualTo("REJECTED")
            assertThatThrownBy { transactions.execute { orderingAccess.requireStore(UUID.randomUUID(), w.storeId) } }
                .isInstanceOf(io.github.kdh949.beanflow.shared.api.DomainFailure::class.java)
        }

        @Test fun `browser quota and start payload reuse fail without another workspace`() {
            val browser = hash()
            repeat(5) {
                val w = start(browser)
                assertThat(service.current(browser)?.workspaceId).isEqualTo(w.id)
                assertThatThrownBy { service.start(browser, w.startKey, "DIRECT", empty) }.isInstanceOf(DemoFailure::class.java)
                service.end(browser, empty)
            }
            assertThatThrownBy { start(browser) }.isInstanceOf(DemoFailure::class.java).hasMessageContaining("quota")
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM demo_workspace WHERE browser_hash = ?",
                    Int::class.java,
                    browser,
                ),
            ).isEqualTo(5)
        }

        @Test fun `csrf protected start issues secure actor cookies and ownership is enforced by ordinary APIs`() {
            mvc
                .perform(
                    post("/api/v1/demo/sessions")
                        .secure(true)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"GUIDED\"}")
                        .header("Idempotency-Key", UUID.randomUUID().toString()),
                ).andExpect(status().isForbidden)
            val bootstrap = mvc.perform(get("/api/v1/demo/csrf").secure(true)).andExpect(status().isOk).andReturn()
            val browserCookies = bootstrap.response.cookies
            val token = mapper.readTree(bootstrap.response.contentAsString).path("token").asText()
            val result =
                mvc
                    .perform(
                        post("/api/v1/demo/sessions")
                            .secure(true)
                            .cookie(*browserCookies)
                            .header("X-BEANFLOW-CSRF", token)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mode\":\"GUIDED\"}"),
                    ).andExpect(status().isCreated)
                    .andExpect(jsonPath("$.order.status").value("PAID"))
                    .andExpect(jsonPath("$.customerSessionId").doesNotExist())
                    .andExpect(jsonPath("$.merchantSessionId").doesNotExist())
                    .andExpect(
                        cookie()
                            .httpOnly(
                                "BEANFLOW_CUSTOMER_SESSION",
                                true,
                            ),
                    ).andExpect(
                        cookie()
                            .secure(
                                "BEANFLOW_CUSTOMER_SESSION",
                                true,
                            ),
                    ).andExpect(cookie().httpOnly("BEANFLOW_MERCHANT_SESSION", true))
                    .andReturn()
            val actorCookies = result.response.cookies
            val body = mapper.readTree(result.response.contentAsString)
            val reference = body.path("order").path("orderReference").asText()
            mvc
                .perform(
                    get("/api/v1/me")
                        .secure(true)
                        .cookie(*actorCookies),
                ).andExpect(
                    status()
                        .isOk,
                ).andExpect(
                    jsonPath("$.displayName")
                        .value("체험 고객"),
                )
            mvc
                .perform(
                    get("/api/v1/merchant/me/stores")
                        .secure(true)
                        .cookie(*actorCookies),
                ).andExpect(
                    status()
                        .isOk,
                ).andExpect(
                    jsonPath("$[0].storeId")
                        .value(
                            body
                                .path("storeId")
                                .asText(),
                        ),
                )
            mvc
                .perform(
                    get("/api/v1/me/orders/$reference")
                        .secure(true)
                        .cookie(*actorCookies),
                ).andExpect(
                    status()
                        .isOk,
                ).andExpect(
                    jsonPath("$.status")
                        .value("PAID"),
                )
            // Drive the actual existing merchant transition endpoint, then observe the same customer order.
            val merchantCsrf =
                mvc
                    .perform(get("/api/v1/auth/merchant/csrf").secure(true).cookie(*actorCookies))
                    .andExpect(status().isNoContent)
                    .andReturn()
            val merchantToken =
                merchantCsrf.response.cookies
                    .single { it.name == "BEANFLOW_MERCHANT_XSRF" }
                    .value
            val transitions =
                listOf(
                    "ACCEPT" to "ACCEPTED",
                    "START_PREPARING" to "PREPARING",
                    "MARK_READY" to "READY",
                    "COMPLETE" to "COMPLETED",
                )
            var previous = "PAID"
            transitions.forEach { (action, expected) ->
                mvc
                    .perform(
                        post("/api/v1/stores/${body.path("storeId").asText()}/orders/$reference/transitions")
                            .secure(true)
                            .cookie(*(actorCookies + merchantCsrf.response.cookies))
                            .header("X-BEANFLOW-CSRF", merchantToken)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(mapOf("action" to action, "expectedStatus" to previous, "reason" to null))),
                    ).andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value(expected))
                mvc
                    .perform(get("/api/v1/me/orders/$reference").secure(true).cookie(*actorCookies))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.status").value(expected))
                previous = expected
            }
            val other = start()
            mvc
                .perform(
                    get("/api/v1/me/orders/${other.orderReference}")
                        .secure(true)
                        .cookie(*actorCookies),
                ).andExpect(
                    status()
                        .isForbidden,
                )
            mvc
                .perform(
                    get("/api/v1/stores/${other.storeId}/orders/${other.orderReference}")
                        .secure(true)
                        .cookie(*actorCookies),
                ).andExpect(
                    status()
                        .isForbidden,
                )
            mvc
                .perform(
                    delete("/api/v1/demo/session")
                        .secure(true)
                        .cookie(*(browserCookies + actorCookies))
                        .header(
                            "X-BEANFLOW-CSRF",
                            token,
                        ),
                ).andExpect(status().isNoContent)
            mvc.perform(get("/api/v1/me").secure(true).cookie(*actorCookies)).andExpect(status().isUnauthorized)
        }
    }

@TestConfiguration(proxyBeanMethods = false)
internal class DemoTestClockConfiguration {
    @Bean @Primary
    fun demoClock() = DemoMutableClock()
}

internal class DemoMutableClock : Clock() {
    private val current = AtomicReference(Instant.parse("2026-09-15T03:00:00Z"))

    fun set(value: Instant) {
        current.set(value)
    }

    override fun instant(): Instant = current.get()

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
}
