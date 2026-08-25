package io.github.kdh949.beanflow.merchant.internal

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import io.github.kdh949.beanflow.operations.api.OperatorPermission
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

internal class StoreImageMigrationTest : IsolatedPostgresSupport() {
    private val jdbc by lazy { JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)) }

    @BeforeEach
    fun migrateFromCleanDatabase() {
        flyway(cleanDisabled = false).clean()
        flyway().migrate()
    }

    @Test
    fun `V65 adds the all-null or all-present store image pointer`() {
        assertThat(
            jdbc.queryForList(
                """
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_name = 'merchant_store' AND column_name LIKE 'image_%'
                 ORDER BY ordinal_position
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly("image_original_key", "image_thumbnail_key", "image_sha256", "image_updated_at")

        val storeId = UUID.randomUUID()
        jdbc.update("INSERT INTO merchant_store (id, accepting_orders, pickup_enabled, version) VALUES (?, true, true, 0)", storeId)
        assertThatThrownBy {
            jdbc.update("UPDATE merchant_store SET image_sha256 = ? WHERE id = ?", HASH, storeId)
        }.isInstanceOf(DataIntegrityViolationException::class.java)

        jdbc.update(
            """
            UPDATE merchant_store
               SET image_original_key = 'stores/example/original.jpg',
                   image_thumbnail_key = 'stores/example/thumbnail.jpg',
                   image_sha256 = ?,
                   image_updated_at = now()
             WHERE id = ?
            """.trimIndent(),
            HASH,
            storeId,
        )
        assertThat(jdbc.queryForObject("SELECT image_sha256 FROM merchant_store WHERE id = ?", String::class.java, storeId))
            .isEqualTo(HASH)
    }

    @Test
    fun `V65 expands the permission and audit action vocabularies without seeding a grant`() {
        assertThat(OperatorPermission.entries.map(OperatorPermission::name)).contains("STORE_MEDIA_MANAGE")
        jdbc.update(
            """
            INSERT INTO operations_operator_permission_grant
                (actor_id, permission, state, granted_at, version, audit_source_reference)
            VALUES (?, 'STORE_MEDIA_MANAGE', 'ACTIVE', now(), 1, ?)
            """.trimIndent(),
            UUID.randomUUID(),
            "store-image-migration-test:${UUID.randomUUID()}",
        )
        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO operations_operator_permission_grant
                    (actor_id, permission, state, granted_at, version, audit_source_reference)
                VALUES (?, 'STORE_MEDIA_ADMIN', 'ACTIVE', now(), 1, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                "store-image-migration-test:${UUID.randomUUID()}",
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(
            jdbc.queryForList(
                """
                SELECT action
                  FROM operations_audit_action_category
                 WHERE action IN ('STORE_IMAGE_UPDATED', 'STORE_IMAGE_DELETED')
                 ORDER BY action
                """.trimIndent(),
                String::class.java,
            ),
        ).containsExactly("STORE_IMAGE_DELETED", "STORE_IMAGE_UPDATED")
    }

    @Test
    fun `V65 remains applied and does not grant store media permission`() {
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '65' AND success",
                Long::class.java,
            ),
        ).isOne()
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM operations_operator_permission_grant WHERE permission = 'STORE_MEDIA_MANAGE'",
                Long::class.java,
            ),
        ).isZero()
    }

    private fun flyway(cleanDisabled: Boolean = true): Flyway =
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .cleanDisabled(cleanDisabled)
            .load()

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
