package io.github.kdh949.beanflow.schema

import io.github.kdh949.beanflow.IsolatedPostgresSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource

internal class FlywayMigrationSmokeTest : IsolatedPostgresSupport() {
    @Test
    fun `fresh database migrates from V1 to the current version and validates cleanly`() {
        val flyway =
            Flyway
                .configure()
                .dataSource(
                    DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
                ).locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()

        val firstMigration = flyway.migrate()
        val validation = flyway.validateWithResult()
        val repeatedMigration = flyway.migrate()

        assertThat(firstMigration.success).isTrue()
        assertThat(firstMigration.targetSchemaVersion.toString()).isEqualTo(CURRENT_SCHEMA_VERSION)
        assertThat(validation.validationSuccessful).isTrue()
        assertThat(validation.invalidMigrations).isEmpty()
        assertThat(repeatedMigration.success).isTrue()
        assertThat(repeatedMigration.migrationsExecuted).isZero()
        assertThat(
            flyway
                .info()
                .current()
                .version
                .toString(),
        ).isEqualTo(CURRENT_SCHEMA_VERSION)
    }

    private companion object {
        const val CURRENT_SCHEMA_VERSION = "69"
    }
}
