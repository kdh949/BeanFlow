package io.github.kdh949.beanflow.identity.internal

import com.jayway.jsonpath.JsonPath
import io.github.kdh949.beanflow.BeanflowIsolatedSpringContext
import io.github.kdh949.beanflow.TestcontainersConfiguration
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Import(TestcontainersConfiguration::class)
@AutoConfigureMockMvc
@BeanflowIsolatedSpringContext("verifies transactional Menu catalogue authoring")
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
internal class MenuCatalogEndpointIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val passwords: CustomerPasswordSecurity,
) {
    @BeforeEach
    fun cleanDatabase() {
        jdbc.execute(
            """
            TRUNCATE TABLE spring_session_attributes, spring_session, identity_login_attempt,
                merchant_menu_catalog_command, discovery_store_search_term,
                identity_store_membership, identity_merchant_account, operations_audit_record,
                merchant_menu_configuration_requirement, merchant_menu_configuration,
                merchant_menu_option, merchant_menu, inventory_stock_reservation,
                inventory_sellable_stock, merchant_store CASCADE
            """.trimIndent(),
        )
    }

    @Test
    fun `OWNER creates replaces no-ops replays and archives a complete Menu aggregate atomically`() {
        val storeId = seedStore()
        val actor = signIn("catalog.owner", storeId, "OWNER")
        val menuId = UUID.randomUUID()
        val optionId = UUID.randomUUID()
        val configurationId = UUID.randomUUID()
        val replacementOptionId = UUID.randomUUID()
        val replacementConfigurationId = UUID.randomUUID()
        val sellableUnitId = UUID.randomUUID()

        val created =
            mutate(
                post("/api/v1/stores/$storeId/menus"),
                actor,
                "menu-create-key-0001",
                content(storeId, menuId, optionId, configurationId, sellableUnitId, "카페 라테", 4_500),
            ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.menuId").value(menuId.toString()))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.lifecycle").value("ACTIVE"))
                .andReturn()
                .response.contentAsString

        mutate(
            post("/api/v1/stores/$storeId/menus"),
            actor,
            "menu-create-key-0001",
            content(storeId, menuId, optionId, configurationId, sellableUnitId, "카페 라테", 4_500),
        ).andExpect(status().isCreated)
            .andExpect { assertThat(it.response.contentAsString).isEqualTo(created) }

        mockMvc
            .perform(get("/api/v1/stores/$storeId/menus").cookie(customerSession().session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].menuId").value(menuId.toString()))
        assertThat(searchTerms(storeId)).containsExactly("카페 라테")

        mutate(
            put("/api/v1/stores/$storeId/menus/$menuId/trade-content"),
            actor,
            "menu-replace-key-001",
            content(
                storeId,
                menuId,
                replacementOptionId,
                replacementConfigurationId,
                sellableUnitId,
                "바닐라 라테",
                5_000,
                expectedVersion = 0,
            ),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("바닐라 라테"))
            .andExpect(jsonPath("$.version").value(1))

        mutate(
            put("/api/v1/stores/$storeId/menus/$menuId/trade-content"),
            actor,
            "menu-noop-key-0001",
            content(
                storeId,
                menuId,
                replacementOptionId,
                replacementConfigurationId,
                sellableUnitId,
                "바닐라 라테",
                5_000,
                expectedVersion = 1,
            ),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(1))

        mutate(
            post("/api/v1/stores/$storeId/menus/$menuId/archive"),
            actor,
            "menu-archive-key-001",
            """{"expectedVersion":1}""",
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.lifecycle").value("ARCHIVED"))
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.options.length()").value(1))
            .andExpect(jsonPath("$.options[0].optionId").value(replacementOptionId.toString()))
            .andExpect(jsonPath("$.configurations.length()").value(1))
            .andExpect(jsonPath("$.configurations[0].configurationId").value(replacementConfigurationId.toString()))

        mockMvc
            .perform(get("/api/v1/stores/$storeId/menus/$menuId/trade-content").cookie(actor.session, actor.csrf))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RESOURCE_STATE_CONFLICT"))

        mockMvc
            .perform(get("/api/v1/stores/$storeId/menus").cookie(customerSession().session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isEmpty)
        assertThat(searchTerms(storeId)).isEmpty()
        assertThat(auditActions(menuId)).containsExactly("MENU_CATALOG_CREATED", "MENU_CATALOG_UPDATED", "MENU_CATALOG_ARCHIVED")
        assertThat(commandCount()).isEqualTo(4)
    }

    @Test
    fun `STAFF is allowed while stale revoked cross-store and changed replay are explicit failures`() {
        val storeId = seedStore()
        val otherStore = seedStore()
        val actor = signIn("catalog.staff", storeId, "STAFF")
        val menuId = UUID.randomUUID()
        val optionId = UUID.randomUUID()
        val configurationId = UUID.randomUUID()
        val unitId = UUID.randomUUID()
        val payload = content(storeId, menuId, optionId, configurationId, unitId, "필터 커피", 4_000)

        mutate(post("/api/v1/stores/$storeId/menus"), actor, "menu-staff-key-0001", payload)
            .andExpect(status().isCreated)
        mutate(
            put("/api/v1/stores/$storeId/menus/$menuId/trade-content"),
            actor,
            "menu-stale-key-0001",
            content(storeId, menuId, optionId, configurationId, unitId, "필터 커피", 4_000, expectedVersion = 7),
        ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("MERCHANT_CONTENT_STALE"))
        mutate(post("/api/v1/stores/$storeId/menus"), actor, "menu-staff-key-0001", payload.replace("4000", "4100"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        mockMvc
            .perform(get("/api/v1/stores/$otherStore/menu-catalog").cookie(actor.session, actor.csrf))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))

        jdbc.update("UPDATE identity_store_membership SET status = 'REVOKED' WHERE actor_id = ?", actor.actorId)
        mockMvc
            .perform(get("/api/v1/stores/$storeId/menu-catalog").cookie(actor.session, actor.csrf))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
    }

    @Test
    fun `invalid aggregate references and identifier collisions do not leave partial rows`() {
        val storeId = seedStore()
        val actor = signIn("catalog.invalid", storeId, "OWNER")
        val menuId = UUID.randomUUID()
        val optionId = UUID.randomUUID()
        val configurationId = UUID.randomUUID()
        val missingOptionId = UUID.randomUUID()
        val unitId = UUID.randomUUID()
        val invalid =
            content(storeId, menuId, optionId, configurationId, unitId, "콜드브루", 4_800)
                .replaceFirst(optionId.toString(), missingOptionId.toString())

        mutate(post("/api/v1/stores/$storeId/menus"), actor, "menu-invalid-key-001", invalid)
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RESOURCE_STATE_CONFLICT"))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_menu", Long::class.java)).isZero()
        assertThat(commandCount()).isZero()
    }

    @Test
    fun `available Menu rejects missing and cross-Store sellable units without partial writes`() {
        val storeId = seedStore()
        val otherStoreId = seedStore()
        val actor = signIn("catalog.sellable.validation", storeId, "OWNER")
        val missingUnitId = UUID.randomUUID()

        mutate(
            post("/api/v1/stores/$storeId/menus"),
            actor,
            "menu-missing-unit-001",
            content(
                storeId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                missingUnitId,
                "누락 재고 메뉴",
                4_000,
                seedStock = false,
            ),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        val crossStoreUnitId = UUID.randomUUID()
        seedSellableUnit(otherStoreId, crossStoreUnitId)
        mutate(
            post("/api/v1/stores/$storeId/menus"),
            actor,
            "menu-cross-store-unit-001",
            content(
                storeId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                crossStoreUnitId,
                "다른 매장 재고 메뉴",
                4_500,
                seedStock = false,
            ),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_menu", Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java)).isZero()
        assertThat(commandCount()).isZero()
    }

    @Test
    fun `authoring list uses a stable cursor bound to actor store lifecycle and limit`() {
        val storeId = seedStore()
        val actor = signIn("catalog.cursor", storeId, "OWNER")
        val otherActor = signIn("catalog.cursor.other", storeId, "STAFF")
        val menuIds =
            listOf(
                UUID.fromString("30000000-0000-4000-8000-000000000011"),
                UUID.fromString("30000000-0000-4000-8000-000000000012"),
                UUID.fromString("30000000-0000-4000-8000-000000000013"),
            )
        jdbc.batchUpdate(
            "INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available) VALUES (?, ?, ?, 0, false)",
            menuIds.mapIndexed { index, menuId -> arrayOf(menuId, storeId, if (index < 2) "가나다" else "라마바") },
        )

        val first =
            mockMvc
                .perform(
                    get("/api/v1/stores/$storeId/menu-catalog").queryParam("limit", "2").cookie(actor.session, actor.csrf),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isString)
                .andReturn()
                .response.contentAsString
        val cursor = JsonPath.read<String>(first, "$.nextCursor")

        mockMvc
            .perform(
                get("/api/v1/stores/$storeId/menu-catalog")
                    .queryParam("limit", "2")
                    .queryParam("cursor", cursor)
                    .cookie(actor.session, actor.csrf),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].menuId").value(menuIds.last().toString()))
            .andExpect(jsonPath("$.nextCursor").doesNotExist())

        listOf(
            get("/api/v1/stores/$storeId/menu-catalog")
                .queryParam("limit", "3")
                .queryParam("cursor", cursor)
                .cookie(actor.session, actor.csrf),
            get("/api/v1/stores/$storeId/menu-catalog")
                .queryParam("limit", "2")
                .queryParam("lifecycle", "ARCHIVED")
                .queryParam("cursor", cursor)
                .cookie(actor.session, actor.csrf),
            get("/api/v1/stores/$storeId/menu-catalog")
                .queryParam("limit", "2")
                .queryParam("cursor", cursor)
                .cookie(otherActor.session, otherActor.csrf),
            get("/api/v1/stores/$storeId/menu-catalog")
                .queryParam("cursor", "not-a-signed-cursor")
                .cookie(actor.session, actor.csrf),
        ).forEach { request ->
            mockMvc
                .perform(request)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
    }

    @Test
    fun `aggregate boundaries accept 100 options 500 configurations and 50 requirements then reject overflow`() {
        val storeId = seedStore()
        val actor = signIn("catalog.boundary", storeId, "OWNER")

        mutate(
            post("/api/v1/stores/$storeId/menus"),
            actor,
            "menu-boundary-exact-001",
            boundaryContent(storeId, UUID.randomUUID(), optionCount = 100, configurationCount = 500, firstRequirementCount = 50),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.options.length()").value(100))
            .andExpect(jsonPath("$.configurations.length()").value(500))

        listOf(
            boundaryContent(storeId, UUID.randomUUID(), optionCount = 101, configurationCount = 0, firstRequirementCount = 0),
            boundaryContent(storeId, UUID.randomUUID(), optionCount = 9, configurationCount = 501, firstRequirementCount = 1),
            boundaryContent(storeId, UUID.randomUUID(), optionCount = 1, configurationCount = 1, firstRequirementCount = 51),
        ).forEachIndexed { index, payload ->
            mutate(post("/api/v1/stores/$storeId/menus"), actor, "menu-boundary-over-00$index", payload)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_menu", Long::class.java)).isOne()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_menu_option", Long::class.java)).isEqualTo(100)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_menu_configuration", Long::class.java)).isEqualTo(500)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_menu_configuration_requirement", Long::class.java)).isEqualTo(549)
        assertThat(commandCount()).isOne()
    }

    @Test
    fun `search index failure rolls back owner command and audit writes`() {
        val storeId = seedStore()
        val actor = signIn("catalog.search.rollback", storeId, "OWNER")
        val menuId = UUID.randomUUID()
        jdbc.execute(
            """
            CREATE OR REPLACE FUNCTION reject_menu_search_write() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
                RAISE EXCEPTION 'forced menu search failure';
            END;
            ${'$'}${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            "CREATE TRIGGER reject_menu_search BEFORE INSERT ON discovery_store_search_term " +
                "FOR EACH ROW WHEN (NEW.term_kind = 'MENU_NAME') EXECUTE FUNCTION reject_menu_search_write()",
        )
        try {
            mutate(
                post("/api/v1/stores/$storeId/menus"),
                actor,
                "menu-search-failure-001",
                content(storeId, menuId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "롤백 라테", 4_500),
            ).andExpect(status().isServiceUnavailable)
                .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))

            assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_menu", Long::class.java)).isZero()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM operations_audit_record", Long::class.java)).isZero()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM discovery_store_search_term", Long::class.java)).isZero()
            assertThat(commandCount()).isZero()
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_menu_search ON discovery_store_search_term")
            jdbc.execute("DROP FUNCTION IF EXISTS reject_menu_search_write()")
        }
    }

    @Test
    fun `concurrent identical Menu commands serialize to one write and one exact replay`() {
        val storeId = seedStore()
        val actor = signIn("catalog.concurrent", storeId, "OWNER")
        val secondSession = signInAgain("catalog.concurrent", actor.actorId)
        val menuId = UUID.randomUUID()
        val payload = content(storeId, menuId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "동시 라테", 4_500)
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val responses =
                listOf(actor, secondSession)
                    .map { session ->
                        executor.submit<String> {
                            barrier.await()
                            mutate(post("/api/v1/stores/$storeId/menus"), session, "menu-concurrent-key-001", payload)
                                .andExpect(status().isCreated)
                                .andReturn()
                                .response.contentAsString
                        }
                    }.map { it.get(15, TimeUnit.SECONDS) }

            assertThat(responses.distinct()).hasSize(1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_menu", Long::class.java)).isOne()
            assertThat(commandCount()).isOne()
            assertThat(auditActions(menuId)).containsExactly("MENU_CATALOG_CREATED")
            assertThat(searchTerms(storeId)).containsExactly("동시 라테")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `same actor operation and key across Stores serializes changed payload to deterministic conflict`() {
        val firstStoreId = seedStore()
        val secondStoreId = seedStore()
        val actor = signIn("catalog.cross-store.concurrent", firstStoreId, "OWNER")
        addMembership(actor.actorId, secondStoreId, "OWNER")
        val secondSession = signInAgain("catalog.cross-store.concurrent", actor.actorId)
        val firstMenuId = UUID.randomUUID()
        val secondMenuId = UUID.randomUUID()
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val statuses =
                listOf(
                    Triple(
                        firstStoreId,
                        actor,
                        content(firstStoreId, firstMenuId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "시청 라테", 4_500),
                    ),
                    Triple(
                        secondStoreId,
                        secondSession,
                        content(secondStoreId, secondMenuId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "강남 라테", 5_000),
                    ),
                ).map { (storeId, session, payload) ->
                    executor.submit<Int> {
                        barrier.await()
                        mutate(post("/api/v1/stores/$storeId/menus"), session, "menu-cross-store-key-001", payload)
                            .andReturn()
                            .response.status
                    }
                }.map { it.get(15, TimeUnit.SECONDS) }

            assertThat(statuses.count { it in 200..299 }).isEqualTo(1)
            assertThat(statuses.count { it == 409 }).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_menu", Long::class.java)).isOne()
            assertThat(commandCount()).isOne()
            assertThat(auditActions(firstMenuId).size + auditActions(secondMenuId).size).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `Store boundaries accept 1000 active Menus and 5000 active Options then reject overflow`() {
        val menuBoundStore = seedStore()
        val menuBoundActor = signIn("catalog.store.menu.boundary", menuBoundStore, "OWNER")
        seedMenus(menuBoundStore, 999)
        mutate(
            post("/api/v1/stores/$menuBoundStore/menus"),
            menuBoundActor,
            "store-menu-boundary-001",
            boundaryContent(menuBoundStore, UUID.randomUUID(), optionCount = 0, configurationCount = 0, firstRequirementCount = 0),
        ).andExpect(status().isCreated)
        mutate(
            post("/api/v1/stores/$menuBoundStore/menus"),
            menuBoundActor,
            "store-menu-overflow-001",
            boundaryContent(menuBoundStore, UUID.randomUUID(), optionCount = 0, configurationCount = 0, firstRequirementCount = 0),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_menu WHERE store_id = ?", Long::class.java, menuBoundStore))
            .isEqualTo(1_000)

        cleanDatabase()
        val optionBoundStore = seedStore()
        val optionBoundActor = signIn("catalog.store.option.boundary", optionBoundStore, "OWNER")
        val menus = seedMenus(optionBoundStore, 49)
        seedOptions(menus, 100)
        mutate(
            post("/api/v1/stores/$optionBoundStore/menus"),
            optionBoundActor,
            "store-option-boundary-001",
            boundaryContent(optionBoundStore, UUID.randomUUID(), optionCount = 100, configurationCount = 0, firstRequirementCount = 0),
        ).andExpect(status().isCreated)
        mutate(
            post("/api/v1/stores/$optionBoundStore/menus"),
            optionBoundActor,
            "store-option-overflow-001",
            boundaryContent(optionBoundStore, UUID.randomUUID(), optionCount = 1, configurationCount = 0, firstRequirementCount = 0),
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant_menu_option", Long::class.java)).isEqualTo(5_000)
    }

    private fun mutate(
        builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder,
        actor: MerchantSession,
        key: String,
        json: String,
    ) = mockMvc.perform(
        builder
            .cookie(actor.session, actor.csrf)
            .header(CSRF_HEADER, actor.csrf.value)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json),
    )

    private fun content(
        storeId: UUID,
        menuId: UUID,
        optionId: UUID,
        configurationId: UUID,
        sellableUnitId: UUID,
        name: String,
        price: Long,
        expectedVersion: Long? = null,
        seedStock: Boolean = true,
    ): String {
        if (seedStock) seedSellableUnit(storeId, sellableUnitId)
        return """
            {
              ${expectedVersion?.let { "\"expectedVersion\":$it," }.orEmpty()}
              "menuId":"$menuId","name":"$name","basePriceKrw":$price,"available":true,
              "options":[{"optionId":"$optionId","name":"샷 추가","additionalPriceKrw":500,"available":true}],
              "configurations":[{
                "configurationId":"$configurationId","selectedOptionIds":["$optionId"],"available":true,
                "requirements":[{"sellableUnitId":"$sellableUnitId","quantityPerLineUnit":1}]
              }]
            }
            """.trimIndent()
    }

    private fun boundaryContent(
        storeId: UUID,
        menuId: UUID,
        optionCount: Int,
        configurationCount: Int,
        firstRequirementCount: Int,
    ): String {
        val optionIds = (0 until optionCount).map { UUID.randomUUID() }
        val options =
            optionIds.joinToString(",") { optionId ->
                """{"optionId":"$optionId","name":"옵션 $optionId","additionalPriceKrw":0,"available":true}"""
            }
        val sellableUnitIds = mutableSetOf<UUID>()
        val configurations =
            (0 until configurationCount).joinToString(",") { configurationIndex ->
                val selected =
                    optionIds
                        .take(9)
                        .filterIndexed { bit, _ -> configurationIndex and (1 shl bit) != 0 }
                        .joinToString(",") { "\"$it\"" }
                val requirementCount = if (configurationIndex == 0) firstRequirementCount else 1
                val requirements =
                    (0 until requirementCount).joinToString(",") {
                        val sellableUnitId = UUID.randomUUID().also(sellableUnitIds::add)
                        """{"sellableUnitId":"$sellableUnitId","quantityPerLineUnit":1}"""
                    }
                """
                {
                  "configurationId":"${UUID.randomUUID()}",
                  "selectedOptionIds":[$selected],
                  "available":true,
                  "requirements":[$requirements]
                }
                """.trimIndent()
            }
        seedSellableUnits(storeId, sellableUnitIds)
        return """
            {
              "menuId":"$menuId",
              "name":"경계 메뉴",
              "basePriceKrw":0,
              "available":${configurationCount > 0},
              "options":[$options],
              "configurations":[$configurations]
            }
            """.trimIndent()
    }

    private fun seedMenus(
        storeId: UUID,
        count: Int,
    ): List<UUID> {
        val menuIds = (0 until count).map { UUID.randomUUID() }
        jdbc.batchUpdate(
            "INSERT INTO merchant_menu (id, store_id, name, base_price_krw, available) VALUES (?, ?, ?, 0, false)",
            object : BatchPreparedStatementSetter {
                override fun getBatchSize(): Int = menuIds.size

                override fun setValues(
                    statement: PreparedStatement,
                    index: Int,
                ) {
                    statement.setObject(1, menuIds[index])
                    statement.setObject(2, storeId)
                    statement.setString(3, "경계 메뉴 ${index.toString().padStart(4, '0')}")
                }
            },
        )
        return menuIds
    }

    private fun seedOptions(
        menuIds: List<UUID>,
        perMenu: Int,
    ) {
        val rows = menuIds.flatMap { menuId -> (0 until perMenu).map { menuId to UUID.randomUUID() } }
        jdbc.batchUpdate(
            "INSERT INTO merchant_menu_option (id, menu_id, name, additional_price_krw, available) VALUES (?, ?, ?, 0, true)",
            object : BatchPreparedStatementSetter {
                override fun getBatchSize(): Int = rows.size

                override fun setValues(
                    statement: PreparedStatement,
                    index: Int,
                ) {
                    statement.setObject(1, rows[index].second)
                    statement.setObject(2, rows[index].first)
                    statement.setString(3, "옵션 ${index.toString().padStart(4, '0')}")
                }
            },
        )
    }

    private fun seedStore(): UUID =
        UUID.randomUUID().also {
            jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled) VALUES (?, true, true)", it)
        }

    private fun seedSellableUnit(
        storeId: UUID,
        sellableUnitId: UUID,
    ) {
        jdbc.update(
            """
            INSERT INTO inventory_sellable_stock
                (id, store_id, available_quantity, reserved_quantity, confirmed_quantity, version)
            VALUES (?, ?, 100000, 0, 0, 0)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            sellableUnitId,
            storeId,
        )
    }

    private fun seedSellableUnits(
        storeId: UUID,
        sellableUnitIds: Collection<UUID>,
    ) {
        if (sellableUnitIds.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO inventory_sellable_stock
                (id, store_id, available_quantity, reserved_quantity, confirmed_quantity, version)
            VALUES (?, ?, 100000, 0, 0, 0)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            sellableUnitIds.map { arrayOf(it, storeId) },
        )
    }

    private fun signIn(
        loginId: String,
        storeId: UUID,
        role: String,
    ): MerchantSession {
        val accountId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO identity_merchant_account
                (id, login_id, password_hash, credential_version, display_name, state,
                 temporary_password_expires_at, password_changed_at, locked_until, created_at, updated_at, version)
            VALUES (?, ?, ?, 0, 'Catalogue Merchant', 'ACTIVE', NULL, ?, NULL, ?, ?, 0)
            """.trimIndent(),
            accountId,
            loginId,
            passwords.encode(PASSWORD),
            Timestamp.from(NOW),
            Timestamp.from(NOW.minusSeconds(1)),
            Timestamp.from(NOW),
        )
        jdbc.update(
            """
            INSERT INTO identity_store_membership
                (id, actor_id, store_id, membership_role, status, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            accountId,
            storeId,
            role,
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
        val csrf =
            requireNotNull(
                mockMvc
                    .perform(get("/api/v1/auth/merchant/csrf"))
                    .andReturn()
                    .response
                    .getCookie(CSRF_COOKIE),
            )
        val session =
            requireNotNull(
                mockMvc
                    .perform(
                        post("/api/v1/auth/merchant/sessions")
                            .cookie(csrf)
                            .header(CSRF_HEADER, csrf.value)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"loginId":"$loginId","password":"$PASSWORD"}"""),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response
                    .getCookie(SESSION_COOKIE),
            )
        return MerchantSession(accountId, session, csrf)
    }

    private fun addMembership(
        actorId: UUID,
        storeId: UUID,
        role: String,
    ) {
        jdbc.update(
            """
            INSERT INTO identity_store_membership
                (id, actor_id, store_id, membership_role, status, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            UUID.randomUUID(),
            actorId,
            storeId,
            role,
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
    }

    private fun customerSession(): CustomerSession {
        val csrf =
            requireNotNull(
                mockMvc
                    .perform(get("/api/v1/auth/customer/csrf"))
                    .andReturn()
                    .response
                    .getCookie(CUSTOMER_CSRF_COOKIE),
            )
        val id = UUID.randomUUID()
        val login = "customer.${id.toString().take(8)}"
        jdbc.update(
            """
            INSERT INTO identity_customer_account
                (id, login_id, password_hash, display_name, state, created_at, updated_at, version)
            VALUES (?, ?, ?, 'Customer', 'ACTIVE', ?, ?, 0)
            """.trimIndent(),
            id,
            login,
            passwords.encode(PASSWORD),
            Timestamp.from(NOW),
            Timestamp.from(NOW),
        )
        val session =
            requireNotNull(
                mockMvc
                    .perform(
                        post("/api/v1/auth/customer/sessions")
                            .cookie(csrf)
                            .header(CSRF_HEADER, csrf.value)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"loginId":"$login","password":"$PASSWORD"}"""),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response
                    .getCookie(CUSTOMER_SESSION_COOKIE),
            )
        return CustomerSession(session)
    }

    private fun signInAgain(
        loginId: String,
        actorId: UUID,
    ): MerchantSession {
        val csrf =
            requireNotNull(
                mockMvc
                    .perform(get("/api/v1/auth/merchant/csrf"))
                    .andReturn()
                    .response
                    .getCookie(CSRF_COOKIE),
            )
        val session =
            requireNotNull(
                mockMvc
                    .perform(
                        post("/api/v1/auth/merchant/sessions")
                            .cookie(csrf)
                            .header(CSRF_HEADER, csrf.value)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""{"loginId":"$loginId","password":"$PASSWORD"}"""),
                    ).andExpect(status().isOk)
                    .andReturn()
                    .response
                    .getCookie(SESSION_COOKIE),
            )
        return MerchantSession(actorId, session, csrf)
    }

    private fun searchTerms(storeId: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT display_text FROM discovery_store_search_term WHERE store_id = ? AND term_kind = 'MENU_NAME' ORDER BY display_text",
                String::class.java,
                storeId,
            ).filterNotNull()

    private fun auditActions(menuId: UUID): List<String> =
        jdbc
            .queryForList(
                "SELECT action FROM operations_audit_record WHERE target_id = ? ORDER BY occurred_at, id",
                String::class.java,
                menuId,
            ).filterNotNull()

    private fun commandCount(): Long =
        requireNotNull(jdbc.queryForObject("SELECT count(*) FROM merchant_menu_catalog_command", Long::class.java))

    private data class MerchantSession(
        val actorId: UUID,
        val session: Cookie,
        val csrf: Cookie,
    )

    private data class CustomerSession(
        val session: Cookie,
    )

    private companion object {
        const val CSRF_COOKIE = "BEANFLOW_MERCHANT_XSRF"
        const val SESSION_COOKIE = "BEANFLOW_MERCHANT_SESSION"
        const val CUSTOMER_CSRF_COOKIE = "BEANFLOW_CUSTOMER_XSRF"
        const val CUSTOMER_SESSION_COOKIE = "BEANFLOW_CUSTOMER_SESSION"
        const val CSRF_HEADER = "X-BEANFLOW-CSRF"
        const val PASSWORD = "merchant-current-password-2026"
        val NOW: Instant = Instant.parse("2026-08-27T00:00:00Z")
    }
}
