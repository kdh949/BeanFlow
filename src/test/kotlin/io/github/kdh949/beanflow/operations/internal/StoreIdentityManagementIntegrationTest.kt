package io.github.kdh949.beanflow.operations.internal

import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import io.github.kdh949.beanflow.merchant.api.StoreIdentityCommand
import io.github.kdh949.beanflow.merchant.api.StoreIdentitySnapshot
import io.github.kdh949.beanflow.shared.api.DomainFailure
import io.github.kdh949.beanflow.shared.api.FailureCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies Store creation, search index atomicity, and concurrent committed commands")
@SpringBootTest
internal class StoreIdentityManagementIntegrationTest(
    @Autowired private val service: OperatorStoreIdentityService,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
) {
    @BeforeEach fun clean() {
        jdbc.execute("TRUNCATE merchant_store, operations_operator_permission_grant, operations_audit_record CASCADE")
    }

    @Test fun `HTTP creation supplies a searchable closed Store with validated region and coordinates`() {
        val actor = operator()
        val result =
            mvc
                .perform(
                    post("/api/v1/operations/stores")
                        .with(jwt(actor))
                        .header("Idempotency-Key", "store-create-http")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(CreateStoreIdentityRequest("새 매장", 37.5, 127.03, REGION, "개설 확인"))),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.acceptingOrders").value(false))
                .andExpect(jsonPath("$.pickupEnabled").value(false))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn()
                .response.contentAsString
        val store = mapper.readValue(result, StoreIdentitySnapshot::class.java)
        assertThat(store.latitude).isEqualTo(37.5)
        assertThat(store.longitude).isEqualTo(127.03)
        assertThat(count("merchant_store")).isOne()
        assertThat(count("merchant_store_discovery_profile")).isOne()
        assertThat(count("discovery_store_search_term")).isEqualTo(4)
        mvc
            .perform(
                get("/api/v1/operations/stores/${store.storeId}/identity").with(jwt(actor)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("새 매장"))
        mvc
            .perform(
                get("/api/v1/operations/stores").param("query", "새 매장").with(jwt(actor)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
        mvc
            .perform(
                get("/api/v1/operations/store-regions").param("query", "역삼동").with(jwt(actor)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].code").value(REGION))
        assertThat(count("merchant_store_settlement_terms")).isZero()
        assertThat(count("fulfillment_pickup_slot")).isZero()
    }

    @Test fun `identity replacement preserves region and replay survives a later update`() {
        val actor = operator()
        val original = service.change(create(actor))
        val command =
            create(
                actor,
            ).copy(
                key = "identity-change-key",
                storeId = original.storeId,
                regionCode = null,
                expectedVersion = 0,
                name = "변경 매장",
                latitude = 35.0,
                longitude = 129.0,
            )
        val first = service.change(command)
        assertThat(first.version).isEqualTo(1)
        assertThat(first.regionCode).isEqualTo(REGION)
        service.change(command.copy(key = "identity-change-next", expectedVersion = 1, name = "최종 매장"))
        assertThat(service.change(command)).isEqualTo(first)
        assertThatThrownBy {
            service.change(command.copy(name = "다른 요청"))
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.IDEMPOTENCY_KEY_REUSED)
        }
        assertThatThrownBy {
            service.change(command.copy(key = "identity-stale-key"))
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.RESOURCE_STATE_CONFLICT)
        }
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM discovery_store_search_term WHERE term_kind = 'STORE_NAME' AND display_text = '최종 매장'",
                Long::class.java,
            ),
        ).isOne()
        assertThat(count("merchant_store_identity_command")).isEqualTo(3)
    }

    @Test fun `invalid coordinates unknown region and privileged body fields cannot create stores`() {
        val actor = operator()
        assertThatThrownBy {
            service.change(create(actor).copy(latitude = Double.NaN))
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.INVALID_REQUEST)
        }
        assertThatThrownBy {
            service.change(create(actor).copy(regionCode = "0000000000"))
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.RESOURCE_NOT_FOUND)
        }
        mvc
            .perform(
                post("/api/v1/operations/stores")
                    .with(jwt(actor))
                    .header("Idempotency-Key", "store-invalid-http")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"매장","latitude":91,"longitude":127,"regionCode":"$REGION","reason":"확인"}"""),
            ).andExpect(status().isBadRequest)
        mvc
            .perform(
                post("/api/v1/operations/stores")
                    .with(jwt(actor))
                    .header("Idempotency-Key", "store-unknown-http")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"매장","latitude":37,"longitude":127,"regionCode":"$REGION","reason":"확인","acceptingOrders":true}"""),
            ).andExpect(status().isBadRequest)
        assertThat(count("merchant_store")).isZero()
    }

    @Test fun `Audit and index failures roll back Store profile and response together`() {
        val actor = operator()
        jdbc.execute("ALTER TABLE operations_audit_record ADD CONSTRAINT test_store_create_audit CHECK (action <> 'STORE_CREATED')")
        try {
            assertThatThrownBy {
                service.change(create(actor))
            }.isInstanceOf(RuntimeException::class.java)
        } finally {
            jdbc.execute("ALTER TABLE operations_audit_record DROP CONSTRAINT test_store_create_audit")
        }
        assertThat(count("merchant_store")).isZero()
        assertThat(count("merchant_store_discovery_profile")).isZero()
        assertThat(count("discovery_store_search_term")).isZero()
        assertThat(count("merchant_store_identity_command")).isZero()
        jdbc.execute("ALTER TABLE discovery_store_search_term ADD CONSTRAINT test_store_index CHECK (term_kind <> 'STORE_NAME')")
        try {
            assertThatThrownBy {
                service.change(create(actor))
            }.isInstanceOf(RuntimeException::class.java)
        } finally {
            jdbc.execute("ALTER TABLE discovery_store_search_term DROP CONSTRAINT test_store_index")
        }
        assertThat(count("merchant_store")).isZero()
        assertThat(count("operations_audit_record")).isZero()
    }

    @Test fun `signed pages are bounded filter and actor scoped`() {
        val actor = operator()
        repeat(3) {
            service.change(create(actor).copy(key = "page-create-key-$it", name = "페이지 $it"))
        }
        val first = service.list(actor, "페이지", null, 2)
        val second = service.list(actor, "페이지", first.nextCursor, 2)
        assertThat(first.items + second.items).hasSize(3).doesNotHaveDuplicates()
        assertThat(second.nextCursor).isNull()
        assertThatThrownBy {
            service.list(actor, "다른", first.nextCursor, 2)
        }.isInstanceOf(DomainFailure::class.java)
        assertThatThrownBy {
            service.list(operator(), "페이지", first.nextCursor, 2)
        }.isInstanceOf(DomainFailure::class.java)
        assertThatThrownBy {
            service.list(actor, "페이지", first.nextCursor + "x", 2)
        }.isInstanceOf(DomainFailure::class.java)
    }

    @Test fun `revoked grant prevents replay and role or read grant alone cannot write`() {
        val actor = operator()
        val command = create(actor)
        service.change(command)
        jdbc.update(
            "UPDATE operations_operator_permission_grant SET state = 'REVOKED', revoked_at = now() WHERE actor_id = ? AND permission = 'STORE_IDENTITY_WRITE'",
            actor,
        )
        assertThatThrownBy {
            service.change(command)
        }.isInstanceOfSatisfying(DomainFailure::class.java) {
            assertThat(it.code).isEqualTo(FailureCode.ACCESS_DENIED)
        }
        mvc
            .perform(
                post("/api/v1/operations/stores")
                    .with(jwt(UUID.randomUUID()))
                    .header("Idempotency-Key", "store-denied-key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(CreateStoreIdentityRequest("매장", 37.5, 127.0, REGION, "확인"))),
            ).andExpect(status().isForbidden)
        assertThat(count("merchant_store")).isOne()
    }

    @Test fun `simultaneous same key creation returns one Store and competing replacements reject stale version`() {
        val actor = operator()
        val command = create(actor)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val results =
                (1..2)
                    .map {
                        executor.submit<StoreIdentitySnapshot> {
                            barrier.await(5, TimeUnit.SECONDS)
                            service.change(command)
                        }
                    }.map {
                        it.get(20, TimeUnit.SECONDS)
                    }
            assertThat(results[0]).isEqualTo(results[1])
            assertThat(count("merchant_store")).isOne()
            val secondActor = operator()
            val secondBarrier = CyclicBarrier(2)
            val attempts =
                listOf(actor, secondActor)
                    .map { writer ->
                        executor.submit<Boolean> {
                            secondBarrier.await(5, TimeUnit.SECONDS)
                            try {
                                service.change(
                                    command.copy(actorId = writer, storeId = results[0].storeId, regionCode = null, expectedVersion = 0),
                                )
                                true
                            } catch (failure: DomainFailure) {
                                assertThat(failure.code).isEqualTo(FailureCode.RESOURCE_STATE_CONFLICT)
                                false
                            }
                        }
                    }.map {
                        it.get(20, TimeUnit.SECONDS)
                    }
            assertThat(attempts).containsExactlyInAnyOrder(true, false)
        } finally {
            executor.shutdownNow()
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
        }
    }

    private fun create(actor: UUID) =
        StoreIdentityCommand(actor, "store-creation-key", null, "새 매장", 37.5, 127.03, REGION, null, "개설 근거 확인", Instant.now())

    private fun count(table: String) = requireNotNull(jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java))

    private fun jwt(actor: UUID) =
        jwt()
            .jwt {
                it.subject(actor.toString())
            }.authorities(SimpleGrantedAuthority("ROLE_PLATFORM_OPERATOR"))

    private fun operator(): UUID =
        UUID.randomUUID().also { actor ->
            listOf("STORE_IDENTITY_READ", "STORE_IDENTITY_WRITE").forEach { permission ->
                jdbc.update(
                    "INSERT INTO operations_operator_permission_grant(actor_id, permission, state, granted_at, version, audit_source_reference) VALUES (?, ?, 'ACTIVE', ?, 1, ?)",
                    actor,
                    permission,
                    Timestamp.from(Instant.now().minusSeconds(1)),
                    "store:$actor:$permission",
                )
            }
        }

    private companion object {
        const val REGION = "1168010100"
    }
}
